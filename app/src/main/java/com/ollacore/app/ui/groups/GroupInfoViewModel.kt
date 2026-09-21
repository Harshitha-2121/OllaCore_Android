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
    val left: Boolean = false
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
                directoryRepo.getInbox(token).onSuccess { inbox ->
                    inbox.conversations.find { it.roomId == roomId }?.let { item ->
                        _uiState.update {
                            it.copy(name = item.name ?: item.peer?.displayName ?: "Group", kind = item.kind)
                        }
                    }
                }
                refreshMembers(selfId)
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
            val selfId = sessionStore.userId.first()
            _uiState.update { it.copy(isWorking = true, error = null) }
            refreshMembers(selfId)
            _uiState.update { it.copy(isWorking = false) }
        }
    }

    private suspend fun refreshMembers(selfId: String?) {
        val token = roomToken ?: return
        val roomId = _uiState.value.roomId
        chatRepo.getParticipants(token, roomId).onSuccess { resp ->
            val admin = resp.participants.any {
                (it.principalId == selfId || it.displayName == selfId) && it.role.equals("admin", ignoreCase = true)
            }
            _uiState.update { it.copy(members = resp.participants, isAdmin = admin) }
        }.onFailure { e ->
            _uiState.update { it.copy(error = e.message) }
        }
    }

    private fun runMutation(block: suspend (String, String) -> Result<Unit>, successNote: String? = null) {
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
        runMutation({ t, r -> chatRepo.updateGroup(t, r, name = name) })
        _uiState.update { it.copy(name = name) }
    }

    fun setDescription(description: String) {
        runMutation({ t, r -> chatRepo.updateGroup(t, r, description = description) })
        _uiState.update { it.copy(description = description.ifBlank { null }) }
    }

    fun setIcon(iconUrl: String) {
        if (iconUrl.isBlank()) {
            _uiState.update { it.copy(error = "Enter an icon URL") }
            return
        }
        runMutation({ t, r -> chatRepo.updateGroup(t, r, iconUrl = iconUrl) })
        _uiState.update { it.copy(iconUrl = iconUrl) }
    }

    fun addMember(userId: String) {
        if (userId.isBlank()) {
            _uiState.update { it.copy(error = "Enter a user ID") }
            return
        }
        runMutation({ t, r -> chatRepo.addMember(t, r, userId) })
    }

    fun removeMember(principalId: String) {
        runMutation({ t, r -> chatRepo.removeMember(t, r, principalId) })
    }

    fun setAdmin(principalId: String, admin: Boolean) {
        runMutation({ t, r -> chatRepo.setMemberRole(t, r, principalId, if (admin) "admin" else "member") })
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
        _uiState.update { it.copy(error = null, inviteText = null) }
    }

    companion object {
        fun backendHint(action: String, detail: String?): String {
            return "$action${if (detail != null) ": $detail" else ""} (backend check required - confirm Ollacore group-mgmt endpoint)"
        }
    }
}
