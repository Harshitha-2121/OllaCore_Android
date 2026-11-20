package com.ollacore.app.data.repository

import com.ollacore.app.data.db.ConversationEntity
import com.ollacore.app.data.db.MediaDownloadStatus
import com.ollacore.app.data.db.MediaEntity
import com.ollacore.app.data.db.MessageEntity
import com.ollacore.app.data.db.OllacoreDatabase
import com.ollacore.app.data.db.PendingMessageEntity
import com.ollacore.app.data.db.bodyFromJson
import com.ollacore.app.data.db.bodyToJson
import com.ollacore.app.data.db.previewOf
import com.ollacore.app.data.db.toEntity
import com.ollacore.app.data.db.toModel
import com.ollacore.app.data.model.*
import com.ollacore.app.data.remote.OllacoreApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

/**
 * Write-through cache over Room (SQLite):
 * - Reads: UI observes Room first ([observeMessages]/[observeInbox]).
 * - Writes: network success -> upsert into Room + refresh conversation row.
 * - Sends: optimistic SENDING row + [PendingMessageEntity] outbox row;
 *   ack deletes outbox, failure marks FAILED for retry.
 *
 * [db] is nullable so unit tests can pass ChatRepository(api) without Room.
 */
class ChatRepository(
    private val api: OllacoreApi,
    private val db: OllacoreDatabase? = null
) {

    // ── Offline-first reads ──────────────────────────────────────────

    fun observeMessages(roomId: String): Flow<List<MessageResponse>> {
        val dao = db?.messageDao() ?: return kotlinx.coroutines.flow.flowOf(listOf())
        return dao.observeByRoom(roomId).map { list -> list.map { it.toModel() } }
    }

    fun observeInbox(): Flow<List<ConversationEntity>> {
        val dao = db?.conversationDao() ?: return kotlinx.coroutines.flow.flowOf(listOf())
        return dao.observeInbox()
    }

    fun observePending(roomId: String): Flow<List<PendingMessageEntity>> {
        val dao = db?.pendingDao() ?: return kotlinx.coroutines.flow.flowOf(listOf())
        return dao.observeByRoom(roomId)
    }

    suspend fun cachedMessages(roomId: String, limit: Int = 50, offset: Int = 0): List<MessageResponse> =
        withContext(Dispatchers.IO) {
            db?.messageDao()?.pageByRoom(roomId, limit, offset)?.map { it.toModel() } ?: emptyList()
        }

    /** msgId + clientMessageId -> statusRank for tick restore (Room authoritative). */
    suspend fun cachedStatuses(roomId: String): Map<String, Int> = withContext(Dispatchers.IO) {
        val dao = db?.messageDao() ?: return@withContext emptyMap()
        dao.pageByRoom(roomId, limit = 500, offset = 0)
            .flatMap { e ->
                listOfNotNull(e.msgId to e.statusRank, e.clientMessageId?.to(e.statusRank))
            }.toMap()
    }

    suspend fun cacheMessages(messages: List<MessageResponse>) = withContext(Dispatchers.IO) {
        if (messages.isEmpty()) return@withContext
        db?.messageDao()?.upsertAll(messages.map { it.toEntity(statusRank = 1) })
        // Refresh conversation preview rows + media metadata.
        val convDao = db?.conversationDao()
        val mediaDao = db?.mediaDao()
        messages.groupBy { it.roomId }.forEach { (roomId, list) ->
            val latest = list.maxByOrNull { it.eventSeq } ?: return@forEach
            val existing = convDao?.get(roomId)
            convDao?.upsert(
                (existing ?: ConversationEntity(roomId = roomId, kind = latest.kind)).copy(
                    lastMessagePreview = previewOf(latest.kind, latest.body),
                    lastMessageAt = latest.createdAt,
                    lastEventSeq = maxOf(existing?.lastEventSeq ?: 0, latest.eventSeq),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        val mediaRows = messages.flatMap { m ->
            attachmentRefIds(m).map { attId ->
                val info = m.attachments.firstOrNull { (it.attachmentId ?: it.id) == attId }
                MediaEntity(
                    attachmentId = attId,
                    roomId = m.roomId,
                    messageId = m.id,
                    filename = info?.filename,
                    mime = info?.mime,
                    byteSize = info?.byteSize,
                    downloadStatus = MediaDownloadStatus.PENDING,
                    updatedAt = System.currentTimeMillis()
                )
            }
        }
        if (mediaRows.isNotEmpty()) mediaDao?.upsertAll(mediaRows)
    }

    /** Room is authoritative for ticks: upgrade by server id AND client echo id. */
    suspend fun updateLocalStatus(msgId: String, rank: Int) = withContext(Dispatchers.IO) {
        db?.messageDao()?.upgradeStatus(msgId, rank)
        db?.messageDao()?.upgradeStatusByClientId(msgId, rank)
    }

    suspend fun retryPending(): List<PendingMessageEntity> = withContext(Dispatchers.IO) {
        db?.pendingDao()?.all() ?: emptyList()
    }

    // ── Network (write-through) ──────────────────────────────────────

    suspend fun listMessages(roomToken: String, roomId: String, afterSeq: Int? = null, beforeSeq: Int? = null, limit: Int = 50): Result<MessageListResponse> = withContext(Dispatchers.IO) {
        val res = runCatching { api.listMessages(roomToken, roomId, afterSeq, beforeSeq, limit) }
        res.onSuccess { cacheMessages(it.messages) }
        res
    }

    suspend fun sendMessage(roomToken: String, roomId: String, clientMessageId: String, body: Map<String, JsonElement>, kind: String = "text", replyTo: String? = null, attachmentIds: List<String> = emptyList()): Result<MessageResponse> = withContext(Dispatchers.IO) {
        // Optimistic row + outbox entry so offline sends survive restart.
        val now = System.currentTimeMillis()
        db?.pendingDao()?.insert(
            PendingMessageEntity(
                clientMessageId = clientMessageId,
                roomId = roomId,
                kind = kind,
                bodyJson = bodyToJson(body),
                replyTo = replyTo,
                createdAt = now
            )
        )
        db?.messageDao()?.upsert(
            MessageEntity(
                msgId = clientMessageId,
                roomId = roomId,
                kind = kind,
                bodyJson = bodyToJson(body),
                clientMessageId = clientMessageId,
                replyTo = replyTo,
                statusRank = 0,
                attachmentIdsCsv = attachmentIds.joinToString(","),
                createdAt = ""
            )
        )
        val res = runCatching { api.sendMessage(roomToken, roomId, clientMessageId, body, kind, replyTo, attachmentIds) }
        res.onSuccess { msg ->
            db?.pendingDao()?.remove(clientMessageId)
            db?.messageDao()?.delete(clientMessageId)
            db?.messageDao()?.upsert(msg.toEntity(statusRank = 1))
            cacheMessages(listOf(msg))
        }.onFailure { e ->
            db?.messageDao()?.upsert(
                MessageEntity(
                    msgId = clientMessageId,
                    roomId = roomId,
                    kind = kind,
                    bodyJson = bodyToJson(body),
                    clientMessageId = clientMessageId,
                    replyTo = replyTo,
                    statusRank = -1,
                    attachmentIdsCsv = attachmentIds.joinToString(","),
                    createdAt = ""
                )
            )
            db?.pendingDao()?.markFailed(clientMessageId, e.message)
        }
        res
    }

    suspend fun editMessage(roomToken: String, roomId: String, messageId: String, body: Map<String, JsonElement>): Result<MessageResponse> = withContext(Dispatchers.IO) {
        val res = runCatching { api.editMessage(roomToken, roomId, messageId, body) }
        res.onSuccess { cacheMessages(listOf(it)) }
        res
    }

    suspend fun deleteMessage(roomToken: String, roomId: String, messageId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val res = runCatching { api.deleteMessage(roomToken, roomId, messageId) }
        res.onSuccess { db?.messageDao()?.delete(messageId) }
        res
    }

    suspend fun addReaction(roomToken: String, roomId: String, messageId: String, emoji: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { api.addReaction(roomToken, roomId, messageId, emoji) }
    }

    suspend fun removeReaction(roomToken: String, roomId: String, messageId: String, emoji: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { api.removeReaction(roomToken, roomId, messageId, emoji) }
    }

    suspend fun markDelivered(roomToken: String, roomId: String, messageId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val res = runCatching { api.markDelivered(roomToken, roomId, messageId) }
        res.onSuccess { db?.messageDao()?.upgradeStatus(messageId, 2) }
        res
    }

    suspend fun markRead(roomToken: String, roomId: String, messageId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val res = runCatching { api.markRead(roomToken, roomId, messageId) }
        res.onSuccess {
            db?.messageDao()?.upgradeStatus(messageId, 3)
            db?.conversationDao()?.clearUnread(roomId)
        }
        res
    }

    suspend fun getUnread(roomToken: String, roomId: String): Result<UnreadResponse> = withContext(Dispatchers.IO) {
        runCatching { api.getUnread(roomToken, roomId) }
    }

    suspend fun getParticipants(roomToken: String, roomId: String): Result<ParticipantsResponse> = withContext(Dispatchers.IO) {
        runCatching { api.getParticipants(roomToken, roomId) }
    }

    // ── Group management (create/participants confirmed; rest surface backend errors to UI) ──

    suspend fun addMember(roomToken: String, roomId: String, userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { api.addMember(roomToken, roomId, userId) }
    }

    suspend fun removeMember(roomToken: String, roomId: String, principalId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { api.removeMember(roomToken, roomId, principalId) }
    }

    suspend fun leaveGroup(roomToken: String, roomId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { api.leaveGroup(roomToken, roomId) }
    }

    suspend fun updateGroup(roomToken: String, roomId: String, name: String? = null, description: String? = null, iconUrl: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { api.updateGroup(roomToken, roomId, name, description, iconUrl) }
    }

    suspend fun setMemberRole(roomToken: String, roomId: String, principalId: String, role: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { api.setMemberRole(roomToken, roomId, principalId, role) }
    }

    suspend fun createInvite(roomToken: String, roomId: String): Result<GroupInviteResponse> = withContext(Dispatchers.IO) {
        runCatching { api.createInvite(roomToken, roomId) }
    }

    suspend fun initAttachment(roomToken: String, roomId: String, filename: String, mimeType: String, byteSize: Long): Result<AttachmentInitResponse> = withContext(Dispatchers.IO) {
        runCatching { api.initAttachment(roomToken, roomId, filename, mimeType, byteSize) }
    }

    suspend fun completeAttachment(roomToken: String, roomId: String, attachmentId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { api.completeAttachment(roomToken, roomId, attachmentId) }
    }

    suspend fun downloadAttachment(roomToken: String, roomId: String, attachmentId: String): Result<AttachmentDownloadResponse> = withContext(Dispatchers.IO) {
        runCatching { api.downloadAttachment(roomToken, roomId, attachmentId) }
    }

    suspend fun searchMessages(roomToken: String, roomId: String, query: String, limit: Int = 20): Result<MessageListResponse> = withContext(Dispatchers.IO) {
        // Offline-first: try local FTS-like search, fall back to server.
        val local = db?.messageDao()?.searchByRoom(roomId, query)?.map { it.toModel() }
        if (!local.isNullOrEmpty()) return@withContext Result.success(MessageListResponse(local, hasMore = false))
        runCatching { api.searchMessages(roomToken, roomId, query, limit) }
    }

    suspend fun initMultipartUpload(roomToken: String, roomId: String, filename: String, mimeType: String, byteSize: Long, partSize: Long = 8388608): Result<MultipartInitResponse> = withContext(Dispatchers.IO) {
        runCatching { api.initMultipartUpload(roomToken, roomId, filename, mimeType, byteSize, partSize) }
    }

    suspend fun completeMultipartUpload(roomToken: String, roomId: String, attachmentId: String, parts: List<MultipartPart>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { api.completeMultipartUpload(roomToken, roomId, attachmentId, parts) }
    }

    suspend fun uploadKeys(roomToken: String, deviceId: String, deviceKeys: Map<String, JsonElement>, oneTimeKeys: Map<String, JsonElement>): Result<E2eeKeyUploadResponse> = withContext(Dispatchers.IO) {
        runCatching { api.uploadKeys(roomToken, deviceId, deviceKeys, oneTimeKeys) }
    }

    suspend fun queryKeys(roomToken: String, query: Map<String, List<String>>): Result<E2eeKeyQueryResponse> = withContext(Dispatchers.IO) {
        runCatching { api.queryKeys(roomToken, query) }
    }

    suspend fun claimKeys(roomToken: String, claims: Map<String, Map<String, String>>): Result<E2eeKeyClaimResponse> = withContext(Dispatchers.IO) {
        runCatching { api.claimKeys(roomToken, claims) }
    }

    suspend fun sendToDevice(roomToken: String, eventType: String, messages: Map<String, Map<String, JsonElement>>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { api.sendToDevice(roomToken, eventType, messages) }
    }

    suspend fun fetchToDeviceMessages(roomToken: String): Result<ToDeviceMessagesResponse> = withContext(Dispatchers.IO) {
        runCatching { api.fetchToDeviceMessages(roomToken) }
    }

    suspend fun uploadKeyPackages(roomToken: String, deviceId: String, packages: List<String>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { api.uploadKeyPackages(roomToken, deviceId, packages) }
    }

    suspend fun getKeyPackageCount(roomToken: String): Result<KeyPackageCountResponse> = withContext(Dispatchers.IO) {
        runCatching { api.getKeyPackageCount(roomToken) }
    }

    suspend fun consumeKeyPackages(roomToken: String, roomId: String, principalId: String): Result<KeyPackageConsumeResponse> = withContext(Dispatchers.IO) {
        runCatching { api.consumeKeyPackages(roomToken, roomId, principalId) }
    }
}
