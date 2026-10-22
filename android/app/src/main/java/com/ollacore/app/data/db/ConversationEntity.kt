package com.ollacore.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * WhatsApp-style conversation row. One per room_id.
 * DataStore (ChatPrefsStore) remains for UI prefs only; this is the
 * source of truth for list ordering, unread, mute/archive/pin.
 */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val roomId: String,
    val kind: String = "direct",
    val name: String? = null,
    val lastMessagePreview: String? = null,
    val lastMessageAt: String? = null,
    val lastEventSeq: Int = 0,
    val unreadCount: Int = 0,
    val muted: Boolean = false,
    val archived: Boolean = false,
    val pinned: Boolean = false,
    val peerUserId: String? = null,
    val peerPhone: String? = null,
    val updatedAt: Long = 0L
)
