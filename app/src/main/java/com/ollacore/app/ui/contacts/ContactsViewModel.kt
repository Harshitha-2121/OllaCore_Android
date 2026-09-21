package com.ollacore.app.ui.contacts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ollacore.app.OllacoreApp
import com.ollacore.app.data.model.ContactUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ContactsUiState(
    val contacts: List<ContactUser> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

/**
 * Contacts from conversations you already have (Category 1 - inbox YES).
 * lookupContacts needs phone numbers, so the picker lists known inbox peers;
 * unknown numbers can be added by phone via lookupAndAdd().
 */
class ContactsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as OllacoreApp).container
    private val directoryRepo = container.directoryRepository
    private val sessionStore = container.sessionStore

    private val _uiState = MutableStateFlow(ContactsUiState())
    val uiState: StateFlow<ContactsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val token = sessionStore.sessionToken.first() ?: return@launch
            _uiState.update { it.copy(isLoading = true, error = null) }
            directoryRepo.getInbox(token)
                .onSuccess { inbox ->
                    val seen = LinkedHashMap<String, ContactUser>()
                    inbox.conversations.forEach { item ->
                        val peer = item.peer
                        if (peer != null && !seen.containsKey(peer.userId)) {
                            seen[peer.userId] = ContactUser(
                                userId = peer.userId,
                                phone = peer.phone,
                                displayName = peer.displayName ?: item.name
                            )
                        }
                    }
                    _uiState.update { it.copy(contacts = seen.values.toList(), isLoading = false) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
        }
    }

    /** Resolve an unknown phone number via directory lookup, then return its userId (or null). */
    suspend fun lookupUserId(phone: String): String? {
        val token = sessionStore.sessionToken.first() ?: return null
        return directoryRepo.lookupContacts(token, listOf(phone))
            .getOrNull()?.contacts?.firstOrNull()?.userId
    }

    /** Open (or create) a 1-to-1 conversation; returns roomId or null. */
    suspend fun openDirect(peerUserId: String): String? {
        val token = sessionStore.sessionToken.first() ?: return null
        return directoryRepo.openDirectConversation(token, peerUserId).getOrNull()?.roomId
    }
}
