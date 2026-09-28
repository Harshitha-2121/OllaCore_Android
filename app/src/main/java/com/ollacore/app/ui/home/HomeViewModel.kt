package com.ollacore.app.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ollacore.app.OllacoreApp
import com.ollacore.app.data.model.InboxItem
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class HomeUiState(
    val inbox: List<InboxItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val displayName: String? = null,
    val phone: String? = null,
    val myUserId: String? = null
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as OllacoreApp).container
    private val directoryRepo = container.directoryRepository
    private val sessionStore = container.sessionStore

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // ── WhatsApp-style chat-list multi-selection (long-press to enter) ──
    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    /** Toggle one room; empty set implicitly exits selection mode. */
    fun toggleChatSelect(roomId: String) {
        _selectedIds.update { if (roomId in it) it - roomId else it + roomId }
    }

    fun selectAllChats(roomIds: Collection<String>) {
        _selectedIds.update { it + roomIds }
    }

    fun clearChatSelection() {
        _selectedIds.value = emptySet()
    }

    init {
        viewModelScope.launch {
            combine(
                sessionStore.sessionToken,
                sessionStore.displayName,
                sessionStore.phone,
                sessionStore.userId
            ) { token, name, phone, userId ->
                arrayOf(token, name, phone, userId)
            }.collect { parts ->
                val token = parts[0]
                _uiState.update {
                    it.copy(
                        displayName = parts[1],
                        phone = parts[2],
                        myUserId = parts[3]
                    )
                }
                if (token != null) {
                    loadInbox(token)
                }
            }
        }
    }

    /**
     * Opening a chat unarchives it (Close chat is reversible). Runs in this
     * VM's scope with the SHARED prefs store: the home entry stays in the
     * backstack so the write always completes, unlike a route-bound scope
     * that is cancelled by the navigate that follows it.
     */
    fun markOpened(roomId: String) {
        viewModelScope.launch {
            // Settings > Chats > Keep chats archived: opening no longer unarchives.
            val keep = runCatching {
                container.chatPrefsStore.getCustomBool(
                    com.ollacore.app.data.local.ChatPrefsStore.SettingsKeys.KEEP_ARCHIVED, false
                )
            }.getOrElse { false }
            if (!keep) {
                runCatching { container.chatPrefsStore.setArchived(roomId, false) }
            }
        }
    }

    fun loadInbox(token: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            directoryRepo.getInbox(token)
                .onSuccess { response ->
                    _uiState.update {
                        it.copy(inbox = response.conversations, isLoading = false)
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = e.message ?: "Failed to load inbox")
                    }
                }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val token = sessionStore.sessionToken.first() ?: return@launch
            loadInbox(token)
        }
    }

    // ── Bulk selection actions (CLIENT-ONLY stores; same semantics as the
    //   per-chat overflow menu: mute flag, archive, wipe(Int.MAX_VALUE)+archive) ──

    /** Pin when any selected room is unpinned, else unpin all. */
    fun togglePinSelected() {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val pinned = runCatching {
                container.chatPrefsStore.pinnedRooms.first()
            }.getOrElse { emptySet() }
            val pin = ids.any { it !in pinned }
            ids.forEach { runCatching { container.chatPrefsStore.setPinned(it, pin) } }
        }
    }

    /** Mute when any selected room is unmuted, else unmute all (indefinite). */
    fun toggleMuteSelected() {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            var anyUnmuted = false
            ids.forEach {
                if (!runCatching { container.chatPrefsStore.isMuted(it) }.getOrDefault(false)) {
                    anyUnmuted = true
                }
            }
            ids.forEach { runCatching { container.chatPrefsStore.setMuted(it, anyUnmuted) } }
        }
    }

    /** Archive when any selected room is unarchived, else unarchive all. */
    fun toggleArchiveSelected() {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val archived = runCatching {
                container.chatPrefsStore.archivedRooms.first()
            }.getOrElse { emptySet() }
            val archive = ids.any { it !in archived }
            ids.forEach { runCatching { container.chatPrefsStore.setArchived(it, archive) } }
            if (archive) _selectedIds.value = emptySet()
        }
    }

    /** Delete = local wipe + archive per room (mirrors per-chat Delete). */
    fun deleteSelected() {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach {
                runCatching { container.chatPrefsStore.setClearedBefore(it, Int.MAX_VALUE) }
                runCatching { container.chatPrefsStore.setArchived(it, true) }
            }
            _selectedIds.value = emptySet()
        }
    }
}
