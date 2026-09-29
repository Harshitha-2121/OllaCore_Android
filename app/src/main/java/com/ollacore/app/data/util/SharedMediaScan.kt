package com.ollacore.app.data.util

import com.ollacore.app.data.model.MessageResponse
import com.ollacore.app.data.model.attachmentRefIds
import com.ollacore.app.data.model.voiceDurationMs
import com.ollacore.app.data.repository.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive

/**
 * Shared room-media scan (extracted from ContactInfoViewModel so Group Info
 * reuses the identical logic): last-100-messages sweep counting attachments,
 * links and documents + up to 6 downloadable thumbnails. Pure repository
 * I/O, no Android/UI dependencies.
 */
data class ScannedMediaItem(
    val attachmentId: String,
    val kind: String,
    val mime: String?,
    val filename: String?,
    val downloadUrl: String?,
    val durationMs: Long? = null
)

data class MediaScanResult(
    val mediaCount: Int,
    val linkCount: Int,
    val docCount: Int,
    val thumbs: List<ScannedMediaItem>
)

suspend fun scanSharedMedia(
    chatRepo: ChatRepository,
    roomToken: String,
    roomId: String,
    limit: Int = 100
): MediaScanResult = withContext(Dispatchers.IO) {
    val messages = chatRepo.listMessages(roomToken, roomId, limit = limit).getOrThrow().messages
    val media = messages.filter { attachmentRefIds(it).isNotEmpty() }
    var links = 0
    messages.forEach { msg ->
        val text = try {
            msg.body["text"]?.jsonPrimitive?.content ?: ""
        } catch (_: Exception) {
            ""
        }
        val lower = text.lowercase()
        if ("http://" in lower || "https://" in lower || "www." in lower) links++
    }
    val docs = media.count {
        val k = it.kind.lowercase()
        k == "file" || k == "document" || (try {
            it.body["mime"]?.jsonPrimitive?.content ?: ""
        } catch (_: Exception) {
            ""
        }).startsWith("application/")
    }
    val thumbs = media.take(12).mapNotNull { msg ->
        val aid = attachmentRefIds(msg).firstOrNull() ?: return@mapNotNull null
        val mime = try {
            msg.body["mime"]?.jsonPrimitive?.content
        } catch (_: Exception) {
            null
        }
        val name = try {
            msg.body["filename"]?.jsonPrimitive?.content
                ?: msg.body["text"]?.jsonPrimitive?.content
        } catch (_: Exception) {
            null
        }
        val url = runCatching {
            chatRepo.downloadAttachment(roomToken, roomId, aid).getOrThrow().downloadUrl
        }.getOrNull()
        val duration = voiceDurationMs(msg.body)
        ScannedMediaItem(aid, msg.kind, mime, name, url, duration)
    }.take(6)
    MediaScanResult(media.size, links, docs, thumbs)
}

/** Message preview snippet for starred lists (text or media-kind label). */
fun messagePreviewSnippet(msg: MessageResponse): String {
    val text = try {
        msg.body["text"]?.jsonPrimitive?.content
    } catch (_: Exception) {
        null
    }
    text?.takeIf { it.isNotBlank() }?.let {
        return if (it.length > 90) it.take(90) + "…" else it
    }
    if (msg.attachmentIds.isNotEmpty() || msg.kind.equals("image", ignoreCase = true)) return "📷 Photo"
    return when (msg.kind.lowercase()) {
        "video" -> "🎥 Video"
        "audio", "voice_note" -> "🎵 Voice message"
        "video_note" -> "🎥 Video message"
        "file" -> "📄 Document"
        "location" -> "📍 Location"
        "contact" -> "👤 Contact"
        else -> msg.kind
    }
}
