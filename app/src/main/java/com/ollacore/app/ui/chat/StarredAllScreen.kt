package com.ollacore.app.ui.chat

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ollacore.app.OllacoreApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.UUID

/** One starred message with its room context for the cross-chat browser. */
data class StarredAllEntry(
    val roomId: String,
    val roomName: String,
    val row: StarredRow
)

data class StarredAllUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val entries: List<StarredAllEntry> = emptyList()
)

/**
 * Cross-chat starred browser (reference overflow "Starred"): rooms with
 * locally starred ids are resolved against real history (same
 * token+scan pattern as the per-room browser), failures isolated per
 * room. Tap jumps the chat to the message; unstar removes it.
 */
class StarredAllViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as OllacoreApp).container
    private val chatRepo = container.chatRepository
    private val directoryRepo = container.directoryRepository
    private val sessionStore = container.sessionStore

    private val _uiState = MutableStateFlow(StarredAllUiState())
    val uiState: StateFlow<StarredAllUiState> = _uiState.asStateFlow()

    fun load() {
        _uiState.update { StarredAllUiState(isLoading = true) }
        viewModelScope.launch {
            val token = sessionStore.sessionToken.first() ?: run {
                _uiState.update { it.copy(isLoading = false, error = "Session expired. Please log in again.") }
                return@launch
            }
            val me = sessionStore.userId.first() ?: ""
            val inbox = runCatching {
                directoryRepo.getInbox(token).getOrThrow().conversations
            }.getOrElse { e ->
                _uiState.update { it.copy(isLoading = false, error = e.message) }
                return@launch
            }
            val roomNames = inbox.associate {
                it.roomId to (it.name ?: it.peer?.displayName ?: it.peer?.phone ?: "Chat")
            }
            // Cheap local pass first: only rooms with starred ids hit the network.
            val starredRooms = inbox.mapNotNull { item ->
                val ids = runCatching {
                    container.chatPrefsStore.getStarredIds(item.roomId)
                }.getOrElse { emptySet() }
                if (ids.isEmpty()) null else item.roomId to ids
            }.take(12)
            val out = mutableListOf<StarredAllEntry>()
            var firstError: String? = null
            supervisorScope {
                starredRooms.map { (roomId, ids) ->
                    launch {
                        runCatching {
                            val roomToken = directoryRepo
                                .getRoomToken(token, roomId, "android-starall-${UUID.randomUUID()}")
                                .getOrThrow().accessToken
                            val names = runCatching {
                                chatRepo.getParticipants(roomToken, roomId).getOrThrow().participants.associate {
                                    it.principalId to (it.displayName?.ifBlank { null }
                                        ?: it.phone?.ifBlank { null } ?: it.principalId.take(8))
                                }
                            }.getOrElse { emptyMap() }
                            val found = mutableListOf<com.ollacore.app.data.model.MessageResponse>()
                            var before: Int? = Int.MAX_VALUE
                            for (i in 0 until 4) {
                                val page = chatRepo.listMessages(roomToken, roomId, beforeSeq = before, limit = 50)
                                    .getOrThrow()
                                found += page.messages
                                if (!page.hasMore || page.messages.isEmpty()) break
                                before = page.messages.minOf { it.eventSeq }
                            }
                            val roomName = roomNames[roomId] ?: "Chat"
                            found.filter { it.id in ids }.forEach { m ->
                                synchronized(out) {
                                    out += StarredAllEntry(
                                        roomId = roomId,
                                        roomName = roomName,
                                        row = StarredRow(
                                            id = m.id,
                                            preview = starredPreview(m),
                                            senderLabel = if (m.senderId == me && me.isNotBlank()) "You"
                                            else names[m.senderId] ?: m.senderId.take(8),
                                            timestamp = shortTimeOf(m.createdAt),
                                            kind = m.kind
                                        )
                                    )
                                }
                            }
                        }.onFailure { e ->
                            synchronized(out) {
                                if (firstError == null) firstError = e.message
                            }
                        }
                    }
                }.forEach { it.join() }
            }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = if (out.isEmpty()) firstError else null,
                    entries = out.sortedBy { e -> e.row.timestamp }
                )
            }
        }
    }

    /** Unstar then drop the row locally (no reload needed). */
    fun unstar(roomId: String, messageId: String) {
        viewModelScope.launch {
            val stillStarred = runCatching {
                container.chatPrefsStore.toggleStar(roomId, messageId)
            }.getOrElse { true }
            if (!stillStarred) {
                _uiState.update { state ->
                    state.copy(entries = state.entries.filterNot {
                        it.roomId == roomId && it.row.id == messageId
                    })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarredAllScreen(
    uiState: StarredAllUiState,
    onBack: () -> Unit,
    onOpenMessage: (roomId: String, messageId: String) -> Unit,
    onUnstar: (roomId: String, messageId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Starred messages") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        modifier = modifier
    ) { padding ->
        when {
            uiState.isLoading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            uiState.error != null -> Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Couldn't load starred messages", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        uiState.error ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            uiState.entries.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("No starred messages", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Long-press any message and tap Star - everything starred across chats lands here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                val grouped = remember(uiState.entries) {
                    uiState.entries.groupBy { it.roomId }
                }
                LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                    grouped.forEach { (roomId, rows) ->
                        item(key = "header-$roomId") {
                            Text(
                                rows.first().roomName,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        items(rows, key = { "$roomId-${it.row.id}" }) { entry ->
                            ListItem(
                                headlineContent = {
                                    Text(entry.row.preview, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = {
                                    Text("${entry.row.senderLabel} • ${entry.row.timestamp}")
                                },
                                trailingContent = {
                                    IconButton(onClick = { onUnstar(entry.roomId, entry.row.id) }) {
                                        Icon(Icons.Default.Star, contentDescription = "Unstar")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { onOpenMessage(entry.roomId, entry.row.id) }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
