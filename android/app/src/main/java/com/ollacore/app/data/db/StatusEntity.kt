package com.ollacore.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Status/Stories entity (WhatsApp-style).
 * One row per status item. TTL enforced by app (24h) + sweep job.
 */
@Entity(
    tableName = "statuses",
    indices = [Index("authorId"), Index("createdAt")]
)
data class StatusEntity(
    @PrimaryKey val statusId: String,
    val authorId: String,
    val authorName: String? = null,
    val authorAvatarUrl: String? = null,
    val kind: String = "text", // text, image, video
    val bodyJson: String = "{}",
    val mediaAttachmentId: String? = null,
    val mediaMimeType: String? = null,
    val mediaLocalPath: String? = null,
    val privacy: String = "contacts", // contacts, allow_list, deny_list
    val privacyUserIdsCsv: String = "",
    val createdAt: String = "",
    val expiresAt: String = "",
    val viewedUserIdsCsv: String = "",
    val isOwn: Boolean = false
) {
    fun privacyUserIds(): List<String> =
        privacyUserIdsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    
    fun viewedUserIds(): List<String> =
        viewedUserIdsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}