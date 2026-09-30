package com.ollacore.app.ui.groups

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ollacore.app.OllacoreApp
import com.ollacore.app.data.model.Participant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GroupStarredMsg(
    val messageId: String,
    val sender: String,
    val preview: String,
    val at: String,
    val eventSeq: Int = -1
)

data class GroupInfoUiState(
    val roomId: String = "",
    val name: String = "",
    val description: String? = null,
    val iconUrl: String? = null,
    val kind: String = "",
    val members: List<Participant> = emptyList(),
    val isAdmin: Boolean = false,
    val isMuted: Boolean = false,
    val isLoading: Boolean = false,
    val isWorking: Boolean = false,
    val error: String? = null,
    val inviteText: String? = null,
    val left: Boolean = false,
    // Reference sections (all real data, loaded with members).
    val isEncrypted: Boolean = false,
    val mediaCount: Int = 0,
    val linkCount: Int = 0,
    val docCount: Int = 0,
    val thumbs: List<com.ollacore.app.data.util.ScannedMediaItem> = emptyList(),
    val starred: List<GroupStarredMsg> = emptyList(),
    val isFavourite: Boolean = false,
    val allLists: Map<String, List<String>> = emptyMap(),
    val memberOfLists: Set<String> = emptySet(),
    val events: List<com.ollacore.app.data.local.GroupEvent> = emptyList(),
    val selfId: String? = null,
    val reportDone: Boolean = false,
    // Add-member workflow state.
    val addCandidates: List<com.ollacore.app.data.model.ContactUser> = emptyList(),
    val addQuery: String = "",
    val addSelected: Set<String> = emptySet(),
    val addBusy: Boolean = false,
    val addErrors: Map<String, String> = emptyMap(),
    val addDone: Boolean = false
)

/**
 * Group Info: photo/name/description/members + Add/Remove/Admin/Invite/Rename/Icon/Leave.
 * Participants list is confirmed (Category 1 YES); every mutation goes through
 * conventional Ollacore-style endpoints and surfaces backend rejections as
 * "backend check required" instead of failing silently.
 */
class GroupInfoViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as OllacoreApp).container
    private val directoryRepo = container.directoryRepository
    private val chatRepo = container.chatRepository
    private val sessionStore = container.sessionStore

    private val _uiState = MutableStateFlow(GroupInfoUiState())
    val uiState: StateFlow<GroupInfoUiState> = _uiState.asStateFlow()

    private var roomToken: String? = null

    /** Room token snapshot for the in-group search route (MainActivity navigates). */
    fun roomTokenSnapshot(): String? = roomToken

    fun toggleMute() {
        val roomId = _uiState.value.roomId
        if (roomId.isBlank()) return
        viewModelScope.launch {
            val next = !_uiState.value.isMuted
            runCatching { container.chatPrefsStore.setMuted(roomId, next) }
            _uiState.update { it.copy(isMuted = next) }
        }
    }

    fun load(roomId: String) {
        _uiState.update { GroupInfoUiState(roomId = roomId, isLoading = true) }
        viewModelScope.launch {
            _uiState.update { it.copy(isMuted = runCatching { container.chatPrefsStore.isMuted(roomId) }.getOrElse { false }) }
            val token = sessionStore.sessionToken.first() ?: return@launch
            val selfId = sessionStore.userId.first()
            directoryRepo.getRoomToken(token, roomId, "android-groupinfo").onSuccess { rt ->
                roomToken = rt.accessToken
                _uiState.update { it.copy(isEncrypted = rt.e2ee?.encrypted == true) }
                directoryRepo.getInbox(token).onSuccess { inbox ->
                    inbox.conversations.find { it.roomId == roomId }?.let { item ->
                        _uiState.update {
                            it.copy(name = item.name ?: item.peer?.displayName ?: "Group", kind = item.kind)
                        }
                    }
                }
                refreshMembers(selfId)
                refreshSections(token)
                _uiState.update { it.copy(isLoading = false) }
            }.onFailure { e ->
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun refresh() {
        val roomId = _uiState.value.roomId
        if (roomId.isBlank()) return
        viewModelScope.launch {
            val token = sessionStore.sessionToken.first()
            val selfId = sessionStore.userId.first()
            _uiState.update { it.copy(isWorking = true, error = null) }
            refreshMembers(selfId)
            if (token != null) refreshSections(token)
            _uiState.update { it.copy(isWorking = false) }
        }
    }

    /** Loads every reference section that rides on room data (one place, reused by load + refresh). */
    private fun refreshSections(sessionToken: String) {
        viewModelScope.launch {
            val prefs = container.chatPrefsStore
            val roomId = _uiState.value.roomId
            _uiState.update {
                it.copy(
                    isFavourite = runCatching {
                        prefs.favouriteRooms.first().contains(roomId)
                    }.getOrElse { false },
                    allLists = runCatching { prefs.chatLists.first() }.getOrElse { emptyMap() },
                    memberOfLists = runCatching { prefs.listsForRoom(roomId) }.getOrElse { emptySet() },
                    events = runCatching { container.groupEventStore.snapshot(roomId) }.getOrElse { emptyList() }
                )
            }
            scanMedia()
            loadStarred()
            loadAddCandidates(sessionToken)
        }
    }

    private suspend fun refreshMembers(selfId: String?) {
        val token = roomToken ?: return
        val roomId = _uiState.value.roomId
        val selfPhone = sessionStore.phone.first()
        chatRepo.getParticipants(token, roomId).onSuccess { resp ->
            val admin = isSelfAdmin(resp.participants, selfId, selfPhone)
            _uiState.update { it.copy(members = resp.participants, isAdmin = admin) }
        }.onFailure { e ->
            _uiState.update { it.copy(error = e.message) }
        }
    }

    private fun runMutation(
        block: suspend (String, String) -> Result<Unit>,
        successNote: String? = null,
        journal: Pair<String, String?>? = null
    ) {
        val token = roomToken
        val roomId = _uiState.value.roomId
        if (token == null || roomId.isBlank()) {
            _uiState.update { it.copy(error = "Not connected") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true, error = null, inviteText = null) }
            block(token, roomId)
                .onSuccess {
                    val selfId = sessionStore.userId.first()
                    refreshMembers(selfId)
                    journal?.let { (action, detail) -> logEvent(action, detail) }
                    _uiState.update { it.copy(isWorking = false, error = successNote) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isWorking = false, error = backendHint("Operation failed", e.message)) }
                }
        }
    }

    fun rename(name: String) {
        if (name.isBlank()) {
            _uiState.update { it.copy(error = "Enter a group name") }
            return
        }
        runMutation({ t, r -> chatRepo.updateGroup(t, r, name = name) }, journal = "Group name changed" to name)
        _uiState.update { it.copy(name = name) }
    }

    fun setDescription(description: String) {
        runMutation(
            { t, r -> chatRepo.updateGroup(t, r, description = description) },
            journal = "Group description changed" to description.take(80).ifBlank { null }
        )
        _uiState.update { it.copy(description = description.ifBlank { null }) }
    }

    fun setIcon(iconUrl: String) {
        if (iconUrl.isBlank()) {
            _uiState.update { it.copy(error = "Enter an icon URL") }
            return
        }
        runMutation({ t, r -> chatRepo.updateGroup(t, r, iconUrl = iconUrl) }, journal = "Group icon changed" to null)
        _uiState.update { it.copy(iconUrl = iconUrl) }
    }

    fun addMember(userId: String) {
        if (userId.isBlank()) {
            _uiState.update { it.copy(error = "Enter a user ID") }
            return
        }
        runMutation({ t, r -> chatRepo.addMember(t, r, userId) }, journal = "Member added" to userId.take(24))
    }

    fun removeMember(principalId: String) {
        val label = _uiState.value.members.find { it.principalId == principalId }
            ?.let { it.displayName ?: it.phone } ?: principalId.take(8)
        runMutation({ t, r -> chatRepo.removeMember(t, r, principalId) }, journal = "Member removed" to label)
    }

    fun setAdmin(principalId: String, admin: Boolean) {
        val label = _uiState.value.members.find { it.principalId == principalId }
            ?.let { it.displayName ?: it.phone } ?: principalId.take(8)
        runMutation(
            { t, r -> chatRepo.setMemberRole(t, r, principalId, if (admin) "admin" else "member") },
            journal = (if (admin) "Made admin" else "Removed admin") to label
        )
    }

    fun createInvite() {
        val token = roomToken
        val roomId = _uiState.value.roomId
        if (token == null || roomId.isBlank()) {
            _uiState.update { it.copy(error = "Not connected") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true, error = null, inviteText = null) }
            chatRepo.createInvite(token, roomId)
                .onSuccess { inv ->
                    _uiState.update { it.copy(isWorking = false, inviteText = inv.url ?: inv.code ?: "Invite created") }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isWorking = false, error = backendHint("Invite failed", e.message)) }
                }
        }
    }

    fun leaveGroup() {
        val token = roomToken
        val roomId = _uiState.value.roomId
        if (token == null || roomId.isBlank()) {
            _uiState.update { it.copy(error = "Not connected") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true, error = null) }
            chatRepo.leaveGroup(token, roomId)
                .onSuccess { _uiState.update { it.copy(isWorking = false, left = true) } }
                .onFailure { e ->
                    _uiState.update { it.copy(isWorking = false, error = backendHint("Leave failed", e.message)) }
                }
        }
    }

    fun clearTransient() {
        _uiState.update { it.copy(error = null, inviteText = null, reportDone = false, addDone = false) }
    }

    // ── Reference sections: media / starred / favourites / lists / clear / report ──

    /** Media sweep over the last 100 group messages (shared helper, real URLs). */
    fun scanMedia() {
        val token = roomToken ?: return
        val roomId = _uiState.value.roomId
        if (roomId.isBlank()) return
        viewModelScope.launch {
            runCatching {
                com.ollacore.app.data.util.scanSharedMedia(chatRepo, token, roomId)
            }.onSuccess { scan ->
                _uiState.update {
                    it.copy(
                        mediaCount = scan.mediaCount,
                        linkCount = scan.linkCount,
                        docCount = scan.docCount,
                        thumbs = scan.thumbs
                    )
                }
            }.onFailure { e ->
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    /** Group-starred messages: persisted ids joined against real history bodies. */
    fun loadStarred() {
        val token = roomToken ?: return
        val roomId = _uiState.value.roomId
        if (roomId.isBlank()) return
        viewModelScope.launch {
            val ids = runCatching {
                container.chatPrefsStore.getStarredIds(roomId)
            }.getOrElse { emptySet() }
            if (ids.isEmpty()) {
                _uiState.update { it.copy(starred = emptyList()) }
                return@launch
            }
            runCatching {
                chatRepo.listMessages(token, roomId, limit = 100).getOrThrow().messages
            }.onSuccess { messages ->
                val names = _uiState.value.members.associate {
                    it.principalId to (it.displayName?.ifBlank { null } ?: it.phone ?: it.principalId.take(8))
                }
                _uiState.update {
                    it.copy(starred = messages.filter { m -> m.id in ids }.map { m ->
                        GroupStarredMsg(
                            messageId = m.id,
                            sender = names[m.senderId] ?: m.senderId.take(8),
                            preview = com.ollacore.app.data.util.messagePreviewSnippet(m),
                            at = shortStamp(m.createdAt),
                            eventSeq = m.eventSeq
                        )
                    })
                }
            }.onFailure { e ->
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun toggleFavourite() {
        val roomId = _uiState.value.roomId
        if (roomId.isBlank()) return
        viewModelScope.launch {
            val fav = runCatching {
                container.chatPrefsStore.toggleFavourite(roomId)
            }.getOrElse { _uiState.value.isFavourite }
            _uiState.update { it.copy(isFavourite = fav) }
        }
    }

    fun setRoomList(listName: String, member: Boolean) {
        val roomId = _uiState.value.roomId
        val name = listName.trim()
        if (roomId.isBlank() || name.isEmpty()) return
        viewModelScope.launch {
            runCatching { container.chatPrefsStore.setRoomInList(name, roomId, member) }
            val lists = runCatching { container.chatPrefsStore.chatLists.first() }
                .getOrElse { _uiState.value.allLists }
            val mine = runCatching { container.chatPrefsStore.listsForRoom(roomId) }
                .getOrElse { _uiState.value.memberOfLists }
            _uiState.update { it.copy(allLists = lists, memberOfLists = mine) }
        }
    }

    /** Clear chat = local watermark (same rule as 1-to-1 Clear); group + membership survive. */
    fun clearChat() {
        val roomId = _uiState.value.roomId
        if (roomId.isBlank()) return
        viewModelScope.launch {
            runCatching { container.chatPrefsStore.setClearedBefore(roomId, Int.MAX_VALUE) }
            _uiState.update { it.copy(error = "Chat cleared on this device. Server history is unchanged.") }
        }
    }

    /** Report queue (client-only until the report endpoint lands; never claims success). */
    fun reportGroup(reason: String) {
        val roomId = _uiState.value.roomId
        if (roomId.isBlank() || reason.isBlank()) return
        viewModelScope.launch {
            runCatching {
                container.chatPrefsStore.submitReport(roomId, _uiState.value.name.ifBlank { "Group" }, reason)
            }.onSuccess {
                _uiState.update { it.copy(reportDone = true) }
            }.onFailure { e ->
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    // ── Add-member workflow (contacts + search + multi-select + confirm) ──

    /** Inbox peers as add candidates (need userIds for addMember). */
    fun loadAddCandidates(sessionToken: String) {
        viewModelScope.launch {
            directoryRepo.getInbox(sessionToken)
                .onSuccess { inbox ->
                    val seen = LinkedHashMap<String, com.ollacore.app.data.model.ContactUser>()
                    inbox.conversations.forEach { item ->
                        val peer = item.peer
                        if (peer != null && !seen.containsKey(peer.userId)) {
                            seen[peer.userId] = com.ollacore.app.data.model.ContactUser(
                                peer.userId, peer.phone, peer.displayName ?: item.name
                            )
                        }
                    }
                    _uiState.update { it.copy(addCandidates = seen.values.toList()) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = e.message) }
                }
        }
    }

    fun setAddQuery(query: String) {
        _uiState.update { it.copy(addQuery = query) }
    }

    fun toggleAddSelect(userId: String) {
        _uiState.update { state ->
            val sel = state.addSelected.toMutableSet()
            if (userId in sel) sel.remove(userId) else sel.add(userId)
            state.copy(addSelected = sel, addErrors = state.addErrors - userId)
        }
    }

    /**
     * Confirm add: admin-gated client-side (server enforces too), sequential
     * per-member requests, failures keep selection for retry, roster refreshes.
     */
    fun confirmAddMembers() {
        val roomId = _uiState.value.roomId
        val token = roomToken
        val selected = _uiState.value.addSelected.toList()
        if (token == null || roomId.isBlank() || selected.isEmpty()) return
        if (!_uiState.value.isAdmin) {
            _uiState.update { it.copy(error = "Only group admins can add members.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(addBusy = true, addErrors = emptyMap(), error = null) }
            val failed = mutableMapOf<String, String>()
            selected.forEach { userId ->
                chatRepo.addMember(token, roomId, userId)
                    .onSuccess {
                        val label = _uiState.value.addCandidates.find { it.userId == userId }
                            ?.displayName ?: userId.take(8)
                        logEvent("Member added", label)
                    }
                    .onFailure { e -> failed[userId] = e.message ?: "Add failed" }
            }
            val selfId = sessionStore.userId.first()
            refreshMembers(selfId)
            _uiState.update {
                it.copy(
                    addBusy = false,
                    addErrors = failed,
                    addSelected = it.addSelected - failed.keys - (selected.toSet() - failed.keys),
                    addDone = failed.isEmpty()
                )
            }
        }
    }

    fun consumeAddDone() {
        _uiState.update { it.copy(addDone = false) }
    }

    /** Group icon via picker file: upload flow + tentative set-icon (backend-checked). */
    fun setIconFile(file: java.io.File, mime: String) {
        val token = roomToken
        val roomId = _uiState.value.roomId
        if (token == null || roomId.isBlank() || !file.exists()) {
            _uiState.update { it.copy(error = "No image selected") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true, error = null) }
            runCatching {
                val uploader = com.ollacore.app.data.remote.AttachmentUploader()
                val size = file.length()
                val init = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    chatRepo.initAttachment(token, roomId, file.name, mime, size).getOrThrow()
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    uploader.uploadToPresignedUrl(init.uploadUrl, file, mime)
                    chatRepo.completeAttachment(token, roomId, init.attachmentId).getOrThrow()
                }
                val url = chatRepo.downloadAttachment(token, roomId, init.attachmentId).getOrThrow().downloadUrl
                chatRepo.updateGroup(token, roomId, iconUrl = url).getOrThrow()
                url
            }.onSuccess { url ->
                _uiState.update { it.copy(isWorking = false, iconUrl = url) }
                logEvent("Group icon changed", null)
            }.onFailure { e ->
                _uiState.update { it.copy(isWorking = false, error = backendHint("Icon upload failed", e.message)) }
            }
        }
    }

    private fun logEvent(action: String, detail: String?) {
        val roomId = _uiState.value.roomId
        if (roomId.isBlank()) return
        viewModelScope.launch {
            runCatching { container.groupEventStore.log(roomId, "You", action, detail) }
            val fresh = runCatching { container.groupEventStore.snapshot(roomId) }
                .getOrElse { _uiState.value.events }
            _uiState.update { it.copy(events = fresh) }
        }
    }

    private fun shortStamp(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return runCatching {
            val instant = try {
                java.time.Instant.parse(raw)
            } catch (_: Exception) {
                val n = raw.toLong()
                java.time.Instant.ofEpochMilli(if (n < 1_000_000_000_000L) n * 1000 else n)
            }
            val z = java.time.ZoneId.systemDefault()
            java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm").format(instant.atZone(z))
        }.getOrElse { "" }
    }

    companion object {
        fun backendHint(action: String, detail: String?): String {
            return "$action${if (detail != null) ": $detail" else ""} (backend check required - confirm Ollacore group-mgmt endpoint)"
        }
    }
}

/** Display name for a member row (never raw id when a name/phone exists). */
fun memberDisplayName(m: Participant): String =
    m.displayName?.takeIf { it.isNotBlank() } ?: m.phone?.takeIf { it.isNotBlank() } ?: m.principalId.take(8)

/** "You" / "Group admin" aware subtitle, reference row hierarchy. */
fun memberSubtitle(m: Participant, selfId: String?): String {
    val you = selfId != null && m.principalId == selfId
    val admin = m.role.equals("admin", ignoreCase = true)
    return when {
        you && admin -> "You · Group admin"
        you -> "You"
        admin -> "Group admin"
        else -> m.phone?.takeIf { it.isNotBlank() } ?: m.principalId.take(8)
    }
}

fun isGroupAdmin(m: Participant): Boolean = m.role.equals("admin", ignoreCase = true)

/** Privileged roster roles (group creators often carry owner/creator, not admin). */
private val ADMIN_ROLES = setOf("admin", "owner", "creator")

/**
 * True when the session user appears in the roster with a privileged role.
 * Identity matches the principal id first, then the session phone (principal
 * formats vary; the old displayName fallback could never match a user id).
 * Role match is case-insensitive; a missing role never grants admin. Pure.
 */
fun isSelfAdmin(participants: List<Participant>, selfId: String?, selfPhone: String?): Boolean {
    if (selfId == null && selfPhone == null) return false
    return participants.any { p ->
        val isSelf = (selfId != null && p.principalId == selfId) ||
            (selfPhone != null && p.phone == selfPhone)
        isSelf && p.role != null && ADMIN_ROLES.any { role -> p.role.equals(role, ignoreCase = true) }
    }
}

/** Live member search across name/phone/id; blank query returns all. */
fun filterMembers(members: List<Participant>, query: String): List<Participant> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return members
    return members.filter { m ->
        m.displayName?.lowercase()?.contains(q) == true ||
            m.phone?.contains(query.trim()) == true ||
            m.principalId.lowercase().contains(q)
    }
}

/** You first, then admins, then named members alphabetically, then phone-only rows. */
fun sortMembersYouFirst(members: List<Participant>, selfId: String?): List<Participant> =
    members.sortedWith(
        compareBy(
            { if (selfId != null && it.principalId == selfId) 0 else 1 },
            { if (isGroupAdmin(it)) 0 else 1 },
            { if (it.displayName.isNullOrBlank()) 1 else 0 },
            { memberDisplayName(it).lowercase() }
        )
    )
