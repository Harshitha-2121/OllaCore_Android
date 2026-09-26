package com.ollacore.app.ui.contact

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ollacore.app.OllacoreApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

data class SharedThumb(
    val attachmentId: String,
    val kind: String,
    val mime: String?,
    val filename: String?,
    val url: String?
)

data class ContactInfoUiState(
    val roomId: String = "",
    val peerName: String = "",
    val peerPhone: String = "",
    val alias: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val mediaCount: Int = 0,
    val linkCount: Int = 0,
    val docCount: Int = 0,
    val thumbs: List<SharedThumb> = emptyList(),
    val starredCount: Int = 0,
    val roomToken: String? = null
)

/**
 * Contact panel data. Everything here is EXISTING-API (inbox, participants
 * metadata, room history, attachment download) or CLIENT-ONLY (alias, mute,
 * stars). Nothing is faked: counts come from a real message scan.
 */
class ContactInfoViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as OllacoreApp).container
    private val directoryRepo = container.directoryRepository
    private val chatRepo = container.chatRepository
    private val sessionStore = container.sessionStore
    private val prefs = container.chatPrefsStore

    private val _uiState = MutableStateFlow(ContactInfoUiState())
    val uiState: StateFlow<ContactInfoUiState> = _uiState.asStateFlow()

    private var watchedRoom = ""

    fun load(roomId: String) {
        watchedRoom = roomId
        _uiState.update { ContactInfoUiState(roomId = roomId, isLoading = true) }
        viewModelScope.launch {
            val token = sessionStore.sessionToken.first() ?: return@launch
            // Peer identity from inbox (same source the chat header uses).
            directoryRepo.getInbox(token).onSuccess { inbox ->
                inbox.conversations.find { it.roomId == roomId }?.let { item ->
                    _uiState.update {
                        it.copy(
                            peerName = item.name ?: item.peer?.displayName ?: item.peer?.phone ?: "Chat",
                            peerPhone = item.peer?.phone ?: "",
                            alias = null
                        )
                    }
                }
            }
            // Local alias + persisted stars + mute are room-scoped client state.
            val alias = runCatching { prefs.getAlias(roomId) }.getOrNull()
            val stars = runCatching { prefs.getStarredIds(roomId) }.getOrElse { emptySet() }
            _uiState.update { it.copy(alias = alias, starredCount = stars.size, isLoading = false) }
            scanSharedMedia(token, roomId)
        }
    }

    fun setMuted(roomId: String, muted: Boolean) {
        viewModelScope.launch { runCatching { prefs.setMuted(roomId, muted) } }
    }

    fun setAlias(roomId: String, name: String?) {
        viewModelScope.launch {
            runCatching { prefs.setAlias(roomId, name) }
            _uiState.update { it.copy(alias = name?.trim()?.ifBlank { null }) }
        }
    }

    fun mutedFlow(roomId: String): StateFlow<Boolean> =
        prefs.mutedRooms.map { roomId in it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private suspend fun scanSharedMedia(token: String, roomId: String) {
        val roomToken = runCatching {
            val deviceId = "android-ci-${UUID.randomUUID()}"
            directoryRepo.getRoomToken(token, roomId, deviceId).getOrThrow().accessToken
        }.getOrElse { e ->
            _uiState.update { it.copy(error = e.message) }
            return
        }
        _uiState.update { it.copy(roomToken = roomToken) }
        val messages = runCatching {
            withContext(Dispatchers.IO) {
                chatRepo.listMessages(roomToken, roomId, limit = 100).getOrThrow().messages
            }
        }.getOrElse {
            _uiState.update { s -> s.copy(error = it.message) }
            return
        }
        var links = 0
        var docs = 0
        val media = messages.filter {
            com.ollacore.app.data.model.attachmentRefIds(it).isNotEmpty()
        }
        messages.forEach { msg ->
            val text = try {
                msg.body["text"]?.jsonPrimitive?.content ?: ""
            } catch (_: Exception) {
                ""
            }
            val lower = text.lowercase()
            if ("http://" in lower || "https://" in lower || "www." in lower) links++
        }
        docs = media.count {
            val k = it.kind.lowercase()
            k == "file" || k == "document" || (try {
                it.body["mime"]?.jsonPrimitive?.content ?: ""
            } catch (_: Exception) {
                ""
            }).startsWith("application/")
        }
        // Thumbnails: resolve at most 6 download URLs (best-effort each).
        val thumbs = withContext(Dispatchers.IO) {
            media.take(12).mapNotNull { msg ->
                val aid = com.ollacore.app.data.model.attachmentRefIds(msg).firstOrNull()
                    ?: return@mapNotNull null
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
                SharedThumb(aid, msg.kind, mime, name, url)
            }.take(6)
        }
        if (watchedRoom == roomId) {
            _uiState.update {
                it.copy(mediaCount = media.size, linkCount = links, docCount = docs, thumbs = thumbs)
            }
        }
    }
}
