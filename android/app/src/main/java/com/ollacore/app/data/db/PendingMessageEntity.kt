package com.ollacore.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Outbox for offline sends. A row is created with SENDING status before
 * the network call; deleted on server ack, marked FAILED on error for
 * retry. Survives process death (unlike in-memory flags).
 */
@Entity(tableName = "pending_messages", indices = [Index("roomId")])
data class PendingMessageEntity(
    @PrimaryKey val clientMessageId: String,
    val roomId: String,
    val kind: String = "text",
    val bodyJson: String = "{}",
    val replyTo: String? = null,
    val attachmentLocalPathsCsv: String = "",
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAt: Long = 0L
)
