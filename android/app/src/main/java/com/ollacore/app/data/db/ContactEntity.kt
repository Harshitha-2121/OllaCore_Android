package com.ollacore.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local + directory contact row. Replaces LocalContactsStore JSON blob
 * with a queryable table (search by phone/name).
 */
@Entity(tableName = "contacts", indices = [Index("normalizedPhone"), Index("displayName")])
data class ContactEntity(
    @PrimaryKey val userId: String,
    val phone: String = "",
    val normalizedPhone: String = "",
    val displayName: String? = null,
    val firstName: String = "",
    val lastName: String = "",
    val about: String? = null,
    val avatarUrl: String? = null,
    /** Internal file path of the contact photo (was LocalContact.photoUri). */
    val photoPath: String? = null,
    val username: String? = null,
    val syncToPhone: Boolean = true,
    val localContactId: String? = null,
    val resolvedUserId: String? = null,
    val resolvedAt: Long = 0L,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)
