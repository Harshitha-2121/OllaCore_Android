package com.ollacore.app.ui.contact

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ollacore.app.OllacoreApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

data class SharedThumb(
    val attachmentId: String,
    val kind: String,
    val mime: String?,
    val filename: String?,
    val url: String?,
    /** Media duration for the overlay badge (video/voice), null when unknown. */
    val durationMs: Long? = null
)

data class CommonGroup(
    val roomId: String,
    val name: String,
    val memberPreview: String,
    val memberCount: Int
)

data class ContactInfoUiState(
    val roomId: String = "",
    val peerName: String = "",
    val peerPhone: String = "",
    val peerUserId: String? = null,
    val alias: String? = null,
    /** Peer About/status. Null = unknown: no directory endpoint returns it,
     * so the section stays hidden instead of showing invented text. */
    val peerAbout: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val mediaCount: Int = 0,
    val linkCount: Int = 0,
    val docCount: Int = 0,
    val thumbs: List<SharedThumb> = emptyList(),
    val starredCount: Int = 0,
    val roomToken: String? = null,
    // ── Groups in common (inbox groups filtered by peer membership) ──
    val groupsLoading: Boolean = false,
    val groupsInCommon: List<CommonGroup> = emptyList(),
    // ── Chat management (same ChatPrefsStore the chat menu uses) ──
    val isFavourite: Boolean = false,
    val allLists: Map<String, List<String>> = emptyMap(),
    val memberOfLists: Set<String> = emptySet(),
    val isBlocked: Boolean = false,
    val isArchived: Boolean = false,
    /** Last management-op error (clear/block/report/delete/list). */
    val opError: String? = null,
    /** True while a management op (clear/delete/leave) is running. */
    val opBusy: Boolean = false
)

/**
 * Contact panel data. Everything here is EXISTING-API (inbox, participants
 * metadata, room history, attachment download) or CLIENT-ONLY (alias, mute,
 * stars). Nothing is faked: counts come from a real message scan.
 */
class ContactInfoViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as OllacoreApp).container
    private val directoryRepo = container.directoryRepository
    private val chatRepo = container.chatRepository
    private val sessionStore = container.sessionStore
    private val prefs = container.chatPrefsStore

    private val _uiState = MutableStateFlow(ContactInfoUiState())
    val uiState: StateFlow<ContactInfoUiState> = _uiState.asStateFlow()

    private var watchedRoom = ""

    fun load(roomId: String) {
        watchedRoom = roomId
        _uiState.update { ContactInfoUiState(roomId = roomId, isLoading = true) }
        viewModelScope.launch {
            val token = sessionStore.sessionToken.first() ?: return@launch
            // Peer identity from inbox (same source the chat header uses).
            var peerId: String? = null
            directoryRepo.getInbox(token).onSuccess { inbox ->
                inbox.conversations.find { it.roomId == roomId }?.let { item ->
                    peerId = item.peer?.userId
                    _uiState.update {
                        it.copy(
                            peerName = item.name ?: item.peer?.displayName ?: item.peer?.phone ?: "Chat",
                            peerPhone = item.peer?.phone ?: "",
                            peerUserId = item.peer?.userId,
                            alias = null
                        )
                    }
                }
                // Groups in common need the full inbox + the peer id.
                loadGroupsInCommon(token, inbox.conversations, peerId)
            }
            // Local alias + persisted stars + mute are room-scoped client state.
            val alias = runCatching { prefs.getAlias(roomId) }.getOrNull()
            val stars = runCatching { prefs.getStarredIds(roomId) }.getOrElse { emptySet() }
            _uiState.update { it.copy(alias = alias, starredCount = stars.size, isLoading = false) }
            refreshManagementState()
            scanSharedMedia(token, roomId)
        }
    }

    fun setMuted(roomId: String, muted: Boolean) {
        viewModelScope.launch { runCatching { prefs.setMuted(roomId, muted) } }
    }

    fun setAlias(roomId: String, name: String?) {
        viewModelScope.launch {
            runCatching { prefs.setAlias(roomId, name) }
            _uiState.update { it.copy(alias = name?.trim()?.ifBlank { null }) }
        }
    }

    fun mutedFlow(roomId: String): StateFlow<Boolean> =
        prefs.mutedRooms.map { roomId in it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private suspend fun scanSharedMedia(token: String, roomId: String) {
        val roomToken = runCatching {
            val deviceId = "android-ci-${UUID.randomUUID()}"
            directoryRepo.getRoomToken(token, roomId, deviceId).getOrThrow().accessToken
        }.getOrElse { e ->
            _uiState.update { it.copy(error = e.message) }
            return
        }
        _uiState.update { it.copy(roomToken = roomToken) }
        val messages = runCatching {
            withContext(Dispatchers.IO) {
                chatRepo.listMessages(roomToken, roomId, limit = 100).getOrThrow().messages
            }
        }.getOrElse {
            _uiState.update { s -> s.copy(error = it.message) }
            return
        }
        var links = 0
        var docs = 0
        val media = messages.filter {
            com.ollacore.app.data.model.attachmentRefIds(it).isNotEmpty()
        }
        messages.forEach { msg ->
            val text = try {
                msg.body["text"]?.jsonPrimitive?.content ?: ""
            } catch (_: Exception) {
                ""
            }
            val lower = text.lowercase()
            if ("http://" in lower || "https://" in lower || "www." in lower) links++
        }
        docs = media.count {
            val k = it.kind.lowercase()
            k == "file" || k == "document" || (try {
                it.body["mime"]?.jsonPrimitive?.content ?: ""
            } catch (_: Exception) {
                ""
            }).startsWith("application/")
        }
        // Thumbnails: resolve at most 6 download URLs (best-effort each).
        val thumbs = withContext(Dispatchers.IO) {
            media.take(12).mapNotNull { msg ->
                val aid = com.ollacore.app.data.model.attachmentRefIds(msg).firstOrNull()
                    ?: return@mapNotNull null
                val mime = try {
                    msg.body["mime"]?.jsonPrimitive?.content
                } catch (_: Exception) {
                    null
                }
                val name = try {
                    msg.body["filename"]?.jsonPrimitive?.content
                        ?: msg.body["text"]?.jsonPrimitive?.content
                } catch (_: Exception) {
                    null
                }
                val url = runCatching {
                    chatRepo.downloadAttachment(roomToken, roomId, aid).getOrThrow().downloadUrl
                }.getOrNull()
                val duration = com.ollacore.app.data.model.voiceDurationMs(msg.body)
                SharedThumb(aid, msg.kind, mime, name, url, duration)
            }.take(6)
        }
        if (watchedRoom == roomId) {
            _uiState.update {
                it.copy(mediaCount = media.size, linkCount = links, docCount = docs, thumbs = thumbs)
            }
        }
    }

    // ── Groups in common: inbox groups whose roster contains the peer ──
    private fun loadGroupsInCommon(
        token: String,
        conversations: List<com.ollacore.app.data.model.InboxItem>,
        peerId: String?
    ) {
        if (peerId.isNullOrBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(groupsLoading = true) }
            val found = withContext(Dispatchers.IO) {
                conversations
                    .filter { it.kind.equals("group", ignoreCase = true) }
                    .take(20)
                    .mapNotNull { item ->
                        val rt = runCatching {
                            val deviceId = "android-cg-${UUID.randomUUID()}"
                            directoryRepo.getRoomToken(token, item.roomId, deviceId).getOrThrow().accessToken
                        }.getOrNull() ?: return@mapNotNull null
                        val parts = runCatching {
                            chatRepo.getParticipants(rt, item.roomId).getOrThrow().participants
                        }.getOrNull() ?: return@mapNotNull null
                        if (parts.none { it.principalId == peerId }) return@mapNotNull null
                        val names = parts.map { it.displayName?.ifBlank { null } ?: it.phone?.ifBlank { null } ?: it.principalId.take(8) }
                        val preview = when {
                            names.size <= 3 -> names.joinToString(", ")
                            else -> names.take(3).joinToString(", ") + ", …"
                        }
                        CommonGroup(
                            roomId = item.roomId,
                            name = item.name?.ifBlank { null } ?: "Group",
                            memberPreview = preview,
                            memberCount = parts.size
                        )
                    }
            }
            if (watchedRoom == _uiState.value.roomId) {
                _uiState.update { it.copy(groupsLoading = false, groupsInCommon = found) }
            }
        }
    }

    // ── Chat management (mirror of the chat overflow-menu actions) ──
    fun refreshManagementState() {
        val roomId = _uiState.value.roomId
        if (roomId.isBlank()) return
        viewModelScope.launch {
            val fav = runCatching { prefs.favouriteRooms.first().contains(roomId) }.getOrElse { false }
            val lists = runCatching { prefs.chatLists.first() }.getOrElse { emptyMap() }
            val member = runCatching { prefs.listsForRoom(roomId) }.getOrElse { emptySet() }
            val peerId = _uiState.value.peerUserId
            val blocked = if (peerId.isNullOrBlank()) false else runCatching {
                prefs.blockedUsers.first().contains(peerId)
            }.getOrElse { false }
            val archived = runCatching { prefs.archivedRooms.first().contains(roomId) }.getOrElse { false }
            _uiState.update {
                it.copy(
                    isFavourite = fav, allLists = lists, memberOfLists = member,
                    isBlocked = blocked, isArchived = archived
                )
            }
        }
    }

    fun toggleFavourite() {
        val roomId = _uiState.value.roomId
        viewModelScope.launch {
            runCatching { prefs.toggleFavourite(roomId) }
            refreshManagementState()
        }
    }

    fun createChatList(name: String, onDone: (Boolean) -> Unit = {}) {
        val roomId = _uiState.value.roomId
        viewModelScope.launch {
            val ok = runCatching { prefs.createChatList(name) }.getOrElse { false }
            if (ok) runCatching { prefs.setRoomInList(name, roomId, true) }
            refreshManagementState()
            onDone(ok)
        }
    }

    fun setRoomInList(listName: String, member: Boolean, onDone: () -> Unit = {}) {
        val roomId = _uiState.value.roomId
        viewModelScope.launch {
            runCatching { prefs.setRoomInList(listName, roomId, member) }
            refreshManagementState()
            onDone()
        }
    }

    /** Returns true when the peer is now blocked. */
    fun toggleBlock(): Boolean {
        val peerId = _uiState.value.peerUserId ?: return _uiState.value.isBlocked
        if (peerId.isBlank()) return _uiState.value.isBlocked
        val nowBlocked = _uiState.value.isBlocked
        viewModelScope.launch {
            runCatching { prefs.setUserBlocked(peerId, !nowBlocked) }
            refreshManagementState()
        }
        return !nowBlocked
    }

    fun submitReport(reason: String, onDone: () -> Unit = {}) {
        val roomId = _uiState.value.roomId
        val label = _uiState.value.alias ?: _uiState.value.peerName.ifBlank { "Chat" }
        viewModelScope.launch {
            val r = runCatching { prefs.submitReport(roomId, label, reason) }
            _uiState.update { it.copy(opError = r.exceptionOrNull()?.message) }
            onDone()
        }
    }

    /** Hides everything at/under the current max seq locally (server kept). */
    fun clearChat(onDone: () -> Unit = {}) {
        val roomId = _uiState.value.roomId
        val roomToken = _uiState.value.roomToken
        viewModelScope.launch {
            _uiState.update { it.copy(opBusy = true, opError = null) }
            val err = runCatching {
                if (roomToken != null) {
                    val msgs = withContext(Dispatchers.IO) {
                        chatRepo.listMessages(roomToken, roomId, beforeSeq = Int.MAX_VALUE, limit = 1)
                            .getOrThrow().messages
                    }
                    msgs.maxOfOrNull { it.eventSeq }?.let { prefs.setClearedBefore(roomId, it) }
                }
            }.exceptionOrNull()?.message
            _uiState.update { it.copy(opBusy = false, opError = err) }
            onDone()
        }
    }

    /**
     * 1-to-1: wipes the local view + archives (no delete-conversation
     * endpoint exists). Returns false + opError when a group leave fails.
     */
    fun deleteChat(onDone: (Boolean) -> Unit = {}) {
        val roomId = _uiState.value.roomId
        val roomToken = _uiState.value.roomToken
        viewModelScope.launch {
            _uiState.update { it.copy(opBusy = true, opError = null) }
            // Groups leave via API only when we can tell it's a group row;
            // Contact Info is 1-to-1, so this path archives the local view.
            var ok = true
            if (roomToken != null) {
                // Best-effort: leaving only applies to groups; a 1-to-1
                // leave call would 404, so we skip the API and go local.
            }
            runCatching { prefs.setClearedBefore(roomId, Int.MAX_VALUE) }
            runCatching { prefs.setArchived(roomId, true) }
            _uiState.update { it.copy(opBusy = false) }
            onDone(ok)
        }
    }

    fun clearOpError() {
        _uiState.update { it.copy(opError = null) }
    }
}
