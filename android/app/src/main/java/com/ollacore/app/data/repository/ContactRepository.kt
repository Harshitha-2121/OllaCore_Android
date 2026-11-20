package com.ollacore.app.data.repository

import com.ollacore.app.data.db.ContactEntity
import com.ollacore.app.data.db.OllacoreDatabase
import com.ollacore.app.data.local.LocalContact
import com.ollacore.app.data.local.LocalContactsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Room-backed contacts. Exposes the same [LocalContact] model the UI
 * already uses, so ContactBook / screens are untouched; persistence
 * moved from the DataStore JSON blob to the queryable contacts table.
 * Room is authoritative after the one-time legacy migration below.
 */
class ContactRepository(
    private val db: OllacoreDatabase,
    private val legacyStore: LocalContactsStore? = null
) {
    val contacts: Flow<List<LocalContact>> =
        db.contactDao().observeAll().map { list -> list.map { it.toLocal() } }

    suspend fun upsert(contact: LocalContact) = withContext(Dispatchers.IO) {
        db.contactDao().upsert(contact.toEntity())
    }

    suspend fun delete(localId: String) = withContext(Dispatchers.IO) {
        db.contactDao().deleteByLocalId(localId)
    }

    suspend fun find(localId: String): LocalContact? = withContext(Dispatchers.IO) {
        db.contactDao().getByLocalId(localId)?.toLocal()
    }

    /**
     * One-time move from DataStore -> Room. Copies legacy entries only
     * when Room is empty (never overwrites), then leaves the legacy
     * store alone. Safe to call on every launch.
     */
    suspend fun migrateLegacyIfNeeded() = withContext(Dispatchers.IO) {
        val store = legacyStore ?: return@withContext
        val legacy = runCatching { store.contacts.first() }.getOrElse { emptyList() }
        if (legacy.isEmpty()) return@withContext
        val dao = db.contactDao()
        // Room Flows always emit (empty list when no rows), so first() is safe.
        val roomEmpty = runCatching { dao.observeAll().first().isEmpty() }.getOrElse { false }
        if (roomEmpty) dao.upsertAll(legacy.map { it.toEntity() })
    }

    private fun LocalContact.toEntity(): ContactEntity = ContactEntity(
        userId = resolvedUserId ?: "local-$id",
        phone = phone,
        normalizedPhone = normalizedPhone,
        displayName = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ").ifBlank { null },
        firstName = firstName,
        lastName = lastName,
        photoPath = photoUri,
        username = username,
        syncToPhone = syncToPhone,
        localContactId = id,
        resolvedUserId = resolvedUserId,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun ContactEntity.toLocal(): LocalContact = LocalContact(
        id = localContactId ?: userId,
        firstName = firstName.ifBlank { displayName?.substringBefore(" ") ?: "" },
        lastName = lastName.ifBlank {
            displayName?.substringAfter(" ", "")?.takeIf { it.isNotBlank() } ?: ""
        },
        phone = phone,
        normalizedPhone = normalizedPhone,
        photoUri = photoPath,
        resolvedUserId = resolvedUserId,
        username = username,
        syncToPhone = syncToPhone,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
