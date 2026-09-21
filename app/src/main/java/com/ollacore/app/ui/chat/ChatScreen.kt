package com.ollacore.app.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ollacore.app.data.model.MessageKinds
import com.ollacore.app.data.model.MessageResponse
import com.ollacore.app.ui.theme.BrandAvatar
import com.ollacore.app.ui.theme.BubbleRadiusOwn
import com.ollacore.app.ui.theme.BubbleRadiusPeer
import com.ollacore.app.ui.theme.OllaPrimaryBlue
import com.ollacore.app.ui.attachments.AttachmentPickerSheet
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    uiState: ChatUiState,
    roomId: String,
    onBack: () -> Unit,
    onSendMessage: (String) -> Unit,
    onEditMessage: (String, String) -> Unit,
    onDeleteMessage: (String) -> Unit,
    onAddReaction: (String, String) -> Unit,
    onReply: (MessageResponse?) -> Unit,
    onLoadMore: (Int) -> Unit,
    onTypingStarted: () -> Unit,
    onTypingStopped: () -> Unit,
    onSearch: () -> Unit = {},
    // --- Category 1 upgrades (WhatsApp-style) ---
    onForward: (MessageResponse) -> Unit = {},
    onCopy: (String) -> Unit = {},
    onToggleStar: (String) -> Unit = {},
    onToggleSelect: (String) -> Unit = {},
    onClearSelection: () -> Unit = {},
    onVoiceCall: () -> Unit = {},
    onVideoCall: () -> Unit = {},
    onProfileClick: () -> Unit = {},
    onPickAttachment: () -> Unit = {},
    onOpenCamera: () -> Unit = {},
    // ── Media & attachments (Category 1 - Ollacore API YES) ──
    attachmentUrls: Map<String, String> = emptyMap(),
    isUploading: Boolean = false,
    uploadProgress: Float = 0f,
    uploadingFilename: String? = null,
    uploadError: String? = null,
    onSendMedia: (File, String, String, String) -> Unit = { _, _, _, _ -> },
    onSendLocation: () -> Unit = {},
    onResolveUrl: (String) -> Unit = {},
    onClearUploadError: () -> Unit = {},
    // ── Message status ticks (Category 1 - ack/receipts already in Ollacore WS) ──
    onRetryMessage: (MessageResponse) -> Unit = {},
    // ── Voice recorder (spec 13; recordings send as kind=audio) ──
    onStartRecord: () -> Unit = {},
    onCancelRecord: () -> Unit = {},
    onSendRecord: () -> Unit = {},
    // ── In-app document viewer (spec 27) ──
    onOpenDocument: (String, String, String) -> Unit = { _, _, _ -> },
    // ── Fullscreen media viewer (spec 42 route) ──
    onOpenImage: (String) -> Unit = {},
    // ── Upload preview / cancel / retry (spec 12) ──
    onCancelUpload: () -> Unit = {},
    onRetryUpload: () -> Unit = {},
    onShareContact: (String, String) -> Unit = { _, _ -> },
    // ── Incoming calls (WS call.started; accept routes to the call screen) ──
    onAcceptCall: (String) -> Unit = {},
    onDeclineCall: () -> Unit = {},
    // ── Spec 34: full error retry rejoins the room ──
    onReconnect: () -> Unit = {}
) {
    var messageText by remember { mutableStateOf("") }
    var showActions by remember { mutableStateOf<MessageResponse?>(null) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showContactDialog by remember { mutableStateOf(false) }
    // Spec 12 preview: stage first, caption + send/cancel, then upload.
    var previewFile by remember { mutableStateOf<File?>(null) }
    var previewMime by remember { mutableStateOf("application/octet-stream") }
    var previewKind by remember { mutableStateOf(MessageKinds.FILE) }
    var previewCaption by remember { mutableStateOf("") }
    var recentMedia by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    fun stagePreview(file: File?, mime: String, kind: String) {
        if (file == null) return
        previewFile = file
        previewMime = mime
        previewKind = kind
        previewCaption = ""
    }

    val mediaPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) recentMedia = loadRecentImages(context)
    }
    fun ensureRecents() {
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES
        else android.Manifest.permission.READ_EXTERNAL_STORAGE
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, perm) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            recentMedia = loadRecentImages(context)
        } else {
            runCatching { mediaPermLauncher.launch(perm) }
        }
    }
    LaunchedEffect(showAttachmentSheet) {
        if (showAttachmentSheet) ensureRecents()
    }

    // ── "+" sheet pickers: Camera / Gallery / Document / Audio -> preview -> Ollacore upload ──
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            val mime = queryMimeType(context, uri) ?: "image/jpeg"
            val file = copyUriToCacheFile(context, uri) ?: return@rememberLauncherForActivityResult
            val kind = when {
                mime.startsWith("image/") -> MessageKinds.IMAGE
                mime.startsWith("video/") -> MessageKinds.VIDEO
                else -> MessageKinds.FILE
            }
            showAttachmentSheet = false
            stagePreview(file, mime, kind)
        }
    }
    val documentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val mime = queryMimeType(context, uri) ?: "application/octet-stream"
            val file = copyUriToCacheFile(context, uri) ?: return@rememberLauncherForActivityResult
            showAttachmentSheet = false
            stagePreview(file, mime, MessageKinds.FILE)
        }
    }
    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val mime = queryMimeType(context, uri) ?: "audio/mpeg"
            val file = copyUriToCacheFile(context, uri) ?: return@rememberLauncherForActivityResult
            showAttachmentSheet = false
            stagePreview(file, mime, MessageKinds.AUDIO)
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap: Bitmap? ->
        if (bitmap != null) {
            val file = bitmapToCacheFile(context, bitmap) ?: return@rememberLauncherForActivityResult
            showAttachmentSheet = false
            stagePreview(file, "image/jpeg", MessageKinds.IMAGE)
        }
    }

    val peerName = uiState.peerName ?: "Chat"
    val isOnline = uiState.onlineUsers.isNotEmpty()
    val isGroupChat = uiState.kind.equals("group", ignoreCase = true)
    val subtitle = when {
        uiState.typingUsers.isNotEmpty() -> "typing…"
        isGroupChat && uiState.participantCount > 0 -> "${uiState.participantCount} members"
        isOnline -> "online"
        uiState.isConnected -> "tap for info"
        else -> "offline"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(onClick = onProfileClick)
                    ) {
                        // Profile/photo (Ollacore API: avatar_url from UserProfile / Peer)
                        // TODO: Coil AsyncImage when avatarUrl != null
                        BrandAvatar(
                            name = peerName,
                            size = 40.dp,
                            showPresence = true,
                            isOnline = isOnline
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(peerName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (uiState.selectionMode) onClearSelection() else onBack()
                    }) {
                        Icon(
                            if (uiState.selectionMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (uiState.selectionMode) {
                        Text("${uiState.selectedIds.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 8.dp))
                        IconButton(onClick = {
                            // Forward selected - for now forward first selected
                            uiState.selectedIds.firstOrNull()?.let { id ->
                                uiState.messages.find { it.id == id }?.let { onForward(it) }
                            }
                        }) {
                            Icon(Icons.Default.Forward, contentDescription = "Forward")
                        }
                        IconButton(onClick = {
                            // Copy selected first
                            uiState.selectedIds.firstOrNull()?.let { id ->
                                val found = uiState.messages.find { it.id == id }
                                val txt = found?.let { bodyString(it.body, "text") } ?: ""
                                copyToClipboard(context, txt); onCopy(txt)
                            }
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                        }
                        IconButton(onClick = {
                            uiState.selectedIds.forEach { onToggleStar(it) }
                        }) {
                            Icon(Icons.Default.Star, contentDescription = "Star")
                        }
                        IconButton(onClick = {
                            uiState.selectedIds.firstOrNull()?.let { onDeleteMessage(it) }
                            onClearSelection()
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    } else {
                        IconButton(onClick = onVoiceCall) {
                            Icon(Icons.Default.Call, contentDescription = "Voice call")
                        }
                        IconButton(onClick = onVideoCall) {
                            Icon(Icons.Default.Videocam, contentDescription = "Video call")
                        }
                        var showMore by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { showMore = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                                DropdownMenuItem(
                                    text = { Text("View info") },
                                    onClick = { showMore = false; onProfileClick() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Search in chat") },
                                    onClick = { showMore = false; onSearch() }
                                )
                            }
                        }
                        if (!uiState.isConnected) {
                            Icon(Icons.Default.CloudOff, contentDescription = "Disconnected", tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(end = 4.dp))
                        }
                        if (uiState.isEncrypted) {
                            Icon(Icons.Default.Lock, contentDescription = "Encrypted", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Spec 35: subtle offline indicator (chat stays usable).
            val (chatOnline, chatWasOffline) = com.ollacore.app.ui.common.rememberConnectivity()
            com.ollacore.app.ui.common.OfflineBanner(isOnline = chatOnline, wasOffline = chatWasOffline)
            // Spec 34: friendly full error with rejoin retry (raw errors mapped, never shown).
            if (uiState.error != null && uiState.messages.isEmpty() && !uiState.isLoading) {
                com.ollacore.app.ui.common.ErrorState(
                    message = uiState.error,
                    onRetry = onReconnect,
                    onBack = onBack
                )
            }
            // Messages list over a very subtle dot pattern (spec 10 background).
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                ChatPatternBackground(modifier = Modifier.fillMaxSize())
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                items(uiState.messages, key = { it.id }) { message ->
                    val isStarred = message.id in uiState.starredIds
                    val isSelected = message.id in uiState.selectedIds
                    val isOwn = message.senderId == uiState.currentUserId ||
                        (uiState.currentUserId.isBlank() && (message.senderId == "self" || message.senderId.isBlank()))
                    val status = uiState.messageStatus[message.id]
                        ?: message.clientMessageId?.let { uiState.messageStatus[it] }
                        ?: if (isOwn) MessageStatus.SENT else null
                    // Spec 15: group peer messages show colored sender label + mini avatar.
                    val senderLabel = if (isGroupChat && !isOwn) {
                        uiState.participantNames[message.senderId] ?: message.senderId.take(12)
                    } else null
                    MessageBubble(
                        message = message,
                        isOwn = isOwn,
                        senderLabel = senderLabel,
                        showSender = senderLabel != null,
                        onOpenDocument = onOpenDocument,
                        onOpenImage = onOpenImage,
                        isStarred = isStarred,
                        isSelected = isSelected,
                        attachmentUrls = attachmentUrls,
                        onResolveUrl = onResolveUrl,
                        status = status,
                        onRetry = { onRetryMessage(message) },
                        onClick = {
                            if (uiState.selectionMode) onToggleSelect(message.id) else showActions = message
                        },
                        onLongClick = {
                            if (uiState.selectionMode) onToggleSelect(message.id) else showActions = message
                        }
                    )
                }
                }
            }

            // Upload progress (spec 12): progress + cancel.
            if (isUploading) {
                Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(progress = { uploadProgress }, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Uploading ${uploadingFilename ?: "file"}… ${(uploadProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onCancelUpload) { Text("Cancel") }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(progress = { uploadProgress }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            // Upload error: dismiss + retry (retries the retained file, spec 12).
            if (uploadError != null) {
                Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer) {
                    Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(uploadError, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = onRetryUpload) { Text("Retry") }
                        TextButton(onClick = onClearUploadError) { Text("Dismiss") }
                    }
                }
            }

            // Typing indicator (WhatsApp-style)
            if (uiState.typingUsers.isNotEmpty()) {
                Text(
                    "  typing…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // Reply preview
            uiState.replyTo?.let { reply ->
                val preview = bodyString(reply.body, "text") ?: ""
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Reply, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Replying to", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(preview, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { onReply(null) }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel reply")
                        }
                    }
                }
            }

            // Emoji picker (Category 1 - client-only, no backend)
            if (showEmojiPicker) {
                Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(8.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        listOf("😀","😂","❤️","👍","😮","😢","🙏","🎉").forEach { e ->
                            TextButton(onClick = { messageText += e }) { Text(e) }
                        }
                        IconButton(onClick = { showEmojiPicker = false }) { Icon(Icons.Default.Close, contentDescription = "Close") }
                    }
                }
            }

            // Voice recording bar: timer + live waveform + cancel/send.
            // (Slide-to-cancel gesture omitted; the ✕ button cancels explicitly.)
            val draft = uiState.voiceDraft
            if (draft.isRecording) {
                Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            formatVoiceTime(draft.elapsedMs),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        WaveformBars(
                            amplitudes = draft.amplitudes.takeLast(32),
                            barColor = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f).height(28.dp)
                        )
                        IconButton(onClick = onCancelRecord) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel recording")
                        }
                        FilledIconButton(onClick = onSendRecord) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send voice message")
                        }
                    }
                }
            }

            // Composer — WhatsApp-style: Emoji | Attachment | Camera | Text | Voice/Send
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 3.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showEmojiPicker = !showEmojiPicker }) {
                        Icon(Icons.Default.EmojiEmotions, contentDescription = "Emoji")
                    }
                    // "+" -> Camera / Gallery / Document / Audio / Location(API check)
                    IconButton(onClick = { showAttachmentSheet = true; onPickAttachment() }) {
                        Icon(Icons.Default.Add, contentDescription = "Attachment")
                    }
                    IconButton(onClick = { try { cameraLauncher.launch(null) } catch (_: Exception) { }; onOpenCamera() }) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = "Camera")
                    }
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = {
                            messageText = it
                            if (it.isNotEmpty()) onTypingStarted() else onTypingStopped()
                        },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Type a message…") },
                        maxLines = 4,
                        shape = RoundedCornerShape(28.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    if (messageText.isNotBlank()) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable {
                                    onSendMessage(messageText.trim())
                                    messageText = ""
                                    onTypingStopped()
                                }
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    } else {
                        // Spec 13: tap mic to record (no voice_note backend, so it sends
                        // as kind=audio with duration + waveform body fields).
                        FilledTonalIconButton(onClick = onStartRecord) {
                            Icon(Icons.Default.Mic, contentDescription = "Record voice message")
                        }
                    }
                }
            }
        }
    }

    // ── "+" sheet: Camera / Gallery / Document / Audio / Location(API check) ──
    if (showAttachmentSheet) {
        AttachmentPickerSheet(
            onDismiss = { showAttachmentSheet = false },
            onCamera = {
                showAttachmentSheet = false
                try { cameraLauncher.launch(null) } catch (_: Exception) { }
                onOpenCamera()
            },
            onGallery = {
                showAttachmentSheet = false
                try { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) } catch (_: Exception) { }
            },
            onDocument = {
                showAttachmentSheet = false
                try { documentLauncher.launch("*/*") } catch (_: Exception) { }
            },
            onAudio = {
                showAttachmentSheet = false
                try { audioLauncher.launch("audio/*") } catch (_: Exception) { }
            },
            onLocation = {
                showAttachmentSheet = false
                onSendLocation()
            },
            onContact = {
                showAttachmentSheet = false
                showContactDialog = true
            },
            recentMedia = recentMedia,
            onPickRecent = { uri ->
                val mime = queryMimeType(context, uri) ?: "image/jpeg"
                val file = copyUriToCacheFile(context, uri)
                val kind = if (mime.startsWith("video/")) MessageKinds.VIDEO else MessageKinds.IMAGE
                showAttachmentSheet = false
                stagePreview(file, mime, kind)
            }
        )
    }

    // Contact share (spec 12): group roster + DM peer; sends a readable text card.
    if (showContactDialog) {
        val entries = remember(uiState.participantNames, uiState.participantPhones, peerName) {
            buildList {
                if (!isGroupChat && peerName != "Chat") {
                    add(peerName to (uiState.peerPhone ?: ""))
                }
                uiState.participantNames.forEach { (id, name) ->
                    add(name to (uiState.participantPhones[id] ?: ""))
                }
            }.distinctBy { it.first }.take(30)
        }
        ContactShareDialog(
            entries = entries,
            onDismiss = { showContactDialog = false },
            onPick = { name, phone ->
                showContactDialog = false
                onShareContact(name, phone)
            }
        )
    }

    // Media preview (spec 12): rounded preview + caption + send/cancel.
    previewFile?.let { file ->
        MediaPreviewDialog(
            file = file,
            mime = previewMime,
            kind = previewKind,
            caption = previewCaption,
            onCaption = { previewCaption = it },
            onCancel = { previewFile = null },
            onSend = {
                onSendMedia(file, previewMime, previewKind, previewCaption)
                previewFile = null
            }
        )
    }

    // Incoming call dialog (ringing while browsing the chat).
    uiState.incomingCall?.let { call ->
        AlertDialog(
            onDismissRequest = { onDeclineCall() },
            icon = { Icon(Icons.Default.Call, contentDescription = null) },
            title = { Text("Incoming call") },
            text = { Text("${call.initiator.ifBlank { "Someone" }} is calling…") },
            confirmButton = {
                Button(onClick = { onAcceptCall(call.roomId) }) { Text("Accept") }
            },
            dismissButton = {
                TextButton(onClick = { onDeclineCall() }) { Text("Decline") }
            }
        )
    }

    // Message context menu: modern bottom sheet (spec 11) - React strip +
    // compact icon grid (Reply/Copy/Forward/Edit/Delete/Star/Select/More).
    showActions?.let { message ->
        val isOwnAction = message.senderId == uiState.currentUserId ||
            (uiState.currentUserId.isBlank() && (message.senderId == "self" || message.senderId.isBlank()))
        val starredAction = message.id in uiState.starredIds
        val statusAction = uiState.messageStatus[message.id]
            ?: message.clientMessageId?.let { uiState.messageStatus[it] }
        MessageActionsSheet(
            message = message,
            isOwn = isOwnAction,
            isStarred = starredAction,
            status = statusAction,
            onDismiss = { showActions = null },
            onReply = { onReply(message); showActions = null },
            onCopy = {
                val t = bodyString(message.body, "text") ?: ""
                copyToClipboard(context, t); onCopy(t); showActions = null
            },
            onForward = { onForward(message); showActions = null },
            onEdit = {
                val t = bodyString(message.body, "text") ?: ""
                onEditMessage(message.id, t); showActions = null
            },
            onDelete = { onDeleteMessage(message.id); showActions = null },
            onReact = { emoji -> onAddReaction(message.id, emoji); showActions = null },
            onStar = { onToggleStar(message.id); showActions = null },
            onSelect = { onToggleSelect(message.id); showActions = null }
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("message", text))
}

/**
 * Crash-hardening (spec 47): server-driven bodies can carry any JSON shape,
 * and jsonPrimitive THROWS on arrays/objects. Every read goes through here.
 */
private fun bodyString(body: Map<String, kotlinx.serialization.json.JsonElement>, key: String): String? {
    return try {
        body[key]?.jsonPrimitive?.content
    } catch (_: Exception) {
        null
    }
}

/**
 * Message context menu (spec 11): modern bottom sheet with a React strip
 * plus a compact icon grid - Reply/Copy/Forward/Edit/Delete/Star/Select/More.
 * Edit/Delete stay own-message only; Copy hides when there is no text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionsSheet(
    message: MessageResponse,
    isOwn: Boolean,
    isStarred: Boolean,
    status: MessageStatus?,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReact: (String) -> Unit,
    onStar: () -> Unit,
    onSelect: () -> Unit
) {
    var showMore by remember { mutableStateOf(false) }
    val text = bodyString(message.body, "text") ?: ""
    val isText = message.kind.equals(MessageKinds.TEXT, ignoreCase = true)

    val actions = buildList {
        add(Triple(Icons.Default.Reply, "Reply", onReply))
        if (text.isNotBlank()) add(Triple(Icons.Default.ContentCopy, "Copy", onCopy))
        add(Triple(Icons.Default.Forward, "Forward", onForward))
        add(
            Triple(
                if (isStarred) Icons.Default.Star else Icons.Default.StarBorder,
                if (isStarred) "Unstar" else "Star",
                onStar
            )
        )
        add(Triple(Icons.Default.Checklist, "Select", onSelect))
        if (isOwn && isText) add(Triple(Icons.Default.Edit, "Edit", onEdit))
        if (isOwn) add(Triple(Icons.Default.Delete, "Delete", onDelete))
        add(Triple(Icons.Default.MoreHoriz, "More", { showMore = !showMore }))
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 28.dp)) {
            // React strip.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                listOf("👍", "❤️", "😂", "😮", "😢", "😡").forEach { emoji ->
                    TextButton(onClick = { onReact(emoji) }) {
                        Text(emoji, style = MaterialTheme.typography.headlineMedium)
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            // Compact icon grid (never a huge menu: max 8 cells, 4 per row).
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .padding(horizontal = 8.dp),
                userScrollEnabled = false
            ) {
                items(actions.size) { index ->
                    val (icon, label, action) = actions[index]
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable(onClick = action)
                            .padding(vertical = 10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (label == "Delete") MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Icon(
                                    icon,
                                    contentDescription = label,
                                    tint = if (label == "Delete") MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            // More: message info (time, delivery state, edited, id).
            if (showMore) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    MessageInfoRow("Sent", messageTime(message.createdAt) ?: "—")
                    MessageInfoRow(
                        "Status",
                        when (status) {
                            MessageStatus.SENDING -> "Sending…"
                            MessageStatus.SENT -> "Sent"
                            MessageStatus.DELIVERED -> "Delivered"
                            MessageStatus.READ -> "Read"
                            MessageStatus.FAILED -> "Failed - use Retry in chat"
                            null -> if (isOwn) "Sent" else "Received"
                        }
                    )
                    if (message.editedAt != null) MessageInfoRow("Edited", "Yes")
                    MessageInfoRow("Type", message.kind)
                }
            }
        }
    }
}

@Composable
private fun MessageInfoRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: MessageResponse,
    isOwn: Boolean,
    isStarred: Boolean = false,
    isSelected: Boolean = false,
    attachmentUrls: Map<String, String> = emptyMap(),
    onResolveUrl: (String) -> Unit = {},
    status: MessageStatus? = null,
    onRetry: () -> Unit = {},
    senderLabel: String? = null,
    showSender: Boolean = false,
    onOpenDocument: (String, String, String) -> Unit = { _, _, _ -> },
    onOpenImage: (String) -> Unit = {},
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val alignment = if (isOwn) Alignment.CenterEnd else Alignment.CenterStart
    val shape = if (isOwn) BubbleRadiusOwn else BubbleRadiusPeer
    // Spec 10 + 31: outgoing = theme primary (adapts to Blue/Green/Purple/Dark);
    // incoming = white/light surface card with hairline border.
    val peerCard = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
    else MaterialTheme.colorScheme.surface
    val main = if (isOwn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val soft = if (isOwn) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f)
    else MaterialTheme.colorScheme.onSurfaceVariant

    // Spec 15: group peer messages carry a colored sender label + mini avatar.
    val bubbleBox: @Composable (Modifier) -> Unit = { boxModifier ->
        Box(
            modifier = boxModifier
                .clip(shape)
                .then(
                    if (isOwn) Modifier.background(MaterialTheme.colorScheme.primary)
                    else Modifier.background(peerCard)
                )
                .then(
                    if (!isOwn) Modifier.border(
                        androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        shape
                    ) else Modifier
                )
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides main
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    if (message.replyTo != null) {
                        Text("↩ Reply", style = MaterialTheme.typography.labelSmall, color = soft.copy(alpha = 0.9f))
                    }
                    // ── Media rendering (was text-only gap): image / video / doc / audio / location ──
                    MediaMessageContent(message = message, attachmentUrls = attachmentUrls, onResolveUrl = onResolveUrl, isOwn = isOwn, onGradient = isOwn, onOpenDocument = onOpenDocument, onOpenImage = onOpenImage)
                    BubbleFooter(message = message, isStarred = isStarred, isSelected = isSelected, status = status, isOwn = isOwn, onRetry = onRetry, onGradient = isOwn)
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxWidth().background(if (isSelected) MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent), contentAlignment = alignment) {
        if (showSender && !isOwn && senderLabel != null) {
            Column(modifier = Modifier.widthIn(max = 330.dp)) {
                Text(
                    senderLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = com.ollacore.app.ui.theme.avatarColorFor(senderLabel),
                    modifier = Modifier.padding(start = 38.dp, bottom = 2.dp)
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    BrandAvatar(name = senderLabel, size = 26.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    bubbleBox(Modifier.weight(1f, fill = false))
                }
            }
        } else {
            bubbleBox(Modifier.widthIn(max = 300.dp))
        }
    }
}

@Composable
private fun BubbleFooter(
    message: MessageResponse,
    isStarred: Boolean,
    isSelected: Boolean,
    status: MessageStatus? = null,
    isOwn: Boolean = false,
    onRetry: () -> Unit = {},
    onGradient: Boolean = false
) {
    val soft = if (onGradient) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.78f)
    else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp, bottom = 4.dp)) {
        if (message.editedAt != null) {
            Text("(edited)", style = MaterialTheme.typography.labelSmall, color = soft.copy(alpha = 0.65f))
            Spacer(modifier = Modifier.width(6.dp))
        }
        if (isStarred) {
            Icon(Icons.Default.Star, contentDescription = "Starred", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(6.dp))
        }
        if (isSelected) {
            Icon(Icons.Default.CheckCircle, contentDescription = "Selected", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(6.dp))
        }
        // ── Message metadata: time · edited · delivered/read ──
        messageTime(message.createdAt)?.let { stamp ->
            Text(stamp, style = MaterialTheme.typography.labelSmall, color = soft.copy(alpha = 0.8f))
            Spacer(modifier = Modifier.width(6.dp))
        }
        // ── Message status ticks (own bubbles only; driven by Ollacore ack + receipts) ──
        if (isOwn && status != null) {
            Spacer(modifier = Modifier.weight(1f))
            when (status) {
                MessageStatus.SENDING -> Icon(
                    Icons.Default.Schedule, contentDescription = "Sending",
                    modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                MessageStatus.SENT -> Icon(
                    Icons.Default.Check, contentDescription = "Sent",
                    modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                MessageStatus.DELIVERED -> Icon(
                    Icons.Default.DoneAll, contentDescription = "Delivered",
                    modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                MessageStatus.READ -> Icon(
                    Icons.Default.DoneAll, contentDescription = "Read",
                    modifier = Modifier.size(16.dp), tint = ReadBlue
                )
                MessageStatus.FAILED -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.WarningAmber, contentDescription = "Failed",
                        modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error
                    )
                    // Spec 43: full 48dp touch target (icon stays small).
                    IconButton(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, contentDescription = "Retry", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
    message.reactions?.takeIf { it.isNotEmpty() }?.let { reacts ->
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)) {
            reacts.forEach { (emoji, users) ->
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                    Text("$emoji ${users.size}", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun MediaMessageContent(
    message: MessageResponse,
    attachmentUrls: Map<String, String>,
    onResolveUrl: (String) -> Unit,
    isOwn: Boolean,
    onGradient: Boolean = false,
    onOpenDocument: (String, String, String) -> Unit = { _, _, _ -> },
    onOpenImage: (String) -> Unit = {}
) {
    val body = message.body
    val caption = bodyString(body, "text") ?: ""
    val mime = bodyString(body, "mime")
    val filename = bodyString(body, "filename")
    val byteSize = bodyString(body, "byte_size")?.toLongOrNull()
    val lat = bodyString(body, "lat")?.toDoubleOrNull()
    val lng = bodyString(body, "lng")?.toDoubleOrNull()
    val attachmentId = message.attachmentIds.firstOrNull()
    val kind = message.kind.lowercase()

    // Location bubble (backend/API check required for kind=location)
    if (kind == MessageKinds.LOCATION || (lat != null && lng != null)) {
        LocationBubble(lat = lat, lng = lng, label = caption, onGradient = onGradient)
        return
    }

    val isMedia = attachmentId != null || kind in setOf(MessageKinds.IMAGE, MessageKinds.VIDEO, MessageKinds.AUDIO, MessageKinds.FILE)
    if (!isMedia) {
        if (caption.isNotEmpty()) {
            Text(caption, modifier = Modifier.padding(4.dp))
        } else if (kind != MessageKinds.TEXT) {
            // voice_note / contact / future kinds have no backend yet: honest label, never blank.
            Text(
                when (kind) {
                    "voice_note" -> "🎵 Voice message (needs backend voice_note support)"
                    "video_note" -> "🎥 Video message (needs backend video_note support)"
                    "contact" -> "👤 Shared contact (needs backend support)"
                    "poll" -> "📊 Poll (needs backend support)"
                    "event" -> "📅 Event (needs backend support)"
                    "location" -> "📍 Location"
                    else -> "Unsupported message ($kind)"
                },
                style = MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.7f),
                modifier = Modifier.padding(4.dp)
            )
        }
        return
    }

    val effectiveMime = mime ?: when (kind) {
        MessageKinds.IMAGE -> "image/jpeg"
        MessageKinds.VIDEO -> "video/mp4"
        MessageKinds.AUDIO -> "audio/mpeg"
        else -> "application/octet-stream"
    }
    val url = attachmentId?.let { attachmentUrls[it] }
    LaunchedEffect(attachmentId) {
        if (attachmentId != null && url == null) onResolveUrl(attachmentId)
    }

    when {
        kind == MessageKinds.IMAGE || effectiveMime.startsWith("image/") -> {
            ImageBubbleContent(
                url = url,
                caption = caption,
                filename = filename ?: attachmentId,
                onOpen = { u -> onOpenImage(u) }
            )
        }
        kind == MessageKinds.VIDEO || effectiveMime.startsWith("video/") -> {
            VideoBubbleContent(url = url, caption = caption)
        }
        kind == MessageKinds.AUDIO || effectiveMime.startsWith("audio/") -> {
            val durationMs = body["duration_ms"]?.jsonPrimitive?.content?.toLongOrNull()
            val wave = body["waveform"]?.jsonArray?.mapNotNull {
                try { it.jsonPrimitive.content.toInt() } catch (_: Exception) { null }
            }?.take(48)
            AudioBubbleContent(url = url, filename = filename, onGradient = onGradient, durationMs = durationMs, waveform = wave)
        }
        else -> {
            val docName = filename ?: caption.ifBlank { attachmentId ?: "document" }
            DocumentBubbleContent(
                url = url,
                filename = docName,
                mime = effectiveMime,
                byteSize = byteSize,
                onResolveUrl = { if (attachmentId != null) onResolveUrl(attachmentId) },
                onGradient = onGradient,
                onOpen = { u -> onOpenDocument(u, docName, effectiveMime) }
            )
        }
    }
}

@Composable
private fun ImageBubbleContent(
    url: String?,
    caption: String,
    filename: String?,
    onOpen: (String) -> Unit = {}
) {
    Column {
        if (url != null) {
            AsyncImage(
                model = coil.request.ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    .size(900)
                    .crossfade(true)
                    .memoryCacheKey("thumb-$url")
                    .build(),
                contentDescription = "📷 Image: ${filename ?: "photo"}",
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .heightIn(min = 120.dp, max = 320.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onOpen(url) },
                contentScale = ContentScale.Crop
            )
        } else {
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp), modifier = Modifier.size(width = 220.dp, height = 140.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("📷 Loading image…", style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(4.dp))
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        if (caption.isNotBlank()) Text(caption, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(4.dp))
    }
}

@Composable
private fun VideoBubbleContent(url: String?, caption: String) {
    val context = LocalContext.current
    // Spec 14: thumbnail + duration via framework retriever (no new dep); premium tile.
    var thumb by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var durationMs by remember(url) { mutableStateOf<Long?>(null) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(url, HashMap())
                    durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    thumb = retriever.getFrameAtTime(1_000_000)
                } finally {
                    runCatching { retriever.release() }
                }
            }
        }
    }
    Column {
        Surface(
            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.85f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.size(width = 240.dp, height = 150.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().clickable(enabled = url != null) {
                if (url != null) {
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { }
                }
            }) {
                val bitmap = thumb
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "🎥 Video thumbnail",
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.55f), modifier = Modifier.size(52.dp)) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "🎥 Play video", tint = Color.White, modifier = Modifier.size(30.dp))
                            }
                        }
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (url == null) CircularProgressIndicator(modifier = Modifier.size(28.dp), color = MaterialTheme.colorScheme.surface)
                        else Icon(Icons.Default.PlayArrow, contentDescription = "🎥 Play video", modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.surface)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            if (url != null) "🎥 Tap to play" else "🎥 Preparing video…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.surface
                        )
                    }
                }
                // Duration badge (spec 14) + progress shimmer while resolving.
                val dur = durationMs
                if (dur != null && dur > 0) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Black.copy(alpha = 0.65f),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    ) {
                        Text(
                            formatVoiceTime(dur),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                if (url != null && bitmap == null && durationMs == null) {
                    LinearProgressIndicator(
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp)
                    )
                }
            }
        }
        if (caption.isNotBlank()) Text(caption, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(4.dp))
    }
}

@Composable
private fun DocumentBubbleContent(
    url: String?,
    filename: String,
    mime: String,
    byteSize: Long?,
    onResolveUrl: () -> Unit,
    onGradient: Boolean = false,
    onOpen: (String) -> Unit = {}
) {
    val soft = if (onGradient) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f)
    else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        // Tap opens the in-app viewer (spec 27); the viewer itself offers external open.
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.clickable(enabled = url != null) { url?.let(onOpen) }
    ) {
        Row(modifier = Modifier.padding(10.dp).widthIn(max = 260.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(if (mime.contains("pdf")) Icons.Default.PictureAsPdf else Icons.Default.InsertDriveFile, contentDescription = "📄 Document", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(filename, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text("${mime}${if (byteSize != null) " • ${formatBytes(byteSize)}" else ""}", style = MaterialTheme.typography.labelSmall, color = soft)
                if (url == null) {
                    TextButton(onClick = onResolveUrl, contentPadding = PaddingValues(0.dp)) { Text("Prepare download") }
                }
            }
            IconButton(onClick = {
                if (url != null) onOpen(url) else onResolveUrl()
            }) {
                Icon(Icons.Default.Download, contentDescription = "Open document")
            }
        }
    }
}

@Composable
private fun AudioBubbleContent(
    url: String?,
    filename: String?,
    onGradient: Boolean = false,
    durationMs: Long? = null,
    waveform: List<Int>? = null
) {
    val soft = if (onGradient) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f)
    else MaterialTheme.colorScheme.onSurfaceVariant
    var isPlaying by remember { mutableStateOf(false) }
    var isPreparing by remember { mutableStateOf(false) }
    var knownDurationMs by remember(durationMs) { mutableStateOf(durationMs) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(url) {
        onDispose { try { player?.release() } catch (_: Exception) { }; player = null }
    }
    // Spec 13 bubble: play button + waveform + duration + timestamp ticks (ticks live in footer).
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp)) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp).widthIn(max = 250.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(onClick = {
                if (url == null) return@FilledIconButton
                try {
                    if (isPlaying) {
                        player?.pause(); isPlaying = false
                    } else {
                        isPreparing = true
                        if (player == null) {
                            player = MediaPlayer().apply {
                                setDataSource(url)
                                setOnPreparedListener {
                                    if (knownDurationMs == null) knownDurationMs = it.duration.toLong()
                                    it.start(); isPlaying = true; isPreparing = false
                                }
                                setOnCompletionListener { isPlaying = false }
                                prepareAsync()
                            }
                        } else {
                            player?.start(); isPlaying = true; isPreparing = false
                        }
                    }
                } catch (_: Exception) { isPreparing = false }
            }, modifier = Modifier.size(40.dp)) {
                when {
                    isPreparing -> CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    isPlaying -> Icon(Icons.Default.Pause, contentDescription = "Pause audio")
                    else -> Icon(Icons.Default.PlayArrow, contentDescription = "🎵 Play audio")
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                WaveformBars(
                    amplitudes = waveform ?: emptyList(),
                    barColor = if (isPlaying) MaterialTheme.colorScheme.primary else soft,
                    modifier = Modifier.fillMaxWidth().height(26.dp)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        filename?.takeIf { it.isNotBlank() } ?: "🎵 Voice message",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = soft,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    val shownDuration = knownDurationMs
                    if (shownDuration != null && shownDuration > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(formatVoiceTime(shownDuration), style = MaterialTheme.typography.labelSmall, color = soft)
                    } else if (url == null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("…", style = MaterialTheme.typography.labelSmall, color = soft)
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationBubble(lat: Double?, lng: Double?, label: String, onGradient: Boolean = false) {
    val context = LocalContext.current
    val soft = if (onGradient) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f)
    else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(10.dp).widthIn(max = 250.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOn, contentDescription = "Location", tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (label.isNotBlank()) label else "📍 Shared location", style = MaterialTheme.typography.bodyMedium)
            }
            if (lat != null && lng != null) {
                Text("$lat, $lng", style = MaterialTheme.typography.labelSmall, color = soft)
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedButton(onClick = {
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng"))) } catch (_: Exception) { }
                }) { Text("Open in Maps") }
                Text("kind=location - backend/API check required", style = MaterialTheme.typography.labelSmall, color = soft)
            }
        }
    }
}

/** WhatsApp-style read blue for double ticks. */
private val ReadBlue = Color(0xFF53BDEB)

private fun formatVoiceTime(ms: Long): String {
    val totalSec = ms / 1000
    return String.format("%d:%02d", totalSec / 60, totalSec % 60)
}

/** Waveform bars (spec 13 bubble + recording animation). */
@Composable
private fun WaveformBars(
    amplitudes: List<Int>,
    barColor: Color,
    modifier: Modifier = Modifier,
    maxBars: Int = 32
) {
    val bars = remember(amplitudes) {
        if (amplitudes.isEmpty()) List(maxBars) { 8 }
        else amplitudes.takeLast(maxBars)
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        bars.forEach { amp ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight((amp.coerceIn(4, 100)) / 100f)
                    .clip(RoundedCornerShape(2.dp))
                    .background(barColor)
            )
        }
    }
}

/** Very subtle dot pattern for the chat background (spec 10 - no gradient). */
@Composable
private fun ChatPatternBackground(modifier: Modifier = Modifier) {
    val dot = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.055f)
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val step = 30.dp.toPx()
        val r = 1.6.dp.toPx()
        var y = step / 2
        var row = 0
        while (y < size.height) {
            var x = step / 2 + (if (row % 2 == 1) step / 2 else 0f)
            while (x < size.width) {
                drawCircle(dot, radius = r, center = androidx.compose.ui.geometry.Offset(x, y))
                x += step
            }
            y += step
            row++
        }
    }
}

/** Bubble timestamp HH:mm; null when the server timestamp is missing/unparseable. */
private fun messageTime(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        val instant = try {
            java.time.Instant.parse(raw)
        } catch (_: Exception) {
            val n = raw.toLong()
            java.time.Instant.ofEpochMilli(if (n < 1_000_000_000_000L) n * 1000 else n)
        }
        java.time.format.DateTimeFormatter.ofPattern("HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
            .format(instant)
    }.getOrNull()
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format("%.1f MB", mb)
}

private fun queryMimeType(context: Context, uri: Uri): String? {
    return try { context.contentResolver.getType(uri) } catch (_: Exception) { null }
}

/** Contact picker for sharing (spec 12): readable text card, no backend kind needed. */
@Composable
private fun ContactShareDialog(
    entries: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onPick: (String, String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share contact") },
        text = {
            if (entries.isEmpty()) {
                Text("No contacts available in this chat.", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(entries, key = { it.first }) { (name, phone) ->
                        ListItem(
                            headlineContent = { Text(name, style = MaterialTheme.typography.titleMedium) },
                            supportingContent = { if (phone.isNotBlank()) Text(phone) },
                            leadingContent = { BrandAvatar(name = name, size = 40.dp) },
                            modifier = Modifier.clickable { onPick(name, phone) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Media preview (spec 12): rounded corners, selection chip, caption, send/cancel. */
@Composable
private fun MediaPreviewDialog(
    file: File,
    mime: String,
    kind: String,
    caption: String,
    onCaption: (String) -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Send ${kind.lowercase().replaceFirstChar { it.uppercase() }}") },
        text = {
            Column {
                Box(contentAlignment = Alignment.TopEnd) {
                    if (mime.startsWith("image/")) {
                        coil.compose.AsyncImage(
                            // Spec 45: cap decode size (never full-res into a small view).
                            model = coil.request.ImageRequest.Builder(LocalContext.current)
                                .data(file)
                                .size(900)
                                .crossfade(true)
                                .build(),
                            contentDescription = "Preview",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp)
                                .clip(RoundedCornerShape(20.dp))
                        )
                    } else {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(20.dp)) {
                            Row(modifier = Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    when {
                                        mime.startsWith("video/") -> Icons.Default.Videocam
                                        mime.startsWith("audio/") -> Icons.Default.AudioFile
                                        else -> Icons.Default.Description
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(mime, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    // Selection indicator.
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(10.dp).size(26.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(Icons.Default.Check, contentDescription = "Selected", tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = caption,
                    onValueChange = onCaption,
                    placeholder = { Text("Add a caption…") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = onSend) { Text("Send") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

/** Recent device photos for the share sheet (needs READ_MEDIA_IMAGES grant). */
private fun loadRecentImages(context: Context): List<Uri> {
    return try {
        val out = mutableListOf<Uri>()
        val collection = if (android.os.Build.VERSION.SDK_INT >= 29) {
            android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL)
        } else {
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        context.contentResolver.query(
            collection,
            arrayOf(android.provider.MediaStore.Images.Media._ID),
            null, null,
            "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID)
            while (cursor.moveToNext() && out.size < 10) {
                out.add(android.content.ContentUris.withAppendedId(collection, cursor.getLong(idCol)))
            }
        }
        out
    } catch (_: Exception) {
        emptyList()
    }
}

private fun copyUriToCacheFile(context: Context, uri: Uri): File? {
    return try {
        var displayName = "shared_${System.currentTimeMillis()}"
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) displayName = c.getString(idx) ?: displayName
            }
        } catch (_: Exception) { }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)
        val out = File(context.cacheDir, "${System.currentTimeMillis()}_$safeName")
        context.contentResolver.openInputStream(uri)?.use { ins ->
            FileOutputStream(out).use { outs -> ins.copyTo(outs) }
        }
        out.takeIf { it.exists() && it.length() > 0 }
    } catch (_: Exception) { null }
}

private fun bitmapToCacheFile(context: Context, bitmap: Bitmap): File? {
    return try {
        val out = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
        FileOutputStream(out).use { fos -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos) }
        out.takeIf { it.exists() }
    } catch (_: Exception) { null }
}
