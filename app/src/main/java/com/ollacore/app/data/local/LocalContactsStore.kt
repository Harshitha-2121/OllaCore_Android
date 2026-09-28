package com.ollacore.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.contactsPrefs by preferencesDataStore(name = "ollacore_local_contacts")

/**
 * User-created contacts (CLIENT-ONLY: no Ollacore contact-CRUD endpoint exists).
 * DataStore-backed JSON list - same pattern as [CallLogStore]; no Room/KSP needed.
 * Server conversations are NOT duplicated here; inbox peers are merged at read
 * time (see ContactRows.mergeRows) so edits/renames never fork server state.
 */
@Serializable
data class LocalContact(
    val id: String,
    val firstName: String,
    val lastName: String = "",
    /** Phone as typed (trimmed). */
    val phone: String,
    /** Canonical digits used for dedupe/search/lookup. */
    val normalizedPhone: String,
    /** Internal file path of the contact photo, if any. */
    val photoUri: String? = null,
    /** Ollacore userId once a directory lookup has resolved this number. */
    val resolvedUserId: String? = null,
    /** Optional @username (New-contact form field; no backend binding). */
    val username: String? = null,
    /** "Sync contact to phone" preference from the New-contact form. */
    val syncToPhone: Boolean = true,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

class LocalContactsStore(private val context: Context) {

    companion object {
        private val KEY_CONTACTS = stringPreferencesKey("local_contacts_json")
        private const val MAX_ENTRIES = 2000
        private val json = Json { ignoreUnknownKeys = true }
        private val listSer = ListSerializer(LocalContact.serializer())
    }

    val contacts: Flow<List<LocalContact>> = context.contactsPrefs.data.map { prefs ->
        val raw = prefs[KEY_CONTACTS] ?: return@map emptyList()
        runCatching { json.decodeFromString(listSer, raw) }.getOrElse { emptyList() }
    }

    /** Insert or replace by [LocalContact.id]. */
    suspend fun upsert(contact: LocalContact) {
        context.contactsPrefs.edit { prefs ->
            val current = readLocked(prefs[KEY_CONTACTS])
            val updated = (current.filter { it.id != contact.id } + contact).take(MAX_ENTRIES)
            prefs[KEY_CONTACTS] = json.encodeToString(listSer, updated)
        }
    }

    suspend fun delete(id: String) {
        context.contactsPrefs.edit { prefs ->
            val current = readLocked(prefs[KEY_CONTACTS])
            prefs[KEY_CONTACTS] = json.encodeToString(listSer, current.filter { it.id != id })
        }
    }

    private fun readLocked(raw: String?): List<LocalContact> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(listSer, raw) }.getOrElse { emptyList() }
    }
}
