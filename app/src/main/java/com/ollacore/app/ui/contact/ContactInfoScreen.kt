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
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ollacore.app.ui.theme.BrandAvatar

/**
 * 1-to-1 contact panel. One continuous scroll, same section order as the
 * reference: header (photo/name/phone/Voice/Video/Search) -> About ->
 * media strip -> options (starred/mute/disappearing/privacy/encryption) ->
 * groups in common -> management block (favourites/list/clear/block/
 * report/delete). Every color comes from the app theme; destructive rows
 * use the semantic error color. All rows are real state (alias/mute/stars/
 * favourites/lists/block/clear/report persist; disappearing + privacy are
 * honest backend-gated placeholders like the rest of the app).
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
    onOpenDocument: (String, String, String) -> Unit,
    // ── New: options / groups / management wiring ──
    onOpenMediaBrowser: () -> Unit = {},
    onOpenStarred: () -> Unit = {},
    onOpenGroup: (String) -> Unit = {},
    onToggleFavourite: () -> Unit = {},
    onCreateList: (String, (Boolean) -> Unit) -> Unit = { _, done -> done(false) },
    onToggleListMember: (String, Boolean) -> Unit = { _, _ -> },
    onClearChat: (() -> Unit) -> Unit = { done -> done() },
    onToggleBlock: () -> Unit = {},
    onSubmitReport: (String, () -> Unit) -> Unit = { _, done -> done() },
    onDeleteChat: ((Boolean) -> Unit) -> Unit = { done -> done(false) },
    onClearOpError: () -> Unit = {}
) {
    var showRename by remember { mutableStateOf(false) }
    var showDisappearing by remember { mutableStateOf(false) }
    var showEncryption by remember { mutableStateOf(false) }
    var privacyExpanded by remember { mutableStateOf(false) }
    // Lists flow: intro -> choose -> create.
    var showListIntro by remember { mutableStateOf(false) }
    var showChooseList by remember { mutableStateOf(false) }
    var showCreateList by remember { mutableStateOf(false) }
    var creatingList by remember { mutableStateOf(false) }
    var createListError by remember { mutableStateOf<String?>(null) }
    // Destructive confirmations.
    var showClearConfirm by remember { mutableStateOf(false) }
    var showBlockConfirm by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var busyOp by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    val displayName = uiState.alias?.ifBlank { null }
        ?: uiState.peerName.ifBlank { uiState.peerPhone.ifBlank { "Unknown" } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contact info") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
                actions = {
                    IconButton(onClick = { showRename = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit contact")
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
            // ── Header: photo, name, phone, Voice/Video/Search pills ──
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BrandAvatar(
                    name = displayName,
                    size = 120.dp,
                    labelStyle = MaterialTheme.typography.headlineLarge
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    displayName,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                if (uiState.peerPhone.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        uiState.peerPhone,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    HeaderPill(icon = Icons.Default.Call, label = "Voice", onClick = onVoiceCall)
                    HeaderPill(icon = Icons.Default.Videocam, label = "Video", onClick = onVideoCall)
                    HeaderPill(
                        icon = Icons.Default.Search,
                        label = "Search",
                        onClick = { uiState.roomToken?.let(onSearchChat) }
                    )
                }
            }

            // ── About (only when known; no directory endpoint returns peer
            // about, so an unknown value hides the section, never fakes it).
            uiState.peerAbout?.takeIf { it.isNotBlank() }?.let { about ->
                SectionLabel("About", Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
                Text(
                    about,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(top = 12.dp))

            // ── Media, links and docs ──
            val totalAttachments = uiState.mediaCount + uiState.docCount
            OptionRow(
                icon = Icons.Default.PermMedia,
                title = "Media, links and docs",
                trailing = {
                    if (totalAttachments > 0) {
                        Text(
                            totalAttachments.toString(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                onClick = onOpenMediaBrowser
            )
            if (uiState.thumbs.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
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
            } else {
                Text(
                    "No shared media in recent history.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            HorizontalDivider()

            // ── Starred messages ──
            OptionRow(
                icon = Icons.Default.StarBorder,
                title = "Starred messages",
                trailing = {
                    if (uiState.starredCount > 0) {
                        Text(
                            uiState.starredCount.toString(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                onClick = onOpenStarred
            )
            HorizontalDivider()

            // ── Mute ──
            OptionRow(
                icon = Icons.Default.Notifications,
                title = "Mute notifications",
                trailing = { Switch(checked = muted, onCheckedChange = onToggleMute) },
                onClick = { onToggleMute(!muted) }
            )
            HorizontalDivider()

            // ── Disappearing (backend TTL required - honest dialog) ──
            OptionRow(
                icon = Icons.Default.Timer,
                title = "Disappearing messages",
                subtitle = "Off",
                onClick = { showDisappearing = true }
            )
            HorizontalDivider()

            // ── Advanced privacy (expandable; server-owned toggles disabled) ──
            OptionRow(
                icon = Icons.Default.PrivacyTip,
                title = "Advanced chat privacy",
                subtitle = "Off",
                trailing = {
                    Icon(
                        if (privacyExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (privacyExpanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                onClick = { privacyExpanded = !privacyExpanded }
            )
            if (privacyExpanded) {
                Column(modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Read receipts", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Always on - no backend setting exists yet",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = true, enabled = false, onCheckedChange = {})
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Last seen", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Needs the backend privacy store",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = false, enabled = false, onCheckedChange = {})
                    }
                }
            }
            HorizontalDivider()

            // ── Encryption ──
            OptionRow(
                icon = Icons.Default.Lock,
                title = "Encryption",
                subtitle = "Messages are end-to-end encrypted. Click to verify.",
                onClick = { showEncryption = true }
            )
            HorizontalDivider()

            // ── Groups in common ──
            SectionLabel(
                if (uiState.groupsInCommon.isEmpty() && !uiState.groupsLoading) "No groups in common"
                else "${uiState.groupsInCommon.size} groups in common",
                Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
            )
            if (uiState.groupsLoading) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                }
            } else {
                uiState.groupsInCommon.forEach { group ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenGroup(group.roomId) }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        BrandAvatar(name = group.name, size = 48.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                group.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                group.memberPreview,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

            // ── Management block (no inter-dividers, destructive in red) ──
            val danger = MaterialTheme.colorScheme.error
            ManageRow(
                icon = if (uiState.isFavourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                title = if (uiState.isFavourite) "Remove from favourites" else "Add to favourites",
                onClick = onToggleFavourite
            )
            ManageRow(
                icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                title = "Add to list",
                onClick = { showListIntro = true }
            )
            ManageRow(
                icon = Icons.Default.RemoveCircleOutline,
                title = "Clear chat",
                color = danger,
                busy = busyOp == "clear",
                onClick = { showClearConfirm = true }
            )
            ManageRow(
                icon = Icons.Default.Block,
                title = if (uiState.isBlocked) "Unblock $displayName" else "Block $displayName",
                color = danger,
                onClick = { showBlockConfirm = true }
            )
            ManageRow(
                icon = Icons.Default.ThumbDown,
                title = "Report $displayName",
                color = danger,
                onClick = { showReportDialog = true }
            )
            ManageRow(
                icon = Icons.Default.DeleteOutline,
                title = "Delete chat",
                color = danger,
                busy = busyOp == "delete",
                onClick = { showDeleteConfirm = true }
            )
            Spacer(modifier = Modifier.height(24.dp))

            // Management-op errors surface inline, never silently.
            uiState.opError?.let { err ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onClearOpError) { Text("Dismiss") }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // ── Dialogs & sheets ──
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

    if (showListIntro) {
        AlertDialog(
            onDismissRequest = { showListIntro = false },
            text = {
                ListIntroContent(
                    onContinue = { showListIntro = false; showChooseList = true },
                    onDismiss = { showListIntro = false }
                )
            },
            confirmButton = {}
        )
    }

    if (showChooseList) {
        ChooseListSheet(
            allLists = uiState.allLists,
            memberOfLists = uiState.memberOfLists,
            onToggleMember = { name, member -> onToggleListMember(name, member) },
            onCreateNew = { showChooseList = false; createListError = null; showCreateList = true },
            onDismiss = { showChooseList = false }
        )
    }

    if (showCreateList) {
        CreateListDialog(
            existingNames = uiState.allLists.keys,
            creating = creatingList,
            createError = createListError,
            onCreate = { name ->
                creatingList = true
                createListError = null
                onCreateList(name) { ok ->
                    creatingList = false
                    if (ok) showCreateList = false
                    else createListError = "Couldn't create the list. Try again."
                }
            },
            onDismiss = { if (!creatingList) showCreateList = false }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear chat?") },
            text = { Text("Messages on this device will be hidden. The conversation and contact stay. New messages still arrive.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    busyOp = "clear"
                    onClearChat { busyOp = null }
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") } }
        )
    }

    if (showBlockConfirm) {
        val blocked = uiState.isBlocked
        AlertDialog(
            onDismissRequest = { showBlockConfirm = false },
            title = { Text(if (blocked) "Unblock $displayName?" else "Block $displayName?") },
            text = {
                Text(
                    if (blocked) "They will be able to message and call you again."
                    else "They won't be able to message or call you. This stays on this device."
                )
            },
            confirmButton = {
                TextButton(onClick = { showBlockConfirm = false; onToggleBlock() }) {
                    Text(if (blocked) "Unblock" else "Block", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showBlockConfirm = false }) { Text("Cancel") } }
        )
    }

    if (showReportDialog) {
        var reason by remember { mutableStateOf("") }
        var sending by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!sending) showReportDialog = false },
            title = { Text("Report $displayName?") },
            text = {
                Column {
                    Text("Tell us what happened. Reports are stored on this device until the backend report endpoint lands.")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("Reason (optional)") },
                        enabled = !sending,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !sending,
                    onClick = {
                        sending = true
                        onSubmitReport(reason.trim()) { sending = false; showReportDialog = false }
                    }
                ) { Text("Report", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(enabled = !sending, onClick = { showReportDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete chat?") },
            text = { Text("This removes the conversation from your chat list on this device. The contact stays.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    busyOp = "delete"
                    onDeleteChat { busyOp = null }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
        )
    }
}

/** Small gray section caption (About / groups header). */
@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/** Icon + title + optional subtitle/count/trailing, full-width tappable row. */
@Composable
private fun OptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing?.invoke()
    }
}

/** Management-block row: same geometry as OptionRow, optional danger tint. */
@Composable
private fun ManageRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    busy: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.5.dp,
                color = color
            )
        } else {
            Icon(icon, contentDescription = null, tint = color)
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/** Pill action (Voice/Video/Search): tonal rounded button + label beneath. */
@Composable
private fun HeaderPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
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
            .size(88.dp)
            .clip(RoundedCornerShape(12.dp))
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
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
        }
        // Duration badge (video/voice), bottom-end like the reference.
        thumb.durationMs?.takeIf { it > 0 }?.let { ms ->
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

/** 36_000ms -> "0:36". Pure formatting, unit-testable. */
fun formatShortDuration(ms: Long): String {
    val totalSec = (ms / 1000).toInt().coerceAtLeast(0)
    return "${totalSec / 60}:${(totalSec % 60).toString().padStart(2, '0')}"
}
