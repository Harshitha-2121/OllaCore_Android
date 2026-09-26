package com.ollacore.app.ui.contact

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ollacore.app.ui.theme.BrandAvatar

/**
 * 1-to-1 contact panel (WhatsApp-style sections, Ollacore-backed only).
 * Header + Voice/Video/Search, shared media strip with counts, starred
 * count, enforced mute, and honest backend-gated rows (disappearing,
 * advanced privacy) plus a real E2EE explainer. Peer identity comes from
 * the inbox data the chat already holds; rename is a local alias.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactInfoScreen(
    uiState: ContactInfoUiState,
    muted: Boolean,
    onBack: () -> Unit,
    onVoiceCall: () -> Unit,
    onVideoCall: () -> Unit,
    onSearchChat: (String) -> Unit,
    onToggleMute: (Boolean) -> Unit,
    onRename: (String?) -> Unit,
    onBackToChat: () -> Unit,
    onOpenImage: (String) -> Unit,
    onOpenDocument: (String, String, String) -> Unit
) {
    var showRename by remember { mutableStateOf(false) }
    var showDisappearing by remember { mutableStateOf(false) }
    var showEncryption by remember { mutableStateOf(false) }
    var privacyExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val displayName = uiState.alias?.ifBlank { null }
        ?: uiState.peerName.ifBlank { uiState.peerPhone.ifBlank { "Unknown" } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contact info") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading && uiState.peerName.isBlank()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Header: avatar + name + phone/about + rename pencil.
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BrandAvatar(name = displayName, size = 64.dp)
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        displayName,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    IconButton(onClick = { showRename = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Rename contact")
                    }
                }
                if (uiState.peerPhone.isNotBlank()) {
                    Text(
                        uiState.peerPhone,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                // Voice / Video / Search round buttons.
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    ContactHeaderAction(icon = Icons.Default.Call, label = "Voice", onClick = onVoiceCall)
                    ContactHeaderAction(icon = Icons.Default.Videocam, label = "Video", onClick = onVideoCall)
                    ContactHeaderAction(
                        icon = Icons.Default.Search,
                        label = "Search",
                        onClick = {
                            uiState.roomToken?.let(onSearchChat)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()

            // Media, links and docs with count + thumbnail strip.
            val totalAttachments = uiState.mediaCount + uiState.docCount
            ListItem(
                headlineContent = { Text("Media, links and docs") },
                leadingContent = {
                    Icon(Icons.Default.PermMedia, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                trailingContent = {
                    if (totalAttachments > 0) {
                        Text(
                            totalAttachments.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
            if (uiState.thumbs.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    items(uiState.thumbs, key = { it.attachmentId }) { thumb ->
                        MediaThumb(
                            thumb = thumb,
                            onOpenImage = onOpenImage,
                            onOpenDocument = onOpenDocument,
                            onOpenVideo = { url ->
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }
                            }
                        )
                    }
                }
                if (uiState.linkCount > 0) {
                    Text(
                        "${uiState.linkCount} shared link${if (uiState.linkCount == 1) "" else "s"} in recent history",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
            } else {
                Text(
                    "No shared media in recent history.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            HorizontalDivider()

            // Starred messages (count persisted locally; bodies live in chat).
            ListItem(
                headlineContent = { Text("Starred messages") },
                leadingContent = {
                    Icon(Icons.Default.StarBorder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                trailingContent = {
                    if (uiState.starredCount > 0) {
                        Text(
                            uiState.starredCount.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                modifier = Modifier.clickable(onClick = onBackToChat)
            )
            HorizontalDivider()

            // Mute (enforced client-side).
            ListItem(
                headlineContent = { Text("Mute notifications") },
                leadingContent = {
                    Icon(Icons.Default.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                trailingContent = {
                    Switch(checked = muted, onCheckedChange = onToggleMute)
                }
            )
            HorizontalDivider()

            // Disappearing messages (backend TTL required - honest dialog).
            ListItem(
                headlineContent = { Text("Disappearing messages") },
                supportingContent = { Text("Off") },
                leadingContent = {
                    Icon(Icons.Default.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                modifier = Modifier.clickable(onClick = { showDisappearing = true })
            )
            HorizontalDivider()

            // Advanced chat privacy (expandable; server-owned toggles disabled).
            ListItem(
                headlineContent = { Text("Advanced chat privacy") },
                supportingContent = { Text("Off") },
                leadingContent = {
                    Icon(Icons.Default.PrivacyTip, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                trailingContent = {
                    Icon(
                        if (privacyExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (privacyExpanded) "Collapse" else "Expand"
                    )
                },
                modifier = Modifier.clickable { privacyExpanded = !privacyExpanded }
            )
            if (privacyExpanded) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                    ListItem(
                        headlineContent = { Text("Read receipts") },
                        supportingContent = { Text("Always on - no backend setting exists yet") },
                        trailingContent = { Switch(checked = true, enabled = false, onCheckedChange = {}) }
                    )
                    ListItem(
                        headlineContent = { Text("Last seen") },
                        supportingContent = { Text("Needs the backend privacy store") },
                        trailingContent = { Switch(checked = false, enabled = false, onCheckedChange = {}) }
                    )
                }
            }
            HorizontalDivider()

            // Encryption (real local explainer).
            ListItem(
                headlineContent = { Text("Encryption") },
                supportingContent = { Text("Messages are end-to-end encrypted. Click to verify.") },
                leadingContent = {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                modifier = Modifier.clickable(onClick = { showEncryption = true })
            )
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showRename) {
        var v by remember(uiState.alias, uiState.peerName) {
            mutableStateOf(uiState.alias ?: "")
        }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename contact") },
            text = {
                Column {
                    OutlinedTextField(
                        value = v,
                        onValueChange = { v = it },
                        label = { Text("Display name") },
                        placeholder = { Text(uiState.peerName) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Stored only on this device.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(v.trim().ifBlank { null })
                    showRename = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } }
        )
    }

    if (showDisappearing) {
        AlertDialog(
            onDismissRequest = { showDisappearing = false },
            title = { Text("Disappearing messages") },
            text = {
                Text("Auto-deletion needs a server-side TTL so all devices stay in sync. The endpoint is spec'd in OLLACORE-BACKEND-SPEC.txt; until it lands, disappearing stays Off.")
            },
            confirmButton = { TextButton(onClick = { showDisappearing = false }) { Text("Got it") } }
        )
    }

    if (showEncryption) {
        AlertDialog(
            onDismissRequest = { showEncryption = false },
            title = { Text("End-to-end encrypted") },
            text = {
                Text("This chat uses MLS encryption with forward secrecy: removed members lose access to future messages, and each session rotation mints fresh epoch keys. Verify safety details with your contact out-of-band.")
            },
            confirmButton = { TextButton(onClick = { showEncryption = false }) { Text("Close") } }
        )
    }
}

@Composable
private fun ContactHeaderAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(52.dp)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun MediaThumb(
    thumb: SharedThumb,
    onOpenImage: (String) -> Unit,
    onOpenDocument: (String, String, String) -> Unit,
    onOpenVideo: (String) -> Unit
) {
    val mime = thumb.mime ?: ""
    val url = thumb.url
    val isImage = thumb.kind.equals("image", ignoreCase = true) || mime.startsWith("image/")
    val isVideo = thumb.kind.equals("video", ignoreCase = true) || mime.startsWith("video/")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable(enabled = url != null) {
                if (url == null) return@clickable
                when {
                    isImage -> onOpenImage(url)
                    isVideo -> onOpenVideo(url)
                    else -> onOpenDocument(url, thumb.filename ?: "document", mime.ifBlank { "application/octet-stream" })
                }
            }
    ) {
        if (isImage && url != null) {
            AsyncImage(
                model = url,
                contentDescription = "Shared photo",
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
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
