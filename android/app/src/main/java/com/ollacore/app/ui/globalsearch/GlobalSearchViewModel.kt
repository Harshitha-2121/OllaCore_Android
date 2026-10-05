package com.ollacore.app.ui.globalsearch

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ollacore.app.OllacoreApp
import com.ollacore.app.data.model.InboxItem
import com.ollacore.app.data.model.MessageResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

enum class SearchTab { CHATS, MESSAGES, MEDIA, IMAGES, VIDEOS, DOCUMENTS, LINKS, AUDIO }

data class GlobalHit(
    val roomId: String,
    val roomName: String,
    val message: MessageResponse
)

data class GlobalSearchUiState(
    val query: String = "",
    val tab: SearchTab = SearchTab.CHATS,
    val isSearching: Boolean = false,
    val searched: Boolean = false,
    val chats: List<InboxItem> = emptyList(),
    val hits: List<GlobalHit> = emptyList(),
    val roomsScanned: Int = 0,
    val error: String? = null
)

/**
 * Global Search (Android-side fan-out; no new Ollacore endpoint).
 * Chats filter locally from inbox; Messages fan out to per-room search
 * (capped room count, per-room failures tolerated); media tabs filter
 * those hits by kind/mime (no media-index endpoint exists).
 */
class GlobalSearchViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as OllacoreApp).container
    private val directoryRepo = container.directoryRepository
    private val chatRepo = container.chatRepository
    private val sessionStore = container.sessionStore

    private val _uiState = MutableStateFlow(GlobalSearchUiState())
    val uiState: StateFlow<GlobalSearchUiState> = _uiState.asStateFlow()

    private val prefsStore = container.chatPrefsStore
    val recentSearches: StateFlow<List<String>> = prefsStore.recentSearches.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    private var searchJob: Job? = null

    fun updateQuery(q: String) {
        _uiState.update { it.copy(query = q, error = null) }
        searchJob?.cancel()
        if (q.trim().length < 2) {
            _uiState.update { it.copy(chats = emptyList(), hits = emptyList(), searched = false, isSearching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(450)
            doSearch(q.trim())
        }
    }

    fun setTab(tab: SearchTab) {
        _uiState.update { it.copy(tab = tab) }
    }

    fun searchNow() {
        val q = _uiState.value.query.trim()
        if (q.length < 2) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch { doSearch(q) }
    }

    fun clear() {
        searchJob?.cancel()
        _uiState.update { GlobalSearchUiState() }
    }

    fun clearRecents() {
        viewModelScope.launch { runCatching { prefsStore.clearRecentSearches() } }
    }

    private suspend fun doSearch(q: String) {
        val token = sessionStore.sessionToken.first() ?: return
        runCatching { prefsStore.addRecentSearch(q) }
        _uiState.update { it.copy(isSearching = true, error = null) }
        directoryRepo.getInbox(token, limit = 30)
            .onSuccess { inbox ->
                val conversations = inbox.conversations
                val matchedChats = conversations.filter { item ->
                    val name = item.name ?: item.peer?.displayName ?: item.peer?.phone ?: ""
                    name.contains(q, ignoreCase = true) || (item.peer?.phone ?: "").contains(q)
                }
                val names = conversations.associate { it.roomId to (it.name ?: it.peer?.displayName ?: it.peer?.phone ?: "Chat") }
                // Fan out message search across recent rooms; skip rooms that fail (token/permission).
                val hits = supervisorScope {
                    conversations.take(MAX_ROOMS).map { item ->
                        async {
                            runCatching {
                                val rt = directoryRepo.getRoomToken(token, item.roomId, "android-gsearch-${UUID.randomUUID()}").getOrThrow()
                                val res = chatRepo.searchMessages(rt.accessToken, item.roomId, q, limit = 5).getOrThrow()
                                res.messages.map { GlobalHit(item.roomId, names[item.roomId] ?: "Chat", it) }
                            }.getOrElse { emptyList() }
                        }
                    }.awaitAll().flatten()
                }
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        searched = true,
                        chats = matchedChats,
                        hits = hits,
                        roomsScanned = minOf(conversations.size, MAX_ROOMS)
                    )
                }
            }
            .onFailure { e ->
                _uiState.update { it.copy(isSearching = false, searched = true, error = e.message) }
            }
    }

    companion object {
        private const val MAX_ROOMS = 12

        private fun strField(message: MessageResponse, key: String): String? {
            return try {
                message.body[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }

        private fun isImage(message: MessageResponse, kind: String, mime: String): Boolean {
            return kind == "image" || mime.startsWith("image/")
        }

        private fun isVideo(message: MessageResponse, kind: String, mime: String): Boolean {
            return kind == "video" || mime.startsWith("video/")
        }

        private fun hasLink(message: MessageResponse): Boolean {
            val text = strField(message, "text") ?: ""
            val lower = text.lowercase()
            return lower.contains("http://") || lower.contains("https://") || lower.contains("www.")
        }

        /** Media/link tabs classify aggregated hits (no media-index API; best-effort by kind/mime/text). */
        fun matchesTab(message: MessageResponse, tab: SearchTab): Boolean {
            if (tab == SearchTab.MESSAGES) return true
            val kind = message.kind.lowercase()
            val mime = strField(message, "mime")?.lowercase() ?: ""
            return when (tab) {
                SearchTab.MEDIA -> isImage(message, kind, mime) || isVideo(message, kind, mime)
                SearchTab.IMAGES -> isImage(message, kind, mime)
                SearchTab.VIDEOS -> isVideo(message, kind, mime)
                SearchTab.AUDIO -> kind == "audio" || mime.startsWith("audio/")
                SearchTab.DOCUMENTS -> kind == "file" || kind == "document" ||
                    mime.startsWith("application/") || mime.startsWith("text/")
                SearchTab.LINKS -> hasLink(message)
                else -> true
            }
        }

        fun captionFor(message: MessageResponse): String {
            strField(message, "text")?.let { return it }
            strField(message, "filename")?.let { return it }
            return when (message.kind.lowercase()) {
                "image" -> "📷 Photo"
                "video" -> "🎥 Video"
                "audio" -> "🎵 Audio"
                "file", "document" -> "📄 Document"
                "location" -> "📍 Location"
                else -> "Message"
            }
        }
    }
}
