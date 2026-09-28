package com.ollacore.app.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ollacore.app.OllacoreApp
import com.ollacore.app.data.e2ee.E2eeManager
import com.ollacore.app.data.e2ee.ForwardSecrecyManager
import com.ollacore.app.data.e2ee.MlsMessageHandler
import com.ollacore.app.data.e2ee.MlsMessageResult
import com.ollacore.app.data.e2ee.RemovalReason
import com.ollacore.app.data.model.EncryptedMessageKind
import com.ollacore.app.data.model.MessageResponse
import com.ollacore.app.data.model.attachmentRefIds
import com.ollacore.app.data.remote.ChatWebSocket
import com.ollacore.app.data.remote.WebSocketEvent
import com.ollacore.app.data.remote.WsMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID

/** WhatsApp-style message status driven by Ollacore ack + delivery/read receipts (Android state job, no new API). */
enum class MessageStatus(val rank: Int) {
    SENDING(0),
    SENT(1),
    DELIVERED(2),
    READ(3),
    FAILED(-1); // terminal until Retry -> SENDING
}

data class ChatUiState(
    val messages: List<MessageResponse> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isConnected: Boolean = false,
    val typingUsers: Set<String> = emptySet(),
    val onlineUsers: Set<String> = emptySet(),
    // ── History pagination (server pages oldest-first; open lands on latest) ──
    val hasMoreHistory: Boolean = false,
    val loadingHistory: Boolean = false,
    val replyTo: MessageResponse? = null,
    val editingMessage: MessageResponse? = null,
    val roomToken: String? = null,
    val isEncrypted: Boolean = false,
    val needsKeyRotation: Boolean = false,
    val currentEpoch: Int = 0,
    // ── Message status ticks (keyed by message id AND client_message_id) ──
    val messageStatus: Map<String, MessageStatus> = emptyMap(),
    val currentUserId: String = "",
    // ── Conversation identity (from inbox; drives header + group routing) ──
    val kind: String = "",
    val groupRevision: Int = 0,
    // ── Group roster (participants API; drives sender labels + member count) ──
    val participantNames: Map<String, String> = emptyMap(),
    val participantPhones: Map<String, String> = emptyMap(),
    val participantCount: Int = 0,
    // ── Incoming call ringing (WS call.started; accept routes to the call screen) ──
    val incomingCall: IncomingCall? = null,
    // ── Deleted-message tombstones (WhatsApp-style placeholder, live events) ──
    val deletedIds: Set<String> = emptySet(),
    // WhatsApp-style chat upgrade (Category 1 - API YES except star/select are client-only)
    val starredIds: Set<String> = emptySet(),
    val selectedIds: Set<String> = emptySet(),
    val selectionMode: Boolean = false,
    val peerName: String? = null,
    val peerAvatarUrl: String? = null,
    val peerPhone: String? = null,
    val peerUserId: String? = null,
    // ── Media & attachments (Category 1 - Ollacore API YES: init/multipart/download + WS attachment.ready/failed) ──
    val attachmentUrls: Map<String, String> = emptyMap(),
    /** attachmentId -> epoch ms when the presigned download URL dies (server ~10 min). */
    val attachmentUrlExpiry: Map<String, Long> = emptyMap(),
    val uploadingFilename: String? = null,
    val uploadProgress: Float = 0f,
    val isUploading: Boolean = false,
    val uploadError: String? = null,
    // ── Voice recorder draft (spec 13; sent as kind=audio which renders today) ──
    val voiceDraft: VoiceDraft = VoiceDraft(),
    // Non-blocking recorder failures (permission, busy mic, too short).
    // Rendered as a dismissible banner above the composer, never fullscreen.
    val recordError: String? = null
)

data class VoiceDraft(
    val isRecording: Boolean = false,
    val elapsedMs: Long = 0L,
    val amplitudes: List<Int> = emptyList()
)

/** An incoming voice/video call announced over the chat socket or push. */
data class IncomingCall(
    val roomId: String,
    val callId: String,
    val initiator: String
)

data class ForwardSecrecyInfo(
    val currentEpoch: Int,
    val activeMembers: Set<String>,
    val revokedMembers: Set<String>,
    val rotationCount: Int,
    val pendingRotation: Boolean
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as OllacoreApp).container
    private val chatRepo = container.chatRepository
    private val directoryRepo = container.directoryRepository
    private val sessionStore = container.sessionStore
    private val e2eeManager = container.e2eeManager
    private val forwardSecrecyManager = container.forwardSecrecyManager
    private val pushConfigManager = container.pushConfigManager
    private val statusStore = container.messageStatusStore
    private val mlsHandler = MlsMessageHandler()

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var chatWebSocket: ChatWebSocket? = null
    private var roomId: String = ""
    private var currentUserId: String = ""

    // ── Message status correlation (Category 1 - ack/receipts already in Ollacore WS) ──
    /** request_id (WS frame) -> client_message_id, so ack/error can mark Sending/Sent/Failed. */
    private val requestToClient = mutableMapOf<String, String>()
    /** client_message_id -> payload kept for Failed -> Retry resend. */
    private val pendingPayloads = mutableMapOf<String, PendingSend>()

    init {
        // Restore tick states known before a restart; live receipts upgrade
        // from here, so reopened chats never flash back to single-tick.
        viewModelScope.launch {
            val stored = runCatching { statusStore.snapshot() }.getOrElse { emptyMap() }
            if (stored.isNotEmpty()) {
                _uiState.update { state ->
                    val merged = state.messageStatus.toMutableMap()
                    stored.forEach { (id, rank) ->
                        val status = MessageStatus.entries.find { it.rank == rank } ?: return@forEach
                        val cur = merged[id]
                        if (cur == null || status.rank > cur.rank) merged[id] = status
                    }
                    state.copy(messageStatus = merged)
                }
            }
        }
    }

    private data class PendingSend(
        val kind: String,
        val body: kotlinx.serialization.json.JsonObject,
        val attachmentIds: List<String>,
        val replyTo: String?
    )

    fun joinRoom(roomId: String) {
        this.roomId = roomId
        viewModelScope.launch {
            // No session at all (logged out elsewhere): surface auth UI instead of
            // hanging on a blank screen forever.
            val token = sessionStore.sessionToken.first() ?: run {
                _uiState.update { it.copy(isLoading = false, error = "session expired") }
                return@launch
            }
            currentUserId = sessionStore.userId.first() ?: ""
            // Local menu enforcement must be in place BEFORE history lands.
            blockedCache = runCatching { container.chatPrefsStore.blockedUsers.first() }.getOrElse { emptySet() }
            clearedBeforeSeq = runCatching { container.chatPrefsStore.getClearedBefore(roomId) }.getOrNull()

            val device_id = "android-${UUID.randomUUID()}"
            directoryRepo.getRoomToken(token, roomId, device_id)
                .onSuccess { response ->
                    _uiState.update {
                        it.copy(
                            roomToken = response.accessToken,
                            isEncrypted = response.e2ee?.encrypted == true,
                            currentUserId = currentUserId
                        )
                    }
                    // Conversation identity for header + group routing (Category 1 - inbox YES)
                    directoryRepo.getInbox(token).onSuccess { inbox ->
                        inbox.conversations.find { it.roomId == roomId }?.let { item ->
                            val isGroup = item.kind.equals("group", ignoreCase = true)
                            val serverName = if (isGroup) item.name
                                ?: "Group" else item.name
                                ?: item.peer?.displayName
                                ?: item.peer?.phone
                                ?: "Chat"
                            // Local alias wins over the server name (contact rename).
                            val alias = runCatching { container.chatPrefsStore.getAlias(roomId) }.getOrNull()
                            _uiState.update {
                                it.copy(
                                    kind = item.kind,
                                    peerName = alias ?: serverName,
                                    peerPhone = if (isGroup) null else item.peer?.phone,
                                    peerUserId = if (isGroup) null else item.peer?.userId
                                )
                            }
                            if (isGroup) loadParticipants(response.accessToken)
                        }
                    }
                    // Restore persisted stars for this room (bodies stay server-side).
                    _uiState.update {
                        it.copy(
                            starredIds = runCatching { container.chatPrefsStore.getStarredIds(roomId) }
                                .getOrElse { emptySet() }
                        )
                    }
                    if (response.e2ee != null) {
                        pushConfigManager.setE2eeConfig(response.e2ee)
                    }
                    connectWebSocket(response.chatWebsocketUrl, response.accessToken)
                    loadMessages(response.accessToken)
                    refreshMenuState()
                }
                .onFailure { e ->
                    android.util.Log.w("ChatNet", "joinRoom($roomId) failed", e)
                    _uiState.update { it.copy(error = e.message ?: "Failed to join room") }
                }
        }
    }

    private fun connectWebSocket(url: String, roomToken: String) {
        chatWebSocket = ChatWebSocket(url, roomToken) { event ->
            viewModelScope.launch { handleWebSocketEvent(event) }
        }
        chatWebSocket?.connect(viewModelScope)
    }

    private suspend fun handleWebSocketEvent(event: WebSocketEvent) {
        when (event) {
            is WebSocketEvent.Connected -> {
                _uiState.update { it.copy(isConnected = true) }
            }
            is WebSocketEvent.Disconnected -> {
                _uiState.update { it.copy(isConnected = false) }
            }
            is WebSocketEvent.Error -> {
                // Correlated send failure -> mark Failed (keeps payload for Retry); otherwise surface banner
                val clientId = event.requestId?.let { requestToClient.remove(it) }
                if (clientId != null) {
                    setStatus(clientId, MessageStatus.FAILED)
                } else {
                    _uiState.update { it.copy(error = event.message) }
                }
            }
            is WebSocketEvent.Ack -> {
                // Server accepted our send -> Sent ✓ (upgrade from Sending ○)
                val clientId = requestToClient.remove(event.requestId)
                if (clientId != null) {
                    setStatus(clientId, MessageStatus.SENT)
                    pendingPayloads.remove(clientId)
                }
            }
            is WebSocketEvent.MessageCreated -> {
                val msg = event.message
                val response = MessageResponse(
                    id = msg.id,
                    roomId = msg.roomId,
                    senderId = msg.senderId,
                    kind = msg.kind,
                    body = msg.body,
                    createdAt = msg.createdAt,
                    eventSeq = msg.eventSeq,
                    clientMessageId = msg.clientMessageId,
                    replyTo = msg.replyTo,
                    editedAt = msg.editedAt,
                    attachmentIds = msg.attachmentIds
                )

                // Process MLS encrypted message kinds
                if (mlsHandler.shouldProcessAsEncrypted(msg.kind)) {
                    val mlsResult = mlsHandler.processMessage(response)
                    handleMlsResult(mlsResult)
                    
                    // Don't add MLS protocol messages to chat UI
                    if (msg.kind != "mls.application") {
                        return
                    }
                }

                // Local enforcement: blocked senders + cleared watermarks never reach the list.
                val cut = clearedBeforeSeq
                if (msg.senderId in blockedCache || (cut != null && msg.eventSeq <= cut)) return
                // Own-message echo: server stored it -> at least Sent ✓ (ack may arrive separately)
                if (msg.senderId == currentUserId && msg.clientMessageId != null) {
                    setStatus(msg.clientMessageId, MessageStatus.SENT)
                    setStatus(msg.id, MessageStatus.SENT)
                    pendingPayloads.remove(msg.clientMessageId)
                }
                _uiState.update {
                    it.copy(messages = it.messages + response)
                }
                // Prefetch download URLs for new media messages (Ollacore API: GET /rooms/{id}/attachments/{aid}/download)
                attachmentRefIds(response).forEach { aid -> resolveAttachmentUrl(aid, auto = true) }
                if (msg.senderId != currentUserId) {
                    chatWebSocket?.markDelivered(roomId, msg.id)
                }
            }
            is WebSocketEvent.ReceiptDelivered -> {
                // Peer device received -> Delivered ✓✓ (never downgrades Read)
                upgradeStatus(event.messageId, MessageStatus.DELIVERED)
            }
            is WebSocketEvent.ReceiptRead -> {
                // Peer opened chat -> Read ✓✓ pink
                setStatus(event.messageId, MessageStatus.READ)
            }
            is WebSocketEvent.MessageUpdated -> {
                val msg = event.message
                _uiState.update {
                    it.copy(messages = it.messages.map { m ->
                        if (m.id == msg.id) m.copy(body = msg.body, editedAt = msg.editedAt, attachmentIds = msg.attachmentIds) else m
                    })
                }
            }
            is WebSocketEvent.AttachmentReady -> {
                // Ollacore WS: attachment.ready -> fetch presigned download URL so bubbles can render
                signalAttachmentReady(event.attachmentId, true)
                resolveAttachmentUrl(event.attachmentId, auto = true)
            }
            is WebSocketEvent.AttachmentFailed -> {
                signalAttachmentReady(event.attachmentId, false)
                _uiState.update { it.copy(uploadError = "Attachment failed: ${event.attachmentId}") }
            }
            is WebSocketEvent.MessageDeleted -> {
                // Keep the row as a tombstone ("This message was deleted") instead
                // of vanishing it; history reloads naturally drop it server-side.
                _uiState.update {
                    it.copy(deletedIds = it.deletedIds + event.messageId)
                }
            }
            is WebSocketEvent.ReactionAdded -> {
                // Reactions are aggregated in message history
            }
            is WebSocketEvent.ReactionRemoved -> {
                // Reactions are aggregated in message history
            }
            is WebSocketEvent.MemberAdded -> {
                // Live roster change: forward-secrecy rotation + bump revision so Group Info refreshes
                onMemberAdded(event.principalId)
                _uiState.update { it.copy(groupRevision = it.groupRevision + 1) }
                _uiState.value.roomToken?.let { loadParticipants(it) }
            }
            is WebSocketEvent.MemberRemoved -> {
                onMemberRemoved(event.principalId)
                _uiState.update { it.copy(groupRevision = it.groupRevision + 1) }
                _uiState.value.roomToken?.let { loadParticipants(it) }
            }
            is WebSocketEvent.CallStarted -> {
                // Ring unless it's our own outgoing call echoing from another device.
                if (event.initiator != currentUserId) {
                    _uiState.update {
                        it.copy(incomingCall = IncomingCall(roomId = event.roomId, callId = event.callId, initiator = event.initiator))
                    }
                }
            }
            is WebSocketEvent.CallEnded -> {
                // Unanswered ringing call that ends -> CLIENT-ONLY missed-call log (no backend).
                val ringing = _uiState.value.incomingCall
                if (ringing != null && ringing.roomId == event.roomId) {
                    val s = _uiState.value
                    viewModelScope.launch {
                        runCatching {
                            container.callLogStore.log(
                                com.ollacore.app.data.local.CallLogEntry(
                                    id = "call-${UUID.randomUUID()}",
                                    roomId = event.roomId,
                                    peerName = s.peerName ?: s.peerPhone ?: "",
                                    direction = com.ollacore.app.data.local.CallDirection.INCOMING,
                                    audioOnly = null, // media type unknown for missed calls
                                    startedAt = System.currentTimeMillis(),
                                    durationSec = 0L,
                                    status = com.ollacore.app.data.local.CallStatus.MISSED
                                )
                            )
                        }
                    }
                }
                _uiState.update {
                    if (it.incomingCall?.roomId == event.roomId) it.copy(incomingCall = null)
                    else it
                }
            }
            is WebSocketEvent.TypingStarted -> {
                // Ignore our own echo: the server rebroadcasts typing.started to
                // every participant including the sender, which made the header
                // show "typing…" while WE type (reported as own number typing).
                if (event.principalId == currentUserId) return
                _uiState.update {
                    it.copy(typingUsers = (it.typingUsers + event.principalId) - currentUserId)
                }
            }
            is WebSocketEvent.TypingStopped -> {
                if (event.principalId == currentUserId) return
                _uiState.update { it.copy(typingUsers = it.typingUsers - event.principalId) }
            }
            is WebSocketEvent.PresenceChanged -> {
                _uiState.update {
                    if (event.online) it.copy(onlineUsers = it.onlineUsers + event.principalId)
                    else it.copy(onlineUsers = it.onlineUsers - event.principalId)
                }
            }
            is WebSocketEvent.Resync -> {
                val token = sessionStore.sessionToken.first() ?: return
                directoryRepo.getRoomToken(token, roomId, "android-resync")
                    .onSuccess { loadMessages(it.accessToken) }
            }
            else -> {}
        }
    }

    private fun handleMlsResult(result: MlsMessageResult) {
        when (result) {
            is MlsMessageResult.CommitProcessed -> {
                // Commit processed - need to rekey
                viewModelScope.launch {
                    rotateSession()
                }
            }
            is MlsMessageResult.ProposalReceived -> {
                // Proposal received - may need to vote
                _uiState.update { it.copy(needsKeyRotation = true) }
            }
            is MlsMessageResult.ApplicationReceived -> {
                // Application message - decrypt and display
                // The actual decryption happens in the E2EE layer
                mlsHandler.markDecrypted(result.message.id)
            }
            is MlsMessageResult.WelcomeReceived -> {
                // Welcome received - join new group
                viewModelScope.launch {
                    processWelcome(result.senderId, result.groupInfo)
                }
            }
            is MlsMessageResult.Ignored -> { /* Not an MLS message */ }
        }
    }

    private suspend fun processWelcome(senderId: String, groupInfo: String) {
        val roomToken = _uiState.value.roomToken ?: return
        // Process the welcome and join the group
        // This would involve creating a new MLS group state
        mlsHandler.markWelcomeProcessed()
    }

    /** Group roster for sender labels / colors / member count (participants API YES). */
    private fun loadParticipants(roomToken: String) {
        viewModelScope.launch {
            chatRepo.getParticipants(roomToken, roomId)
                .onSuccess { resp ->
                    val names = resp.participants.associate { p ->
                        p.principalId to (p.displayName ?: p.phone ?: p.principalId.take(8))
                    }
                    val phones = resp.participants.associate { p ->
                        p.principalId to (p.phone ?: "")
                    }
                    _uiState.update {
                        it.copy(participantNames = names, participantPhones = phones, participantCount = resp.participants.size)
                    }
                }
        }
    }

    private suspend fun loadMessages(roomToken: String) {
        _uiState.update { it.copy(isLoading = true) }
        // Server pages oldest-first: a bare limit=50 returns the FIRST page, so rooms
        // with 50+ messages opened on ancient history and latest (often own) messages
        // never appeared. Anchor with a huge before_seq to land on the LATEST page.
        chatRepo.listMessages(roomToken, roomId, beforeSeq = Int.MAX_VALUE)
            .onSuccess { response ->
                // Seed history: own messages show at least Sent ✓ (live receipts upgrade to ✓✓/blue)
                val seeded = _uiState.value.messageStatus.toMutableMap()
                response.messages.forEach { m ->
                    if (m.senderId == currentUserId && currentUserId.isNotBlank()) {
                        if (!seeded.containsKey(m.id)) seeded[m.id] = MessageStatus.SENT
                    }
                }
                _uiState.update {
                    val liveIds = response.messages.map { it.id }.toSet()
                    it.copy(
                        messages = applyLocalViewFilter(response.messages.sortedBy { m -> m.eventSeq }),
                        isLoading = false,
                        error = null,
                        messageStatus = seeded,
                        // Drop tombstones the server no longer returns.
                        deletedIds = it.deletedIds.intersect(liveIds),
                        hasMoreHistory = response.hasMore,
                        loadingHistory = false
                    )
                }
                // Prefetch download URLs for media messages so bubbles render immediately
                response.messages.flatMap { attachmentRefIds(it) }.distinct().forEach { aid -> resolveAttachmentUrl(aid, auto = true) }
                if (response.messages.isNotEmpty()) {
                    val lastMsg = response.messages.maxByOrNull { it.eventSeq }
                    if (lastMsg != null) {
                        chatWebSocket?.markRead(roomId, lastMsg.id)
                    }
                    // Reconnect/offline catch-up: these messages ARE on this
                    // device, so acknowledge delivery for recent peer messages.
                    // Truthful even when the chat isn't opened (delivered != read);
                    // the server dedupes repeat receipts. Capped to bound traffic.
                    response.messages
                        .filter { it.senderId != currentUserId }
                        .sortedByDescending { it.eventSeq }
                        .take(20)
                        .forEach { chatWebSocket?.markDelivered(roomId, it.id) }
                }
            }
            .onFailure { e ->
                android.util.Log.w("ChatNet", "loadMessages($roomId) failed", e)
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
    }

    fun sendMessage(text: String, replyToId: String? = null) {
        val roomToken = _uiState.value.roomToken ?: return
        val clientMessageId = "local-${UUID.randomUUID()}"
        // Attach quoted reply (reply preview state) when caller doesn't pass an explicit id
        val reply = replyToId ?: _uiState.value.replyTo?.id
        val body = buildJsonObject { put("text", JsonPrimitive(text)) }

        // Optimistic Sending ○ + payload kept for Failed ⚠ -> Retry ↻
        setStatus(clientMessageId, MessageStatus.SENDING)
        pendingPayloads[clientMessageId] =
            PendingSend(kind = com.ollacore.app.data.model.MessageKinds.TEXT, body = body, attachmentIds = emptyList(), replyTo = reply)
        viewModelScope.launch {
            // Socket unavailable -> immediate Failed so user can Retry
            val requestId = chatWebSocket?.sendMessage(roomId, clientMessageId, body, replyTo = reply)
            if (requestId != null) requestToClient[requestId] = clientMessageId
            else setStatus(clientMessageId, MessageStatus.FAILED)
        }
        _uiState.update { it.copy(replyTo = null) }
    }

    fun editMessage(messageId: String, newText: String) {
        val roomToken = _uiState.value.roomToken ?: return
        viewModelScope.launch {
            chatWebSocket?.editMessage(roomId, messageId, kotlinx.serialization.json.buildJsonObject {
                put("text", JsonPrimitive(newText))
            })
        }
        _uiState.update { it.copy(editingMessage = null) }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            chatWebSocket?.deleteMessage(roomId, messageId)
        }
    }

    fun addReaction(messageId: String, emoji: String) {
        viewModelScope.launch {
            chatWebSocket?.addReaction(roomId, messageId, emoji)
        }
    }

    fun removeReaction(messageId: String, emoji: String) {
        viewModelScope.launch {
            chatWebSocket?.removeReaction(roomId, messageId, emoji)
        }
    }

    fun setReplyTo(message: MessageResponse?) {
        _uiState.update { it.copy(replyTo = message) }
    }

    fun setEditingMessage(message: MessageResponse?) {
        _uiState.update { it.copy(editingMessage = message) }
    }

    // ── WhatsApp-style: Forward / Copy / Select / Star (Category 1) ──
    // Forward reuses existing sendMessage API (POST /v1/rooms/{id}/messages / WS message.send) - no new Ollacore endpoint
    fun forwardMessage(message: MessageResponse, targetRoomId: String? = null) {
        val text = message.body["text"]?.let { try { it.jsonPrimitive.content } catch (_: Exception) { "" } } ?: ""
        val forwardBody = if (text.isNotBlank()) "[Forwarded] $text" else text
        val dest = targetRoomId ?: roomId
        // If forwarding within same chat, just send as new message; cross-chat forward is handled by caller picking target room
        if (dest == roomId) {
            sendMessage(forwardBody)
        } else {
            val fwdId = "fwd-${UUID.randomUUID()}"
            val body = buildJsonObject { put("text", JsonPrimitive(forwardBody)) }
            setStatus(fwdId, MessageStatus.SENDING)
            pendingPayloads[fwdId] =
                PendingSend(kind = com.ollacore.app.data.model.MessageKinds.TEXT, body = body, attachmentIds = emptyList(), replyTo = null)
            viewModelScope.launch {
                // For cross-room forward, caller should navigate to target and send there; we also support direct WS if same socket
                val requestId = chatWebSocket?.sendMessage(dest, fwdId, body)
                if (requestId != null) requestToClient[requestId] = fwdId
                else setStatus(fwdId, MessageStatus.FAILED)
            }
        }
    }

    fun toggleStar(messageId: String) {
        // Optimistic UI first, then persist per room (star has no Ollacore API;
        // reactions stay separate via addReaction).
        _uiState.update {
            val starred = it.starredIds.toMutableSet()
            if (messageId in starred) starred.remove(messageId) else starred.add(messageId)
            it.copy(starredIds = starred)
        }
        viewModelScope.launch {
            runCatching { container.chatPrefsStore.toggleStar(roomId, messageId) }
        }
    }

    fun toggleSelect(messageId: String) {
        _uiState.update {
            val sel = it.selectedIds.toMutableSet()
            if (messageId in sel) sel.remove(messageId) else sel.add(messageId)
            it.copy(selectedIds = sel, selectionMode = sel.isNotEmpty())
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedIds = emptySet(), selectionMode = false) }
    }

    /** Enter selection mode with nothing pre-selected (3-dot menu entry point). */
    fun enterSelectionMode() {
        _uiState.update { it.copy(selectionMode = true) }
    }

    fun isStarred(messageId: String): Boolean = messageId in _uiState.value.starredIds
    fun isSelected(messageId: String): Boolean = messageId in _uiState.value.selectedIds

    // ── 3-dot chat menu state + actions (Category 1 wired where APIs exist;
    //   the rest persist client-side with backend-spec hooks, never faked) ──

    data class ChatMenuState(
        val isFavourite: Boolean = false,
        val muteUntilMs: Long? = null,
        val disappearingTtlSec: Long = 0L,
        /** Global default prefill when the room has no explicit choice. */
        val defaultDisappearingTtlSec: Long = 0L,
        val isBlocked: Boolean = false,
        val isArchived: Boolean = false,
        val allLists: Map<String, List<String>> = emptyMap(),
        val memberOfLists: Set<String> = emptySet(),
        val cleared: Boolean = false
    )

    data class CallCandidate(val userId: String, val name: String, val phone: String)

    private val _menuState = MutableStateFlow(ChatMenuState())
    val menuState: StateFlow<ChatMenuState> = _menuState.asStateFlow()

    // Local enforcement caches (block + clear watermarks apply to live + history).
    private var blockedCache: Set<String> = emptySet()
    private var clearedBeforeSeq: Int? = null

    /**
     * Server validates attachments ASYNCHRONOUSLY after complete: sending the
     * message blindly races validation (rejected sends died silently as
     * invisible FAILED statuses). Wait for the ready/failed signal instead.
     */
    private val attachmentReadySignals =
        mutableMapOf<String, kotlinx.coroutines.CompletableDeferred<Boolean>>()

    private suspend fun awaitAttachmentReady(attachmentId: String): Boolean {
        val signal = synchronized(attachmentReadySignals) {
            attachmentReadySignals.getOrPut(attachmentId) {
                kotlinx.coroutines.CompletableDeferred()
            }
        }
        return try {
            kotlinx.coroutines.withTimeout(60_000) { signal.await() }
        } catch (_: Exception) {
            false
        } finally {
            synchronized(attachmentReadySignals) {
                if (attachmentReadySignals[attachmentId] === signal) {
                    attachmentReadySignals.remove(attachmentId)
                }
            }
        }
    }

    private fun signalAttachmentReady(attachmentId: String, ready: Boolean) {
        synchronized(attachmentReadySignals) {
            attachmentReadySignals.remove(attachmentId)
        }?.let { deferred ->
            if (!deferred.isCompleted) deferred.complete(ready)
        }
    }

    private fun peerIdsForBlock(): Set<String> {
        val ids = _uiState.value.participantNames.keys.toMutableSet()
        _uiState.value.peerUserId?.takeIf { it.isNotBlank() }?.let { ids.add(it) }
        ids.remove(currentUserId)
        return ids
    }

    fun refreshMenuState() {
        viewModelScope.launch {
            val prefs = container.chatPrefsStore
            blockedCache = runCatching {
                prefs.blockedUsers.first()
            }.getOrElse { emptySet() }
            clearedBeforeSeq = runCatching { prefs.getClearedBefore(roomId) }.getOrNull()
            _menuState.value = ChatMenuState(
                isFavourite = runCatching {
                    prefs.favouriteRooms.first().contains(roomId)
                }.getOrElse { false },
                muteUntilMs = runCatching { prefs.getMuteUntil(roomId) }.getOrNull(),
                disappearingTtlSec = runCatching { prefs.getDisappearingTtl(roomId) }.getOrElse { 0L },
                defaultDisappearingTtlSec = runCatching {
                    prefs.getCustom(
                        com.ollacore.app.data.local.ChatPrefsStore.SettingsKeys.DISAPPEAR_DEFAULT, "0"
                    ).toLongOrNull() ?: 0L
                }.getOrElse { 0L },
                isBlocked = peerIdsForBlock().any { it in blockedCache },
                isArchived = runCatching {
                    prefs.archivedRooms.first().contains(roomId)
                }.getOrElse { false },
                allLists = runCatching {
                    prefs.chatLists.first()
                }.getOrElse { emptyMap() },
                memberOfLists = runCatching { prefs.listsForRoom(roomId) }.getOrElse { emptySet() },
                cleared = clearedBeforeSeq != null
            )
        }
    }

    /** Clear-chat watermark + blocked-sender drop (local view only; server untouched). */
    private fun applyLocalViewFilter(msgs: List<MessageResponse>): List<MessageResponse> {
        val cut = clearedBeforeSeq
        return msgs.filter { m ->
            (cut == null || m.eventSeq > cut) && m.senderId !in blockedCache
        }
    }

    fun toggleFavourite() {
        viewModelScope.launch {
            runCatching { container.chatPrefsStore.toggleFavourite(roomId) }
            refreshMenuState()
        }
    }

    /** durationMs null = unmute; otherwise mute until now+duration (MUTE_ALWAYS = forever). */
    fun setMuteDuration(durationMs: Long?) {
        viewModelScope.launch {
            val until = durationMs?.let {
                if (it == com.ollacore.app.data.local.ChatPrefsStore.MUTE_ALWAYS) it
                else System.currentTimeMillis() + it
            }
            runCatching { container.chatPrefsStore.setMuteUntil(roomId, until) }
            refreshMenuState()
        }
    }

    fun setDisappearingTtl(ttlSec: Long) {
        viewModelScope.launch {
            runCatching { container.chatPrefsStore.setDisappearingTtl(roomId, ttlSec) }
            refreshMenuState()
        }
    }

    fun toggleBlock(): Boolean {
        val ids = peerIdsForBlock()
        if (ids.isEmpty()) return _menuState.value.isBlocked
        val nowBlocked = ids.any { it in blockedCache }
        viewModelScope.launch {
            ids.forEach { runCatching { container.chatPrefsStore.setUserBlocked(it, !nowBlocked) } }
            // Update the cache synchronously (same scope/thread): refreshMenuState()
            // reloads it in a sibling coroutine, which previously raced the filter.
            blockedCache = if (nowBlocked) blockedCache - ids else blockedCache + ids
            refreshMenuState()
            if (nowBlocked) {
                // Unblocked: reload history so messages hidden while blocked return
                // (server history is untouched by local block).
                _uiState.value.roomToken?.let { loadMessages(it) }
            } else {
                // Blocked: drop their bubbles from the visible list immediately.
                _uiState.update { it.copy(messages = applyLocalViewFilter(it.messages)) }
            }
        }
        return !nowBlocked
    }

    fun submitReport(reason: String) {
        viewModelScope.launch {
            val label = _uiState.value.peerName ?: "Chat"
            runCatching { container.chatPrefsStore.submitReport(roomId, label, reason) }
        }
    }

    fun createChatList(name: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = runCatching { container.chatPrefsStore.createChatList(name) }.getOrElse { false }
            refreshMenuState()
            onDone(ok)
        }
    }

    fun setRoomInList(listName: String, member: Boolean) {
        viewModelScope.launch {
            runCatching { container.chatPrefsStore.setRoomInList(listName, roomId, member) }
            refreshMenuState()
        }
    }

    fun setArchived(archived: Boolean, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching { container.chatPrefsStore.setArchived(roomId, archived) }
            refreshMenuState()
            onDone()
        }
    }

    /** Clear chat: hides everything at/under the current max seq locally (server kept). */
    fun clearChat(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val maxSeq = _uiState.value.messages.maxOfOrNull { it.eventSeq }
            if (maxSeq != null) {
                runCatching { container.chatPrefsStore.setClearedBefore(roomId, maxSeq) }
                clearedBeforeSeq = maxSeq
                _uiState.update {
                    it.copy(
                        messages = applyLocalViewFilter(it.messages),
                        replyTo = null,
                        selectedIds = emptySet(),
                        selectionMode = false
                    )
                }
            }
            refreshMenuState()
            onDone()
        }
    }

    /**
     * Delete chat: groups leave via API (DELETE rooms/{id}/members/me) then wipe local
     * view + archive; 1-to-1 has no delete-conversation endpoint (backend spec gap),
     * so it wipes the local view + archives (inbox row returns only on new messages).
     */
    fun clearChatError() {
        _uiState.update { it.copy(error = null) }
    }

    suspend fun deleteChat(): Boolean {
        val token = _uiState.value.roomToken
        val isGroup = _uiState.value.kind.equals("group", ignoreCase = true)
        if (isGroup && token != null) {
            val left = chatRepo.leaveGroup(token, roomId)
            if (left.isFailure) {
                // Stay put and say why: no silent fake-delete (leave endpoint 404s today).
                _uiState.update {
                    it.copy(error = "Couldn't leave the group: ${left.exceptionOrNull()?.message ?: "server rejected"}")
                }
                return false
            }
        }
        runCatching { container.chatPrefsStore.setClearedBefore(roomId, Int.MAX_VALUE) }
        runCatching { container.chatPrefsStore.setArchived(roomId, true) }
        clearedBeforeSeq = Int.MAX_VALUE
        _uiState.update {
            it.copy(
                messages = emptyList(),
                replyTo = null,
                selectedIds = emptySet(),
                selectionMode = false
            )
        }
        refreshMenuState()
        return true
    }

    /** Generates a unique call link, posts it in the chat, and returns it for sharing. */
    fun sendCallLink(onLink: (String) -> Unit) {
        val link = "https://call.ollacore.com/${UUID.randomUUID()}"
        sendMessage("📞 Join my call: $link")
        onLink(link)
    }

    /** Group call = create a real group conversation (existing API) then call there. */
    fun startGroupCall(name: String, memberIds: List<String>, onRoom: (String?) -> Unit) {
        viewModelScope.launch {
            val token = sessionStore.sessionToken.first() ?: run { onRoom(null); return@launch }
            val clean = memberIds.filter { it.isNotBlank() }.distinct()
            if (clean.isEmpty()) { onRoom(null); return@launch }
            val res = directoryRepo.createGroupConversation(
                token, clean, name.ifBlank { "Group call" }
            )
            onRoom(res.getOrNull()?.roomId)
        }
    }

    /** Call/group-call candidates: inbox direct peers (existing API, no new endpoint). */
    suspend fun loadCallCandidates(): List<CallCandidate> {
        val token = sessionStore.sessionToken.first() ?: return emptyList()
        val inbox = directoryRepo.getInbox(token).getOrNull() ?: return emptyList()
        return inbox.conversations.mapNotNull { item ->
            val peer = item.peer ?: return@mapNotNull null
            if (peer.userId == currentUserId) return@mapNotNull null
            CallCandidate(
                userId = peer.userId,
                name = peer.displayName ?: peer.phone,
                phone = peer.phone
            )
        }.distinctBy { it.userId }
    }

    fun copyText(message: MessageResponse): String {
        return message.body["text"]?.let { try { it.jsonPrimitive.content } catch (_: Exception) { "" } } ?: ""
    }

    // ── Media & attachments (Category 1 - Ollacore API YES) ──
    // Flow: MimeValidator.validate -> init/init-multipart -> PUT presigned -> complete -> WS message.send(kind, attachment_ids)
    // Kinds: image / video / audio / file (Ollacore-native, WhatsApp-style UI only). Location = kind "location" (backend check required).
    private val attachmentUploader = com.ollacore.app.data.remote.AttachmentUploader()

    /** attachmentId -> presigned URL; paired with [attachmentUrlExpiry] (server ~10 min TTL). */
    private val resolvingIds = mutableSetOf<String>()

    /**
     * Resolves a presigned download URL. auto=true marks BACKGROUND prefetch
     * (history bulk, attachment.ready); Settings > Storage > Media
     * auto-download gates only prefetch - tapping a bubble to view always
     * resolves on demand.
     *
     * force=true drops a cached URL first (playback hit an expired/403 link).
     * Cached URLs also re-fetch when expires_at is past (within 30s skew) -
     * proven download links live ~10 minutes; stale cache made old voice
     * bubbles fail MediaPlayer prepare with no UI feedback.
     */
    fun resolveAttachmentUrl(attachmentId: String, auto: Boolean = false, force: Boolean = false) {
        val token = _uiState.value.roomToken ?: return
        if (force) {
            _uiState.update {
                it.copy(
                    attachmentUrls = it.attachmentUrls - attachmentId,
                    attachmentUrlExpiry = it.attachmentUrlExpiry - attachmentId
                )
            }
        } else if (_uiState.value.attachmentUrls.containsKey(attachmentId)) {
            val exp = _uiState.value.attachmentUrlExpiry[attachmentId]
            val stillFresh = exp != null && exp > System.currentTimeMillis() + 30_000L
            val unknownExpiry = exp == null
            // Unknown expiry keeps legacy cache behaviour; known-past expiry re-fetches.
            if (stillFresh || unknownExpiry) return
        }
        if (!resolvingIds.add(attachmentId)) return
        viewModelScope.launch {
            try {
                if (auto) {
                    val allowed = runCatching {
                        container.chatPrefsStore.getCustomBool(
                            com.ollacore.app.data.local.ChatPrefsStore.SettingsKeys.AUTO_DOWNLOAD, true
                        )
                    }.getOrElse { true }
                    if (!allowed) return@launch
                }
                chatRepo.downloadAttachment(token, roomId, attachmentId)
                    .onSuccess { resp ->
                        val expMs = runCatching {
                            resp.expiresAt?.let { java.time.Instant.parse(it).toEpochMilli() }
                        }.getOrNull()
                        _uiState.update {
                            it.copy(
                                attachmentUrls = it.attachmentUrls + (attachmentId to resp.downloadUrl),
                                attachmentUrlExpiry = if (expMs != null) {
                                    it.attachmentUrlExpiry + (attachmentId to expMs)
                                } else it.attachmentUrlExpiry
                            )
                        }
                    }
            } finally {
                resolvingIds.remove(attachmentId)
            }
        }
    }

    fun sendMediaMessage(
        kind: String,
        caption: String,
        attachmentIds: List<String>,
        mime: String?,
        filename: String?,
        byteSize: Long?,
        durationMs: Long? = null,
        waveform: List<Int>? = null
    ) {
        val clientMessageId = "local-${UUID.randomUUID()}"
        val body = buildJsonObject {
            put("text", JsonPrimitive(caption))
            if (mime != null) put("mime", JsonPrimitive(mime))
            if (filename != null) put("filename", JsonPrimitive(filename))
            if (byteSize != null) put("byte_size", JsonPrimitive(byteSize))
            // Client-computed voice metadata (server passes body through; bubbles render it).
            if (durationMs != null) put("duration_ms", JsonPrimitive(durationMs))
            if (waveform != null) put("waveform", kotlinx.serialization.json.JsonArray(waveform.map { JsonPrimitive(it) }))
        }
        trackSend(clientMessageId, kind, body, attachmentIds, replyTo = null)
    }

    fun sendLocation(lat: Double, lng: Double, label: String = "") {
        // NOTE: backend/API check required - Ollacore docs list generic kinds; "location" may need backend support.
        val clientMessageId = "loc-${UUID.randomUUID()}"
        val body = buildJsonObject {
            put("lat", JsonPrimitive(lat))
            put("lng", JsonPrimitive(lng))
            put("text", JsonPrimitive(label.ifBlank { "📍 $lat, $lng" }))
        }
        trackSend(clientMessageId, com.ollacore.app.data.model.MessageKinds.LOCATION, body, emptyList(), replyTo = null)
    }

    /** Shared send path: optimistic Sending ○, payload kept for Retry ↻, request mapped for ack/error. */
    private fun trackSend(clientMessageId: String, kind: String, body: kotlinx.serialization.json.JsonObject, attachmentIds: List<String>, replyTo: String?) {
        setStatus(clientMessageId, MessageStatus.SENDING)
        pendingPayloads[clientMessageId] = PendingSend(kind, body, attachmentIds, replyTo)
        viewModelScope.launch {
            val requestId = chatWebSocket?.sendMessage(roomId, clientMessageId, body, kind = kind, replyTo = replyTo, attachmentIds = attachmentIds)
            if (requestId != null) requestToClient[requestId] = clientMessageId
            else setStatus(clientMessageId, MessageStatus.FAILED)
        }
    }

    /** Retry from a bubble: picks the tracked key (client echo id preferred, server id fallback). */
    fun retryForMessage(message: MessageResponse) {
        val key = listOfNotNull(message.clientMessageId, message.id)
            .firstOrNull { pendingPayloads.containsKey(it) } ?: return
        retryMessage(key)
    }

    /** Resend a Failed ⚠ message with the same client_message_id (server dedupes, no duplicate bubble). */
    fun retryMessage(clientMessageId: String) {
        val pending = pendingPayloads[clientMessageId] ?: return
        setStatus(clientMessageId, MessageStatus.SENDING)
        viewModelScope.launch {
            val requestId = chatWebSocket?.sendMessage(roomId, clientMessageId, pending.body, kind = pending.kind, replyTo = pending.replyTo, attachmentIds = pending.attachmentIds)
            if (requestId != null) requestToClient[requestId] = clientMessageId
            else setStatus(clientMessageId, MessageStatus.FAILED)
        }
    }

    /** Monotonic upgrade only: READ > DELIVERED > SENT > SENDING (FAILED only via error path). */
    private fun setStatus(key: String, status: MessageStatus) {
        var changed = false
        _uiState.update {
            val cur = it.messageStatus[key]
            val next = if (cur == null || status.rank > cur.rank || status == MessageStatus.FAILED || (cur == MessageStatus.FAILED && status == MessageStatus.SENDING)) status else cur
            if (next == cur) it else {
                changed = true
                it.copy(messageStatus = it.messageStatus + (key to next))
            }
        }
        // Persist receipt facts (SENT/DELIVERED/READ only; SENDING/FAILED are
        // transient) so ticks survive restarts without conflicting live state.
        if (changed && status.rank in 1..3) {
            viewModelScope.launch {
                runCatching { statusStore.upsert(key, status.rank) }
            }
        }
    }

    private fun upgradeStatus(key: String, status: MessageStatus) = setStatus(key, status)

    /** Resolve display status for a bubble: server id first, then client echo id, else history default. */
    fun statusFor(message: MessageResponse): MessageStatus? {
        _uiState.value.messageStatus[message.id]?.let { return it }
        message.clientMessageId?.let { _uiState.value.messageStatus[it]?.let { s -> return s } }
        // History messages predate live receipts: own messages read at least Sent ✓
        return if (message.senderId == currentUserId && currentUserId.isNotBlank()) MessageStatus.SENT else null
    }

    // Upload cancellation + failed-file retention for Retry (spec 12 preview actions).
    @Volatile private var uploadCancelled = false
    private var failedUpload: FailedUpload? = null

    private data class FailedUpload(
        val file: File,
        val mimeType: String,
        val kind: String,
        val caption: String,
        val durationMs: Long?,
        val waveform: List<Int>?
    )

    fun uploadAndSendFile(
        file: File,
        mimeType: String,
        kind: String,
        caption: String = "",
        durationMs: Long? = null,
        waveform: List<Int>? = null
    ) {
        val roomToken = _uiState.value.roomToken ?: run {
            _uiState.update { it.copy(uploadError = "Not connected") }
            return
        }
        // Client-side MIME + size validation (Ollacore API: MimeValidator, 10/10 coverage)
        val validation = com.ollacore.app.data.util.MimeValidator.validate(file, mimeType)
        if (!validation.isValid) {
            _uiState.update { it.copy(uploadError = validation.error) }
            return
        }
        uploadCancelled = false
        failedUpload = null
        android.util.Log.d("Upload", "launching upload coroutine for ${file.name}")
        viewModelScope.launch {
            android.util.Log.d("Upload", "coroutine entered for ${file.name}")
            _uiState.update { it.copy(isUploading = true, uploadingFilename = file.name, uploadProgress = 0f, uploadError = null) }
            // Hard ceiling: OkHttp timeouts don't cover DNS stalls, so no upload
            // may hang forever - it must end in sent/stuck UI or a clear error.
            // (Covers network legs plus the 60s attachment.ready wait below.)
            try {
                kotlinx.coroutines.withTimeout(240_000) {
                check(!uploadCancelled) { "cancelled" }
                val byteSize = file.length()
                val attachmentId: String = if (byteSize > SINGLE_PUT_THRESHOLD) {
                    // Multipart: init -> PUT parts -> complete-multipart
                    val init = withContext(Dispatchers.IO) {
                        chatRepo.initMultipartUpload(roomToken, roomId, file.name, mimeType, byteSize).getOrThrow()
                    }
                    check(!uploadCancelled) { "cancelled" }
                    _uiState.update { it.copy(uploadProgress = 0.2f) }
                    val parts = withContext(Dispatchers.IO) {
                        attachmentUploader.uploadMultipart(init.partUrls, file, init.partSize) { uploaded, total ->
                            _uiState.update { s -> s.copy(uploadProgress = 0.2f + 0.6f * uploaded.toFloat() / total) }
                        }
                    }
                    check(!uploadCancelled) { "cancelled" }
                    withContext(Dispatchers.IO) {
                        chatRepo.completeMultipartUpload(roomToken, roomId, init.attachmentId, parts.map { (num, etag) ->
                            com.ollacore.app.data.model.MultipartPart(num, etag)
                        }).getOrThrow()
                    }
                    if (!awaitAttachmentReady(init.attachmentId)) {
                        throw Exception("Server could not prepare the attachment - please retry")
                    }
                    init.attachmentId
                } else {
                    // Single PUT: init -> PUT -> complete
                    val init = withContext(Dispatchers.IO) {
                        chatRepo.initAttachment(roomToken, roomId, file.name, mimeType, byteSize).getOrThrow()
                    }
                    android.util.Log.d("Upload", "init done id=${init.attachmentId}")
                    check(!uploadCancelled) { "cancelled" }
                    _uiState.update { it.copy(uploadProgress = 0.4f) }
                    withContext(Dispatchers.IO) {
                        attachmentUploader.uploadToPresignedUrl(init.uploadUrl, file, mimeType)
                    }
                    android.util.Log.d("Upload", "PUT done id=${init.attachmentId}")
                    check(!uploadCancelled) { "cancelled" }
                    _uiState.update { it.copy(uploadProgress = 0.7f) }
                    withContext(Dispatchers.IO) {
                        chatRepo.completeAttachment(roomToken, roomId, init.attachmentId).getOrThrow()
                    }
                    android.util.Log.d("Upload", "complete done id=${init.attachmentId}")
                    // Wait for the server's async validation BEFORE referencing the
                    // attachment in a message; otherwise the send is rejected and
                    // the failure is invisible (no bubble exists yet to mark Failed).
                    if (!awaitAttachmentReady(init.attachmentId)) {
                        throw Exception("Server could not prepare the attachment - please retry")
                    }
                    android.util.Log.d("Upload", "attachment ready id=${init.attachmentId}")
                    init.attachmentId
                }
                _uiState.update { it.copy(uploadProgress = 0.9f) }
                sendMediaMessage(kind, caption.ifBlank { file.name }, listOf(attachmentId), mimeType, file.name, byteSize, durationMs, waveform)
                // attachment.ready WS will arrive -> resolveAttachmentUrl prefetches download URL
                _uiState.update { it.copy(isUploading = false, uploadProgress = 1f, uploadingFilename = null) }
                }
            } catch (e: Exception) {
                val wasCancel = uploadCancelled || e.message == "cancelled"
                if (!wasCancel && file.exists()) {
                    failedUpload = FailedUpload(file, mimeType, kind, caption, durationMs, waveform)
                }
                _uiState.update {
                    it.copy(
                        isUploading = false,
                        uploadError = if (wasCancel) null else "Upload failed: ${e.message}",
                        uploadingFilename = null,
                        uploadProgress = 0f
                    )
                }
            }
        }
    }

    fun cancelUpload() {
        uploadCancelled = true
        _uiState.update { it.copy(isUploading = false, uploadProgress = 0f, uploadingFilename = null, uploadError = null) }
    }

    fun retryUpload() {
        val failed = failedUpload ?: return
        failedUpload = null
        uploadAndSendFile(failed.file, failed.mimeType, failed.kind, failed.caption, failed.durationMs, failed.waveform)
    }

    fun hasFailedUpload(): Boolean = failedUpload != null

    fun clearUploadError() {
        _uiState.update { it.copy(uploadError = null, isUploading = false, uploadProgress = 0f, uploadingFilename = null) }
    }

    // ── Voice recorder (spec 13): tap mic -> live bar -> send as audio file ──
    // No voice_note backend exists, and message kind "audio" is rejected with
    // 400 "unsupported message kind audio" (proven 2026-09-24), so recordings
    // travel as kind=file with audio mime + duration_ms + waveform body fields
    // (server passes body through; bubbles key off the audio/* MIME).

    private var recorder: android.media.MediaRecorder? = null
    private var recordFile: File? = null
    private var recordJob: kotlinx.coroutines.Job? = null

    /**
     * Starts a voice recording (AAC in M4A - MediaRecorder has no MP3
     * encoder, so MP3 was never an option; the old ".mp3" label was UI text).
     * Order source -> format -> encoder -> rate -> file -> prepare -> start
     * is mandatory. A fresh instance is built per attempt; released instances
     * are never reused.
     */
    fun startRecording() {
        if (_uiState.value.voiceDraft.isRecording) return
        _uiState.update { it.copy(recordError = null) }
        val app = getApplication<android.app.Application>()
        // Backstop: the UI requests this first, but never assume the grant.
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                app, android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            _uiState.update {
                it.copy(recordError = "Microphone permission is needed to record voice messages.")
            }
            return
        }
        try {
            val file = File(app.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
            val rec = if (android.os.Build.VERSION.SDK_INT >= 31) {
                android.media.MediaRecorder(app)
            } else {
                @Suppress("DEPRECATION")
                android.media.MediaRecorder()
            }.apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                // Explicit voice-safe params: mono 44.1kHz AAC-LC. Defaults
                // vary by device (stereo/unset rate) and the server-side audio
                // probe rejects some of them (attachment.failed after upload).
                setAudioChannels(1)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = rec
            recordFile = file
            val startedAt = System.currentTimeMillis()
            _uiState.update { it.copy(voiceDraft = VoiceDraft(isRecording = true)) }
            recordJob = viewModelScope.launch {
                val amps = mutableListOf<Int>()
                while (_uiState.value.voiceDraft.isRecording) {
                    kotlinx.coroutines.delay(150)
                    val peak = runCatching { rec.maxAmplitude }.getOrElse { 0 }
                    amps.add((peak / 270).coerceIn(1, 100))
                    if (amps.size > 60) amps.removeAt(0)
                    _uiState.update {
                        it.copy(
                            voiceDraft = VoiceDraft(
                                isRecording = true,
                                elapsedMs = System.currentTimeMillis() - startedAt,
                                amplitudes = amps.toList()
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            cleanupRecorder(deleteFile = true)
            val msg = when {
                // Mic held by a call/another app, or device has no mic path.
                e is RuntimeException -> "Microphone is busy or unavailable. Close other apps using it and try again."
                else -> "Recording failed: ${e.message ?: e.javaClass.simpleName}"
            }
            _uiState.update { it.copy(recordError = msg) }
        }
    }

    /** Stops + releases the recorder exactly once; never throws. */
    private fun cleanupRecorder(deleteFile: Boolean) {
        recordJob?.cancel()
        recordJob = null
        // stop() throws if nothing was captured (very short tap) - then the
        // file is unusable, so it is dropped in sendRecording's checks.
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        if (deleteFile) {
            recordFile?.delete()
            recordFile = null
        }
    }

    fun cancelRecording() {
        cleanupRecorder(deleteFile = true)
        _uiState.update { it.copy(voiceDraft = VoiceDraft(), recordError = null) }
    }

    fun sendRecording() {
        val draft = _uiState.value.voiceDraft
        android.util.Log.d("Record", "send tap: recording=${draft.isRecording} elapsed=${draft.elapsedMs} amps=${draft.amplitudes.size}")
        if (!draft.isRecording) return
        cleanupRecorder(deleteFile = false)
        val file = recordFile
        recordFile = null
        val duration = draft.elapsedMs
        val wave = draft.amplitudes.takeLast(40)
        _uiState.update { it.copy(voiceDraft = VoiceDraft()) }
        android.util.Log.d(
            "Record",
            "send checks: exists=${file?.exists()} size=${file?.length()} duration=$duration"
        )
        // Server accepts only the M4A major brand for audio (mp42/isom fail
        // its probe even though the bytes are valid AAC). Stamp it truthfully.
        if (file != null) {
            runCatching { com.ollacore.app.data.util.MimeValidator.ensureM4aBrand(file) }
        }
        // Verified sendable: real file, non-zero bytes, audible length.
        // kind=file (NOT audio): the server rejects message kind "audio" with
        // 400 "unsupported message kind audio" (proven via API). The bubble
        // still renders the voice player: rendering keys off the audio/* MIME.
        if (file != null && file.exists() && file.length() > 1024 && duration > 500) {
            uploadAndSendFile(file, "audio/mp4", com.ollacore.app.data.model.MessageKinds.FILE, "", duration, wave)
        } else {
            file?.delete()
            if (file != null) {
                _uiState.update {
                    it.copy(recordError = "That recording is too short or empty - hold to record, then send.")
                }
            }
        }
    }

    fun clearRecordError() {
        _uiState.update { it.copy(recordError = null) }
    }

    /** Leaving the screen with a live recording: stop + release, drop the temp file. */
    private fun releaseRecorderOnDestroy() {
        cleanupRecorder(deleteFile = true)
    }

    companion object {
        private const val SINGLE_PUT_THRESHOLD = 10 * 1024 * 1024L // 10MB - matches AttachmentViewModel
    }

    /** User accepted the ringing call (navigation to the call screen is handled by the UI). */
    fun acceptIncomingCall() {
        _uiState.update { it.copy(incomingCall = null) }
    }

    /** User declined: dismiss locally (caller stops on timeout/leave; no decline frame in Ollacore WS). */
    fun declineIncomingCall() {
        _uiState.update { it.copy(incomingCall = null) }
    }

    fun sendTypingStarted() {
        chatWebSocket?.typingStarted(roomId)
    }

    fun sendTypingStopped() {
        chatWebSocket?.typingStopped(roomId)
    }

    fun loadMore(beforeSeq: Int) {
        if (_uiState.value.loadingHistory) return
        val roomToken = _uiState.value.roomToken ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(loadingHistory = true) }
            chatRepo.listMessages(roomToken, roomId, beforeSeq = beforeSeq)
                .onSuccess { response ->
                    _uiState.update {
                        val seeded = it.messageStatus.toMutableMap()
                        response.messages.forEach { m ->
                            if (m.senderId == currentUserId && currentUserId.isNotBlank() && !seeded.containsKey(m.id)) {
                                seeded[m.id] = MessageStatus.SENT
                            }
                        }
                        // Dedupe by id: live echoes may already hold items the page returns.
                        val known = it.messages.map { m -> m.id }.toSet()
                        val fresh = response.messages.filter { m -> m.id !in known }.sortedBy { m -> m.eventSeq }
                        it.copy(
                            messages = applyLocalViewFilter(fresh + it.messages),
                            messageStatus = seeded,
                            hasMoreHistory = response.hasMore,
                            loadingHistory = false
                        )
                    }
                }
                .onFailure {
                    _uiState.update { it.copy(loadingHistory = false) }
                }
        }
    }

    fun onMemberRemoved(principalId: String, reason: String = "removed") {
        val removalReason = when (reason) {
            "left" -> RemovalReason.LEFT
            "banned" -> RemovalReason.BANNED
            "device_revoked" -> RemovalReason.DEVICE_REVOKED
            else -> RemovalReason.REMOVED
        }
        
        forwardSecrecyManager.onMemberRemoved(principalId, removalReason)
        
        if (_uiState.value.isEncrypted) {
            _uiState.update { it.copy(needsKeyRotation = true) }
            
            // CRITICAL: Rotate session immediately for forward secrecy
            // This ensures removed member cannot decrypt future messages
            rotateSession()
        }
    }

    fun onMemberAdded(principalId: String) {
        forwardSecrecyManager.onMemberAdded(principalId)
        if (_uiState.value.isEncrypted) {
            _uiState.update { it.copy(needsKeyRotation = true) }
            rotateSession()
        }
    }

    /**
     * Rotate the encryption session.
     * This creates a new epoch with new keys, excluding revoked members.
     * CRITICAL for forward secrecy when members are removed.
     */
    private fun rotateSession() {
        viewModelScope.launch {
            val roomToken = _uiState.value.roomToken ?: return@launch
            val deviceId = "android-${UUID.randomUUID()}"
            
            // First, rotate the local session state
            val rotationResult = forwardSecrecyManager.rotateSession()
            
            if (!rotationResult.success) {
                _uiState.update { it.copy(error = "Session rotation failed") }
                return@launch
            }
            
            // Then, upload new keys to server
            e2eeManager.rotateSession(roomToken, roomId, deviceId)
                .onSuccess {
                    // Mark rotation complete
                    _uiState.update { 
                        it.copy(
                            needsKeyRotation = false,
                            currentEpoch = rotationResult.newEpoch
                        )
                    }
                    
                    // Log the rotation for audit
                    android.util.Log.i("ForwardSecrecy", 
                        "Session rotated: epoch=${rotationResult.newEpoch}, " +
                        "active=${rotationResult.activeMembers.size}, " +
                        "revoked=${rotationResult.revokedMembers.size}")
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = "Key rotation failed: ${e.message}") }
                }
        }
    }

    /**
     * Check if a specific member can decrypt messages at the current epoch.
     * Useful for testing forward secrecy.
     */
    fun canMemberDecrypt(memberId: String, epoch: Int = -1): Boolean {
        val checkEpoch = if (epoch == -1) forwardSecrecyManager.getCurrentEpoch() else epoch
        return forwardSecrecyManager.canDecrypt(memberId, checkEpoch)
    }

    /**
     * Get current forward secrecy state for debugging/testing.
     */
    fun getForwardSecrecyInfo(): ForwardSecrecyInfo {
        val state = forwardSecrecyManager.sessionState.value
        return ForwardSecrecyInfo(
            currentEpoch = state.currentEpoch,
            activeMembers = state.currentMembers,
            revokedMembers = state.revokedMembers,
            rotationCount = state.rotationCount,
            pendingRotation = state.pendingRotation
        )
    }

    override fun onCleared() {
        releaseRecorderOnDestroy()
        super.onCleared()
        chatWebSocket?.disconnect()
    }
}
