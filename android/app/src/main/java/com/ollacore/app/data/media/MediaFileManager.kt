package com.ollacore.app.data.media

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * File helper per sir's split:
 * - Chat media bytes -> app-specific storage: files/chat_media/<roomId>/
 * - DB (MediaEntity) holds only filename/mime/size/localPath/downloadStatus.
 * - Shared storage (MediaStore gallery) ONLY on explicit user save.
 */
class MediaFileManager(private val context: Context) {

    fun dirForRoom(roomId: String): File {
        val safe = roomId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64).ifBlank { "misc" }
        return File(File(context.filesDir, "chat_media"), safe).apply { mkdirs() }
    }

    fun fileForAttachment(roomId: String, attachmentId: String, filename: String?): File {
        val safeName = (filename?.takeLast(80) ?: attachmentId).replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(dirForRoom(roomId), "${attachmentId}_$safeName")
    }

    suspend fun saveBytes(roomId: String, attachmentId: String, filename: String?, bytes: ByteArray): File =
        withContext(Dispatchers.IO) {
            val f = fileForAttachment(roomId, attachmentId, filename)
            f.writeBytes(bytes)
            f
        }

    fun resolve(path: String?): File? =
        if (path.isNullOrBlank()) null else File(path).takeIf { it.exists() }

    /**
     * Explicit "Save to gallery" only. Returns the MediaStore URI string.
     * Images/video -> Pictures/Movies(Ollacore); audio -> Music; else Downloads.
     */
    suspend fun copyToGallery(source: File, mime: String?, displayName: String): String? =
        withContext(Dispatchers.IO) {
            if (!source.exists()) return@withContext null
            val collection = when {
                mime?.startsWith("image/") == true ->
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                mime?.startsWith("video/") == true ->
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                mime?.startsWith("audio/") == true ->
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else -> if (Build.VERSION.SDK_INT >= 29)
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Files.getContentUri("external")
            }
            val subdir = when {
                mime?.startsWith("image/") == true -> "${Environment.DIRECTORY_PICTURES}/Ollacore"
                mime?.startsWith("video/") == true -> "${Environment.DIRECTORY_MOVIES}/Ollacore"
                mime?.startsWith("audio/") == true -> "${Environment.DIRECTORY_MUSIC}/Ollacore"
                else -> "${Environment.DIRECTORY_DOWNLOADS}/Ollacore"
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime ?: "application/octet-stream")
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, subdir)
            }
            val uri = context.contentResolver.insert(collection, values) ?: return@withContext null
            context.contentResolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            }
            uri.toString()
        }
}
