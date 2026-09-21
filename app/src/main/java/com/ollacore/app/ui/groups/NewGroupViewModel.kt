package com.ollacore.app.ui.groups

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ollacore.app.OllacoreApp
import com.ollacore.app.data.model.ContactUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class NewGroupStep { SELECT_MEMBERS, DETAILS, REVIEW }

data class NewGroupUiState(
    val step: NewGroupStep = NewGroupStep.SELECT_MEMBERS,
    val contacts: List<ContactUser> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val groupName: String = "",
    val groupDescription: String = "",
    val isLoading: Boolean = false,
    val isCreating: Boolean = false,
    val error: String? = null,
    val iconNote: String? = null,
    val createdRoomId: String? = null
)

/**
 * New Group: Select contacts -> Group name/photo -> Create.
 * Create uses confirmed POST /v1/directory/conversations/group (Category 1 YES).
 * Icon upload is best-effort (attachment init/PUT/complete + tentative set-icon).
 */
class NewGroupViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as OllacoreApp).container
    private val directoryRepo = container.directoryRepository
    private val chatRepo = container.chatRepository
    private val sessionStore = container.sessionStore
    private val uploader = com.ollacore.app.data.remote.AttachmentUploader()

    private val _uiState = MutableStateFlow(NewGroupUiState())
    val uiState: StateFlow<NewGroupUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val token = sessionStore.sessionToken.first() ?: return@launch
            _uiState.update { it.copy(isLoading = true) }
            directoryRepo.getInbox(token)
                .onSuccess { inbox ->
                    val seen = LinkedHashMap<String, ContactUser>()
                    inbox.conversations.forEach { item ->
                        val peer = item.peer
                        if (peer != null && !seen.containsKey(peer.userId)) {
                            seen[peer.userId] = ContactUser(peer.userId, peer.phone, peer.displayName ?: item.name)
                        }
                    }
                    _uiState.update { it.copy(contacts = seen.values.toList(), isLoading = false) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
        }
    }

    fun toggleSelect(userId: String) {
        _uiState.update {
            val sel = it.selectedIds.toMutableSet()
            if (userId in sel) sel.remove(userId) else sel.add(userId)
            it.copy(selectedIds = sel, error = null)
        }
    }

    fun goDetails() {
        if (_uiState.value.selectedIds.isEmpty()) {
            _uiState.update { it.copy(error = "Select at least one member") }
            return
        }
        _uiState.update { it.copy(step = NewGroupStep.DETAILS, error = null) }
    }

    fun backToSelect() {
        _uiState.update { it.copy(step = NewGroupStep.SELECT_MEMBERS) }
    }

    fun backToDetails() {
        _uiState.update { it.copy(step = NewGroupStep.DETAILS) }
    }

    fun updateName(name: String) {
        _uiState.update { it.copy(groupName = name, error = null) }
    }

    fun updateDescription(description: String) {
        _uiState.update { it.copy(groupDescription = description, error = null) }
    }

    fun goReview() {
        if (_uiState.value.groupName.trim().isBlank()) {
            _uiState.update { it.copy(error = "Enter a group name") }
            return
        }
        _uiState.update { it.copy(step = NewGroupStep.REVIEW, error = null) }
    }

    fun consumeCreated(): String? {
        val id = _uiState.value.createdRoomId ?: return null
        _uiState.update { it.copy(createdRoomId = null) }
        return id
    }

    fun createGroup(iconFile: File? = null, iconMime: String? = null) {
        val name = _uiState.value.groupName.trim()
        val description = _uiState.value.groupDescription.trim()
        val members = _uiState.value.selectedIds.toList()
        if (name.isBlank()) {
            _uiState.update { it.copy(error = "Enter a group name") }
            return
        }
        if (members.isEmpty()) {
            _uiState.update { it.copy(error = "Select at least one member") }
            return
        }
        viewModelScope.launch {
            val token = sessionStore.sessionToken.first() ?: return@launch
            _uiState.update { it.copy(isCreating = true, error = null, iconNote = null) }
            directoryRepo.createGroupConversation(token, members, name)
                .onSuccess { conv ->
                    // Best-effort icon + description (tentative room PATCH; backend-check on reject).
                    if (iconFile != null && iconFile.exists()) {
                        runCatching { uploadGroupIcon(token, conv.roomId, iconFile, iconMime ?: "image/jpeg") }
                            .onFailure { e ->
                                _uiState.update { it.copy(iconNote = "Group created, but icon needs backend support: ${e.message}") }
                            }
                    }
                    if (description.isNotBlank()) {
                        runCatching {
                            val deviceId = "android-${UUID.randomUUID()}"
                            val roomToken = directoryRepo.getRoomToken(token, conv.roomId, deviceId).getOrThrow().accessToken
                            chatRepo.updateGroup(roomToken, conv.roomId, description = description).getOrThrow()
                        }.onFailure { e ->
                            _uiState.update { it.copy(iconNote = "Group created, but description needs backend support: ${e.message}") }
                        }
                    }
                    _uiState.update { it.copy(isCreating = false, createdRoomId = conv.roomId) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isCreating = false, error = e.message) }
                }
        }
    }

    private suspend fun uploadGroupIcon(token: String, roomId: String, file: File, mime: String) {
        val deviceId = "android-${UUID.randomUUID()}"
        val roomToken = directoryRepo.getRoomToken(token, roomId, deviceId).getOrThrow().accessToken
        val size = file.length()
        val attachmentId = withContext(Dispatchers.IO) {
            val init = chatRepo.initAttachment(roomToken, roomId, file.name, mime, size).getOrThrow()
            uploader.uploadToPresignedUrl(init.uploadUrl, file, mime)
            chatRepo.completeAttachment(roomToken, roomId, init.attachmentId).getOrThrow()
            init.attachmentId
        }
        val downloadUrl = chatRepo.downloadAttachment(roomToken, roomId, attachmentId).getOrThrow().downloadUrl
        // Tentative set-icon (backend check required); throws if unsupported.
        chatRepo.updateGroup(roomToken, roomId, iconUrl = downloadUrl).getOrThrow()
    }
}
