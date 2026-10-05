package com.ollacore.app.data.repository

import com.ollacore.app.data.model.*
import com.ollacore.app.data.remote.OllacoreApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

class ChatRepository(private val api: OllacoreApi) {

    suspend fun listMessages(roomToken: String, roomId: String, afterSeq: Int? = null, beforeSeq: Int? = null, limit: Int = 50): Result<MessageListResponse> = withContext(Dispatchers.IO) {
        runCatching { api.listMessages(roomToken, roomId, afterSeq, beforeSeq, limit) }
    }

    suspend fun sendMessage(roomToken: String, roomId: String, clientMessageId: String, body: Map<String, JsonElement>, kind: String = "text", replyTo: String? = null, attachmentIds: List<String> = emptyList()): Result<MessageResponse> = withContext(Dispatchers.IO) {
        runCatching { api.sendMessage(roomToken, roomId, clientMessageId, body, kind, replyTo, attachmentIds) }
    }

    suspend fun editMessage(roomToken: String, roomId: String, messageId: String, body: Map<String, JsonElement>): Result<MessageResponse> = withContext(Dispatchers.IO) {
        runCatching { api.editMessage(roomToken, roomId, messageId, body) }
    }

    suspend fun deleteMessage(roomToken: String, roomId: String, messageId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { api.deleteMessage(roomToken, roomId, messageId) }
    }

    suspend fun addReaction(roomToken: String, roomId: String, messageId: String, emoji: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { api.addReaction(roomToken, roomId, messageId, emoji) }
    }

    suspend fun removeReaction(roomToken: String, roomId: String, messageId: String, emoji: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { api.removeReaction(roomToken, roomId, messageId, emoji) }
    }

    suspend fun markDelivered(roomToken: String, roomId: String, messageId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { api.markDelivered(roomToken, roomId, messageId) }
    }

    suspend fun markRead(roomToken: String, roomId: String, messageId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { api.markRead(roomToken, roomId, messageId) }
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
