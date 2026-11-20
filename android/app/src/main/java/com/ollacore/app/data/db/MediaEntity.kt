package com.ollacore.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Media metadata only — file bytes live in app-specific storage
 * (MediaFileManager, files/chat_media/). Gallery copies go through
 * MediaStore only on explicit user save.
 *
 * downloadStatus: 0 pending, 1 downloading, 2 done, 3 failed.
 */
@Entity(tableName = "media", indices = [Index("roomId"), Index("messageId")])
data class MediaEntity(
    @PrimaryKey val attachmentId: String,
    val roomId: String = "",
    val messageId: String = "",
    val filename: String? = null,
    val mime: String? = null,
    val byteSize: Long? = null,
    val localPath: String? = null,
    val remoteUrl: String? = null,
    val urlExpiresAt: Long = 0L,
    val downloadStatus: Int = 0,
    val updatedAt: Long = 0L
)

object MediaDownloadStatus {
    const val PENDING = 0
    const val DOWNLOADING = 1
    const val DONE = 2
    const val FAILED = 3
}
