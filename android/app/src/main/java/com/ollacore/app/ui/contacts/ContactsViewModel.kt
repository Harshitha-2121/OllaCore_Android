package com.ollacore.app.ui.contacts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ollacore.app.OllacoreApp
import com.ollacore.app.data.local.LocalContact
import com.ollacore.app.data.model.ContactUser
import com.ollacore.app.data.util.friendlyError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ContactsUiState(
    val rows: List<ContactRow> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

/** Outcome of resolving a typed phone number via the Ollacore directory. */
sealed interface NumberLookupResult {
    data class OnOllacore(
        val userId: String,
        val displayName: String?,
        val phone: String,
        val alreadySaved: Boolean
    ) : NumberLookupResult

    data class NotOnOllacore(val phone: String) : NumberLookupResult
    data class Invalid(val message: String) : NumberLookupResult
    data class Failed(val message: String) : NumberLookupResult
}

/** Outcome of opening (or creating) a 1-to-1 conversation for a row. */
sealed interface OpenChatResult {
    data class Opened(val roomId: String) : OpenChatResult
    data class NotOnOllacore(val phone: String) : OpenChatResult
    data class Failed(val message: String) : OpenChatResult
}

/**
 * Contacts = inbox peers (server, in-memory) MERGED with user-saved local
 * contacts (DataStore, persistent). Local CRUD updates the list reactively via
 * [LocalContactsStore.contacts]; there is no Ollacore contact-CRUD endpoint,
 * so add/edit/delete are client-local by design (never faked as server ops).
 */
class ContactsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as OllacoreApp).container
    private val directoryRepo = container.directoryRepository
    private val sessionStore = container.sessionStore
    private val localStore = container.localContactsStore

    private val inboxPeers = MutableStateFlow<List<ContactUser>>(emptyList())
    private val loading = MutableStateFlow(false)
    private val failure = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ContactsUiState> = combine(
        inboxPeers, localStore.contacts, loading, failure
    ) { inbox, local, isLoading, error ->
        ContactsUiState(
            rows = ContactBook.mergeRows(inbox, local),
            isLoading = isLoading,
            error = error
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ContactsUiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val token = sessionStore.sessionToken.first() ?: return@launch
            loading.update { true }
            failure.update { null }
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
                    inboxPeers.update { seen.values.toList() }
                    loading.update { false }
                }
                .onFailure { e ->
                    loading.update { false }
                    // Local contacts stay visible offline; only the inbox half fails.
                    failure.update { friendlyError(e.message) }
                }
        }
    }

    fun clearError() {
        failure.update { null }
    }

    fun rowFor(key: String): ContactRow? = uiState.value.rows.find { it.key == key }

    // ── Local CRUD (persistent, immediate UI update via store Flow) ──

    suspend fun addContact(
        firstName: String,
        lastName: String,
        phone: String,
        photoUri: String?,
        username: String? = null,
        syncToPhone: Boolean = true
    ): ContactFormError? {
        val existing = localStore.contacts.first()
        ContactBook.validateForm(firstName, phone, existing)?.let { return it }
        val now = System.currentTimeMillis()
        localStore.upsert(
            LocalContact(
                id = UUID.randomUUID().toString(),
                firstName = firstName.trim(),
                lastName = lastName.trim(),
                phone = phone.trim(),
                normalizedPhone = ContactBook.normalizePhone(phone),
                photoUri = photoUri,
                username = username?.trim()?.ifEmpty { null },
                syncToPhone = syncToPhone,
                createdAt = now,
                updatedAt = now
            )
        )
        return null
    }

    suspend fun updateContact(
        localId: String,
        firstName: String,
        lastName: String,
        phone: String,
        photoUri: String?
    ): ContactFormError? {
        val existing = localStore.contacts.first()
        val current = existing.find { it.id == localId } ?: return null
        ContactBook.validateForm(firstName, phone, existing, editingId = localId)?.let { return it }
        localStore.upsert(
            current.copy(
                firstName = firstName.trim(),
                lastName = lastName.trim(),
                phone = phone.trim(),
                normalizedPhone = ContactBook.normalizePhone(phone),
                photoUri = photoUri,
                // Number changed -> previous resolution may be stale; re-resolve on next chat.
                resolvedUserId = if (ContactBook.normalizePhone(phone) == current.normalizedPhone) {
                    current.resolvedUserId
                } else {
                    null
                },
                updatedAt = System.currentTimeMillis()
            )
        )
        return null
    }

    /**
     * Delete the saved local record behind [row]. Pure inbox rows have nothing
     * saved: returns false and the server conversation is left untouched.
     */
    suspend fun deleteRow(row: ContactRow): Boolean {
        val localId = row.localId ?: return false
        if (row.photoUri != null) deleteContactPhoto(row.photoUri)
        localStore.delete(localId)
        return true
    }

    // ── Directory flows (existing backend) ──

    /** Resolve a typed number: distinct outcomes for on-Ollacore / unknown / bad input / failure. */
    suspend fun lookupNumber(rawPhone: String): NumberLookupResult {
        val phone = rawPhone.trim()
        if (phone.isEmpty()) return NumberLookupResult.Invalid("Enter a phone number")
        val normalized = ContactBook.normalizePhone(phone)
        if (!ContactBook.isValidPhone(normalized)) {
            return NumberLookupResult.Invalid(
                "That doesn't look like a valid phone number. Use 7-15 digits, e.g. +15550001111"
            )
        }
        val token = sessionStore.sessionToken.first()
            ?: return NumberLookupResult.Failed("You're signed out. Log in again to look up numbers.")
        return directoryRepo.lookupContacts(token, listOf(normalized))
            .fold(
                onSuccess = { response ->
                    val match = response.contacts.firstOrNull()
                    if (match == null) {
                        NumberLookupResult.NotOnOllacore(normalized)
                    } else {
                        val saved = localStore.contacts.first()
                            .any { it.normalizedPhone == ContactBook.normalizePhone(match.phone) }
                        NumberLookupResult.OnOllacore(
                            userId = match.userId,
                            displayName = match.displayName,
                            phone = match.phone,
                            alreadySaved = saved
                        )
                    }
                },
                onFailure = { e -> NumberLookupResult.Failed(friendlyError(e.message)) }
            )
    }

    /**
     * Open (or create) a 1-to-1 conversation for [row]. Local-only rows are
     * resolved via directory lookup first and the resolution is persisted, so
     * the second tap never pays the lookup cost again.
     */
    suspend fun openChat(row: ContactRow): OpenChatResult {
        val token = sessionStore.sessionToken.first()
            ?: return OpenChatResult.Failed("You're signed out. Log in again to start chats.")
        var userId = row.userId
        if (userId == null) {
            val lookup = directoryRepo.lookupContacts(token, listOf(row.normalizedPhone))
                .getOrElse { return OpenChatResult.Failed(friendlyError(it.message)) }
            val match = lookup.contacts.firstOrNull()
                ?: return OpenChatResult.NotOnOllacore(row.phone)
            userId = match.userId
            // Persist the resolution on the local record for next time.
            row.localId?.let { localId ->
                val current = localStore.contacts.first().find { it.id == localId }
                if (current != null && current.resolvedUserId != userId) {
                    localStore.upsert(
                        current.copy(resolvedUserId = userId, updatedAt = System.currentTimeMillis())
                    )
                }
            }
        }
        return directoryRepo.openDirectConversation(token, userId)
            .fold(
                onSuccess = { OpenChatResult.Opened(it.roomId) },
                onFailure = { OpenChatResult.Failed(friendlyError(it.message)) }
            )
    }
}
