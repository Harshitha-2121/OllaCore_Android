package com.ollacore.app.ui.contact

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.ollacore.app.OllacoreApp
import com.ollacore.app.data.model.MessageResponse
import com.ollacore.app.data.model.attachmentRefIds
import com.ollacore.app.data.model.voiceDurationMs
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

data class BrowserItem(
    val attachmentId: String,
    val kind: String,
    val mime: String?,
    val filename: String?,
    val url: String?,
    val durationMs: Long?
)

data class MediaBrowserUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val items: List<BrowserItem> = emptyList()
)

/**
 * Full shared-media grid for one room: history scan (paged, capped) with
 * per-item download URLs resolved on load. Taps reuse the existing
 * image/document viewers; video opens the system player.
 */
class MediaBrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as OllacoreApp).container
    private val chatRepo = container.chatRepository
    private val directoryRepo = container.directoryRepository
    private val sessionStore = container.sessionStore

    private val _uiState = MutableStateFlow(MediaBrowserUiState())
    val uiState: StateFlow<MediaBrowserUiState> = _uiState.asStateFlow()

    fun load(roomId: String) {
        _uiState.update { MediaBrowserUiState(isLoading = true) }
        viewModelScope.launch {
            val token = sessionStore.sessionToken.first() ?: run {
                _uiState.update { it.copy(isLoading = false, error = "Session expired. Please log in again.") }
                return@launch
            }
            val roomToken = runCatching {
                directoryRepo.getRoomToken(token, roomId, "android-media-${UUID.randomUUID()}")
                    .getOrThrow().accessToken
            }.getOrElse { e ->
                _uiState.update { it.copy(isLoading = false, error = e.message) }
                return@launch
            }
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val msgs = mutableListOf<MessageResponse>()
                    var before: Int? = Int.MAX_VALUE
                    for (i in 0 until 6) {
                        val page = chatRepo.listMessages(roomToken, roomId, beforeSeq = before, limit = 50).getOrThrow()
                        msgs += page.messages
                        if (!page.hasMore || page.messages.isEmpty()) break
                        before = page.messages.minOf { it.eventSeq }
                    }
                    msgs.filter { attachmentRefIds(it).isNotEmpty() }
                        .sortedByDescending { it.eventSeq }
                        .take(60)
                        .map { msg ->
                            val aid = attachmentRefIds(msg).first()
                            fun s(key: String): String? = try {
                                msg.body[key]?.jsonPrimitive?.content
                            } catch (_: Exception) { null }
                            val url = runCatching {
                                chatRepo.downloadAttachment(roomToken, roomId, aid).getOrThrow().downloadUrl
                            }.getOrNull()
                            BrowserItem(
                                attachmentId = aid,
                                kind = msg.kind,
                                mime = s("mime"),
                                filename = s("filename") ?: s("text"),
                                url = url,
                                durationMs = voiceDurationMs(msg.body)
                            )
                        }
                }
            }
            result.onSuccess { items ->
                _uiState.update { it.copy(isLoading = false, items = items) }
            }.onFailure { e ->
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaBrowserScreen(
    uiState: MediaBrowserUiState,
    onBack: () -> Unit,
    onOpenImage: (String) -> Unit,
    onOpenDocument: (String, String, String) -> Unit
) {
    val context = LocalContext.current
    fun open(item: BrowserItem) {
        val url = item.url ?: return
        val mime = item.mime ?: ""
        when {
            item.kind.equals("image", ignoreCase = true) || mime.startsWith("image/") -> onOpenImage(url)
            item.kind.equals("video", ignoreCase = true) || mime.startsWith("video/") -> runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            else -> onOpenDocument(url, item.filename ?: "document", mime.ifBlank { "application/octet-stream" })
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Media, links and docs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        when {
            uiState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            uiState.error != null -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Couldn't load media.", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            uiState.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = onBack) { Text("Go back") }
                    }
                }
            }
            uiState.items.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "No shared media in this chat yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.items, key = { it.attachmentId }) { item ->
                        val mime = item.mime ?: ""
                        val isImage = item.kind.equals("image", ignoreCase = true) || mime.startsWith("image/")
                        val isVideo = item.kind.equals("video", ignoreCase = true) || mime.startsWith("video/")
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                .clickable(enabled = item.url != null) { open(item) }
                        ) {
                            if (isImage && item.url != null) {
                                AsyncImage(
                                    model = item.url,
                                    contentDescription = item.filename ?: "Shared photo",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(
                                    when {
                                        isVideo -> Icons.Default.PlayArrow
                                        mime.startsWith("audio/") -> Icons.Default.AudioFile
                                        else -> Icons.Default.Description
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            item.durationMs?.takeIf { it > 0 && (isVideo || mime.startsWith("audio/")) }?.let { ms ->
                                Surface(
                                    color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.65f),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                                ) {
                                    Text(
                                        formatShortDuration(ms),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = androidx.compose.ui.graphics.Color.White,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
