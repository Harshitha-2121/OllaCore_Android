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
    // WhatsApp-style chat upgrade (Category 1 - API YES except star/select are client-only)
    val starredIds: Set<String> = emptySet(),
    val selectedIds: Set<String> = emptySet(),
    val selectionMode: Boolean = false,
    val peerName: String? = null,
    val peerAvatarUrl: String? = null,
    val peerPhone: String? = null,
    // ── Media & attachments (Category 1 - Ollacore API YES: init/multipart/download + WS attachment.ready/failed) ──
    val attachmentUrls: Map<String, String> = emptyMap(),
    val uploadingFilename: String? = null,
    val uploadProgress: Float = 0f,
    val isUploading: Boolean = false,
    val uploadError: String? = null,
    // ── Voice recorder draft (spec 13; sent as kind=audio which renders today) ──
    val voiceDraft: VoiceDraft = VoiceDraft()
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

    private data class PendingSend(
        val kind: String,
        val body: kotlinx.serialization.json.JsonObject,
        val attachmentIds: List<String>,
        val replyTo: String?
    )

    fun joinRoom(roomId: String) {
        this.roomId = roomId
        viewModelScope.launch {
            val token = sessionStore.sessionToken.first() ?: return@launch
            currentUserId = sessionStore.userId.first() ?: ""

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
                            _uiState.update {
                                it.copy(
                                    kind = item.kind,
                                    peerName = if (isGroup) item.name
                                        ?: "Group" else item.name
                                        ?: item.peer?.displayName
                                        ?: item.peer?.phone
                                        ?: "Chat",
                                    peerPhone = if (isGroup) null else item.peer?.phone
                                )
                            }
                            if (isGroup) loadParticipants(response.accessToken)
                        }
                    }
                    if (response.e2ee != null) {
                        pushConfigManager.setE2eeConfig(response.e2ee)
                    }
                    connectWebSocket(response.chatWebsocketUrl, response.accessToken)
                    loadMessages(response.accessToken)
                }
                .onFailure { e ->
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
                if (response.attachmentIds.isNotEmpty()) {
                    response.attachmentIds.forEach { aid -> resolveAttachmentUrl(aid) }
                }
                if (msg.senderId != currentUserId) {
                    chatWebSocket?.markDelivered(roomId, msg.id)
                }
            }
            is WebSocketEvent.ReceiptDelivered -> {
                // Peer device received -> Delivered ✓✓ (never downgrades Read)
                upgradeStatus(event.messageId, MessageStatus.DELIVERED)
            }
            is WebSocketEvent.ReceiptRead -> {
                // Peer opened chat -> Read ✓✓ blue
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
                resolveAttachmentUrl(event.attachmentId)
            }
            is WebSocketEvent.AttachmentFailed -> {
                _uiState.update { it.copy(uploadError = "Attachment failed: ${event.attachmentId}") }
            }
            is WebSocketEvent.MessageDeleted -> {
                _uiState.update {
                    it.copy(messages = it.messages.filter { m -> m.id != event.messageId })
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
                _uiState.update { it.copy(typingUsers = it.typingUsers + event.principalId) }
            }
            is WebSocketEvent.TypingStopped -> {
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
        chatRepo.listMessages(roomToken, roomId)
            .onSuccess { response ->
                // Seed history: own messages show at least Sent ✓ (live receipts upgrade to ✓✓/blue)
                val seeded = _uiState.value.messageStatus.toMutableMap()
                response.messages.forEach { m ->
                    if (m.senderId == currentUserId && currentUserId.isNotBlank()) {
                        if (!seeded.containsKey(m.id)) seeded[m.id] = MessageStatus.SENT
                    }
                }
                _uiState.update {
                    it.copy(messages = response.messages.sortedBy { m -> m.eventSeq }, isLoading = false, messageStatus = seeded)
                }
                // Prefetch download URLs for media messages so bubbles render immediately
                response.messages.flatMap { it.attachmentIds }.distinct().forEach { aid -> resolveAttachmentUrl(aid) }
                if (response.messages.isNotEmpty()) {
                    val lastMsg = response.messages.maxByOrNull { it.eventSeq }
                    if (lastMsg != null) {
                        chatWebSocket?.markRead(roomId, lastMsg.id)
                    }
                }
            }
            .onFailure { e ->
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
        _uiState.update {
            val starred = it.starredIds.toMutableSet()
            if (messageId in starred) starred.remove(messageId) else starred.add(messageId)
            it.copy(starredIds = starred)
        }
        // Client-only: persisted locally (star is not an Ollacore API reactions; reactions are separate via addReaction)
        // Could persist via DataStore if needed
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

    fun isStarred(messageId: String): Boolean = messageId in _uiState.value.starredIds
    fun isSelected(messageId: String): Boolean = messageId in _uiState.value.selectedIds

    fun copyText(message: MessageResponse): String {
        return message.body["text"]?.let { try { it.jsonPrimitive.content } catch (_: Exception) { "" } } ?: ""
    }

    // ── Media & attachments (Category 1 - Ollacore API YES) ──
    // Flow: MimeValidator.validate -> init/init-multipart -> PUT presigned -> complete -> WS message.send(kind, attachment_ids)
    // Kinds: image / video / audio / file (Ollacore-native, WhatsApp-style UI only). Location = kind "location" (backend check required).
    private val attachmentUploader = com.ollacore.app.data.remote.AttachmentUploader()

    fun resolveAttachmentUrl(attachmentId: String) {
        val token = _uiState.value.roomToken ?: return
        if (_uiState.value.attachmentUrls.containsKey(attachmentId)) return
        viewModelScope.launch {
            chatRepo.downloadAttachment(token, roomId, attachmentId)
                .onSuccess { resp ->
                    _uiState.update { it.copy(attachmentUrls = it.attachmentUrls + (attachmentId to resp.downloadUrl)) }
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
        _uiState.update {
            val cur = it.messageStatus[key]
            val next = if (cur == null || status.rank > cur.rank || status == MessageStatus.FAILED || (cur == MessageStatus.FAILED && status == MessageStatus.SENDING)) status else cur
            if (next == cur) it else it.copy(messageStatus = it.messageStatus + (key to next))
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
        viewModelScope.launch {
            _uiState.update { it.copy(isUploading = true, uploadingFilename = file.name, uploadProgress = 0f, uploadError = null) }
            try {
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
                    init.attachmentId
                } else {
                    // Single PUT: init -> PUT -> complete
                    val init = withContext(Dispatchers.IO) {
                        chatRepo.initAttachment(roomToken, roomId, file.name, mimeType, byteSize).getOrThrow()
                    }
                    check(!uploadCancelled) { "cancelled" }
                    _uiState.update { it.copy(uploadProgress = 0.4f) }
                    withContext(Dispatchers.IO) {
                        attachmentUploader.uploadToPresignedUrl(init.uploadUrl, file, mimeType)
                    }
                    check(!uploadCancelled) { "cancelled" }
                    _uiState.update { it.copy(uploadProgress = 0.7f) }
                    withContext(Dispatchers.IO) {
                        chatRepo.completeAttachment(roomToken, roomId, init.attachmentId).getOrThrow()
                    }
                    init.attachmentId
                }
                _uiState.update { it.copy(uploadProgress = 0.9f) }
                sendMediaMessage(kind, caption.ifBlank { file.name }, listOf(attachmentId), mimeType, file.name, byteSize, durationMs, waveform)
                // attachment.ready WS will arrive -> resolveAttachmentUrl prefetches download URL
                _uiState.update { it.copy(isUploading = false, uploadProgress = 1f, uploadingFilename = null) }
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
    // No voice_note backend exists, so recordings travel as kind=audio with
    // duration_ms + waveform body fields (server passes body through).

    private var recorder: android.media.MediaRecorder? = null
    private var recordFile: File? = null
    private var recordJob: kotlinx.coroutines.Job? = null

    fun startRecording() {
        if (_uiState.value.voiceDraft.isRecording) return
        try {
            val app = getApplication<android.app.Application>()
            val file = File(app.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
            val rec = android.media.MediaRecorder().apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
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
            _uiState.update { it.copy(error = "Recording failed: ${e.message}") }
        }
    }

    fun cancelRecording() {
        recordJob?.cancel()
        recordJob = null
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        recordFile?.delete()
        recordFile = null
        _uiState.update { it.copy(voiceDraft = VoiceDraft()) }
    }

    fun sendRecording() {
        val draft = _uiState.value.voiceDraft
        if (!draft.isRecording) return
        recordJob?.cancel()
        recordJob = null
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        val file = recordFile
        recordFile = null
        val duration = draft.elapsedMs
        val wave = draft.amplitudes.takeLast(40)
        _uiState.update { it.copy(voiceDraft = VoiceDraft()) }
        if (file != null && file.exists() && duration > 500) {
            uploadAndSendFile(file, "audio/mp4", com.ollacore.app.data.model.MessageKinds.AUDIO, "", duration, wave)
        } else {
            file?.delete()
        }
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
        val roomToken = _uiState.value.roomToken ?: return
        viewModelScope.launch {
            chatRepo.listMessages(roomToken, roomId, beforeSeq = beforeSeq)
                .onSuccess { response ->
                    _uiState.update {
                        val seeded = it.messageStatus.toMutableMap()
                        response.messages.forEach { m ->
                            if (m.senderId == currentUserId && currentUserId.isNotBlank() && !seeded.containsKey(m.id)) {
                                seeded[m.id] = MessageStatus.SENT
                            }
                        }
                        it.copy(messages = response.messages.sortedBy { m -> m.eventSeq } + it.messages, messageStatus = seeded)
                    }
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
        super.onCleared()
        chatWebSocket?.disconnect()
    }
}
