package com.ollacore.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Chat message row. bodyJson holds the serialized message body map;
 * large media bytes NEVER go here — see MediaEntity (localPath only).
 *
 * statusRank mirrors MessageStatus: 0 SENDING, 1 SENT, 2 DELIVERED,
 * 3 READ, -1 FAILED. Merged monotonically (never downgrades).
 */
@Entity(
    tableName = "messages",
    indices = [Index("roomId"), Index("eventSeq"), Index("clientMessageId")]
)
data class MessageEntity(
    @PrimaryKey val msgId: String,
    val roomId: String,
    val senderId: String = "",
    val kind: String = "text",
    val bodyJson: String = "{}",
    val eventSeq: Int = 0,
    val clientMessageId: String? = null,
    val replyTo: String? = null,
    val statusRank: Int = 1,
    val attachmentIdsCsv: String = "",
    val createdAt: String = "",
    val editedAt: String? = null
) {
    fun attachmentIds(): List<String> =
        attachmentIdsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}
