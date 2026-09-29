package com.ollacore.app.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ollacore.app.OllacoreApp
import com.ollacore.app.data.model.MessageResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

data class StarredRow(
    val id: String,
    val preview: String,
    /** "You" for own messages, else the sender's display name. */
    val senderLabel: String,
    val timestamp: String,
    val kind: String
)

data class StarredUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val rows: List<StarredRow> = emptyList()
)

/** Preview text for a starred row (text/caption/filename fallback). */
fun starredPreview(msg: MessageResponse): String {
    fun s(key: String): String? = try {
        msg.body[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }
    return s("text") ?: s("filename") ?: s("caption")
        ?: when (msg.kind.lowercase()) {
            "image" -> "Photo"
            "video" -> "Video"
            "audio" -> "Voice message"
            else -> "Shared file"
        }
}

/** ISO instant -> "HH:mm" local; falls back to the raw string. Pure + tested. */
fun shortTimeOf(iso: String): String = try {
    val instant = java.time.Instant.parse(iso)
    val local = instant.atZone(java.time.ZoneId.systemDefault())
    "%02d:%02d".format(local.hour, local.minute)
} catch (_: Exception) {
    iso.take(16)
}

/**
 * Starred-message browser: persisted star ids + full history scan, real
 * previews with sender + time. Tap returns the message id so the caller
 * can jump the chat straight to it.
 */
class StarredMessagesViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as OllacoreApp).container
    private val chatRepo = container.chatRepository
    private val directoryRepo = container.directoryRepository
    private val sessionStore = container.sessionStore

    private val _uiState = MutableStateFlow(StarredUiState())
    val uiState: StateFlow<StarredUiState> = _uiState.asStateFlow()

    fun load(roomId: String) {
        _uiState.update { StarredUiState(isLoading = true) }
        viewModelScope.launch {
            val token = sessionStore.sessionToken.first() ?: run {
                _uiState.update { it.copy(isLoading = false, error = "Session expired. Please log in again.") }
                return@launch
            }
            val me = sessionStore.userId.first() ?: ""
            val starred = runCatching { container.chatPrefsStore.getStarredIds(roomId) }.getOrElse { emptySet() }
            if (starred.isEmpty()) {
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            val roomToken = runCatching {
                directoryRepo.getRoomToken(token, roomId, "android-star-${UUID.randomUUID()}")
                    .getOrThrow().accessToken
            }.getOrElse { e ->
                _uiState.update { it.copy(isLoading = false, error = e.message) }
                return@launch
            }
            val names = runCatching {
                withContext(Dispatchers.IO) {
                    chatRepo.getParticipants(roomToken, roomId).getOrThrow().participants.associate {
                        it.principalId to (it.displayName?.ifBlank { null } ?: it.phone?.ifBlank { null } ?: it.principalId.take(8))
                    }
                }
            }.getOrElse { emptyMap() }
            val found = runCatching {
                withContext(Dispatchers.IO) {
                    val out = mutableListOf<MessageResponse>()
                    var before: Int? = Int.MAX_VALUE
                    for (i in 0 until 6) {
                        val page = chatRepo.listMessages(roomToken, roomId, beforeSeq = before, limit = 50).getOrThrow()
                        out += page.messages
                        if (!page.hasMore || page.messages.isEmpty()) break
                        before = page.messages.minOf { it.eventSeq }
                    }
                    out.filter { it.id in starred }.sortedBy { it.eventSeq }
                }
            }
            found.onSuccess { msgs ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        rows = msgs.map { m ->
                            StarredRow(
                                id = m.id,
                                preview = starredPreview(m),
                                senderLabel = if (m.senderId == me && me.isNotBlank()) "You" else names[m.senderId] ?: m.senderId.take(8),
                                timestamp = shortTimeOf(m.createdAt),
                                kind = m.kind
                            )
                        }
                    )
                }
            }.onFailure { e ->
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }
}
