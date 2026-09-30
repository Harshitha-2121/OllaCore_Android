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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ollacore.app.data.model.MessageKinds
import com.ollacore.app.data.model.MessageResponse
import com.ollacore.app.ui.theme.BrandAvatar
import com.ollacore.app.ui.theme.BubbleRadiusOwn
import com.ollacore.app.ui.theme.BubbleRadiusPeer
import com.ollacore.app.ui.appearance.LocalChatTheme
import com.ollacore.app.ui.appearance.ChatWallpaperView
import com.ollacore.app.ui.appearance.rememberChatStyle
import com.ollacore.app.data.model.bubbleShapes
import com.ollacore.app.ui.theme.ReadBlue
import com.ollacore.app.ui.attachments.AttachmentPickerSheet
import kotlinx.coroutines.launch
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
    onStartEdit: (MessageResponse) -> Unit = {},
    onSubmitEdit: (String, String) -> Unit = { _, _ -> },
    onCancelEdit: () -> Unit = {},
    onDeleteForEveryone: (String) -> Unit,
    onDeleteForMe: (String) -> Unit,
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
    onForceResolveUrl: (String) -> Unit = {},
    onClearUploadError: () -> Unit = {},
    // ── Message status ticks (Category 1 - ack/receipts already in Ollacore WS) ──
    onRetryMessage: (MessageResponse) -> Unit = {},
    // ── Voice recorder (spec 13; recordings send as kind=audio) ──
    onStartRecord: () -> Unit = {},
    onCancelRecord: () -> Unit = {},
    onSendRecord: () -> Unit = {},
    onClearRecordError: () -> Unit = {},
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
    onReconnect: () -> Unit = {},
    onClearError: () -> Unit = {},
    // ── Expired session: log out + return to login (retry can't help) ──
    onLoginExpired: () -> Unit = {},
    // ── 3-dot overflow menu (stateful actions; see ChatOverflowMenu) ──
    menuState: ChatViewModel.ChatMenuState = ChatViewModel.ChatMenuState(),
    onEnterSelection: () -> Unit = {},
    onToggleFavourite: () -> Unit = {},
    onMute: (Long?) -> Unit = {},
    onDisappearing: (Long) -> Unit = {},
    onCreateList: (String, (Boolean) -> Unit) -> Unit = { _, done -> done(false) },
    onToggleListMember: (String, Boolean) -> Unit = { _, _ -> },
    onCloseChat: () -> Unit = {},
    onSendCallLink: () -> Unit = {},
    onOpenGroupCall: () -> Unit = {},
    onReport: (String) -> Unit = {},
    onToggleBlock: () -> Unit = {},
    onClearChat: () -> Unit = {},
    onDeleteChat: () -> Unit = {},
    // ── Starred-browser entry: jump once to this message when present ──
    initialScrollToMessageId: String? = null
) {
    // Draft survives rotation (String is saveable; rotation test covers this).
    var messageText by rememberSaveable { mutableStateOf("") }
    // Settings-driven input behavior (Settings > Chats > Enter key to send;
    // Settings > Storage > Media upload quality). Defaults keep current UX.
    val chatPrefsContext = LocalContext.current
    val chatPrefs = remember(chatPrefsContext) {
        com.ollacore.app.data.local.ChatPrefsStore(chatPrefsContext.applicationContext)
    }
    val enterToSend by chatPrefs.customBoolFlow(
        com.ollacore.app.data.local.ChatPrefsStore.SettingsKeys.ENTER_SEND, true
    ).collectAsState(initial = true)
    val uploadQuality by chatPrefs.customFlow(
        com.ollacore.app.data.local.ChatPrefsStore.SettingsKeys.UPLOAD_QUALITY, "balanced"
    ).collectAsState(initial = "balanced")
    var showActions by remember { mutableStateOf<MessageResponse?>(null) }
    // Window bounds of the long-pressed bubble: anchors the context popup.
    var selectedBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    // A new target re-anchors from scratch (never a stale position).
    LaunchedEffect(showActions?.id) { selectedBounds = null }
    // Delete confirmation targets: 1 from the actions sheet, N from the selection toolbar.
    var deleteQueue by remember { mutableStateOf<List<MessageResponse>>(emptyList()) }
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
    val scope = rememberCoroutineScope()
    // Context popup follows WhatsApp: any list scroll dismisses it.
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) showActions = null
    }
    // Older history: scrolling to the very top pages backward (server has_more).
    // lastPagedSeq stops repeat calls when a page comes back empty.
    var lastPagedSeq by remember(roomId) { mutableStateOf<Int?>(null) }
    val canPage = uiState.hasMoreHistory && !uiState.loadingHistory && !uiState.isLoading
    LaunchedEffect(listState.firstVisibleItemIndex, canPage) {
        if (canPage && listState.firstVisibleItemIndex == 0) {
            val minSeq = uiState.messages.minOfOrNull { it.eventSeq }
            if (minSeq != null && minSeq != lastPagedSeq) {
                lastPagedSeq = minSeq
                onLoadMore(minSeq)
            }
        }
    }
    // WhatsApp-style follow: chat opens at the latest message and sticks to
    // the bottom for new arrivals. History prepend (loadMore) never moves the
    // viewport: the last message id is unchanged by prepends, so no scroll.
    var lastSeenMsgId by remember(roomId) { mutableStateOf<String?>(null) }
    val lastMsgId = uiState.messages.lastOrNull()?.id
    LaunchedEffect(lastMsgId) {
        val msgs = uiState.messages
        if (msgs.isEmpty() || lastMsgId == lastSeenMsgId) return@LaunchedEffect
        val isInitial = lastSeenMsgId == null
        lastSeenMsgId = lastMsgId
        val visible = listState.layoutInfo.visibleItemsInfo
        val atBottom = visible.lastOrNull()?.index?.let { it >= msgs.size - 3 } ?: true
        val isOwn = msgs.lastOrNull()?.senderId == uiState.currentUserId
        if (isInitial || atBottom || isOwn) {
            if (isInitial) listState.scrollToItem(msgs.size - 1)
            else listState.animateScrollToItem(msgs.size - 1)
        }
    }
    // Deep-link entry (starred browser): jump once to the target message
    // as soon as history contains it; never fights the follow logic after.
    var scrollTargetConsumed by remember(roomId, initialScrollToMessageId) { mutableStateOf<String?>(null) }
    LaunchedEffect(uiState.messages.size, initialScrollToMessageId) {
        val target = initialScrollToMessageId
        if (!target.isNullOrBlank() && scrollTargetConsumed != target) {
            val idx = uiState.messages.indexOfFirst { it.id == target }
            if (idx >= 0) {
                scrollTargetConsumed = target
                listState.scrollToItem(idx)
            }
        }
    }
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
    // ── Microphone permission (RECORD_AUDIO is declared in the manifest but
    //   must be requested at runtime; without it setAudioSource fails) ──
    var micDialog by remember { mutableStateOf(false) }
    val micPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onStartRecord() else micDialog = true
    }
    fun onMicTap() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            onStartRecord()
            return
        }
        val activity = context as? android.app.Activity
        val showRationale = activity?.let {
            androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                it, android.Manifest.permission.RECORD_AUDIO
            )
        } ?: true
        if (showRationale) micDialog = true
        else runCatching { micPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO) }
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
            val file = bitmapToCacheFile(context, bitmap, uploadQualityValue(uploadQuality)) ?: return@rememberLauncherForActivityResult
            showAttachmentSheet = false
            stagePreview(file, "image/jpeg", MessageKinds.IMAGE)
        }
    }

    val peerName = uiState.peerName ?: "Chat"
    val isGroupChat = uiState.kind.equals("group", ignoreCase = true)
    // "online" ONLY when the 1-to-1 peer's own principal is reported online
    // (their app open). Our own presence echo or other members must never
    // light it; groups have no single presence.
    val peerId = uiState.peerUserId?.takeIf { it.isNotBlank() }
    val isOnline = !isGroupChat && peerId != null && peerId in uiState.onlineUsers
    // Typing names resolve via the participants roster (never raw ids/phone numbers);
    // 1-to-1 keeps WhatsApp-style "typing…", groups show who is typing.
    val typingNames = remember(uiState.typingUsers, uiState.participantNames, uiState.currentUserId) {
        uiState.typingUsers
            .filter { it != uiState.currentUserId }
            .map { id ->
                uiState.participantNames[id]?.takeIf { it.isNotBlank() }
                    ?: uiState.participantPhones[id]?.takeIf { it.isNotBlank() }
                    ?: peerName
            }
            .distinct()
    }
    val subtitle = when {
        typingNames.isNotEmpty() && !isGroupChat -> "typing…"
        typingNames.isNotEmpty() && typingNames.size == 1 -> "${typingNames[0]} is typing…"
        typingNames.isNotEmpty() && typingNames.size == 2 -> "${typingNames[0]} and ${typingNames[1]} are typing…"
        typingNames.isNotEmpty() -> "${typingNames[0]} and ${typingNames.size - 1} others are typing…"
        isGroupChat && uiState.participantCount > 0 -> "${uiState.participantCount} members"
        isOnline -> "online"
        uiState.isConnected -> "tap for info"
        else -> "offline"
    }

    // Appearance → Chat Theme: live resolved style for bubbles + wallpaper.
    val chatStyle = rememberChatStyle()
    androidx.compose.runtime.CompositionLocalProvider(
        LocalChatTheme provides chatStyle
    ) {
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
                            deleteQueue = uiState.selectedIds.mapNotNull { id ->
                                uiState.messages.find { it.id == id }
                            }
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
                                Icon(Icons.Default.MoreVert, contentDescription = "Chat menu")
                            }
                            // 3-dot overflow menu: dark anchored popup, stateful rows.
                            // Contact info + Search reuse the existing routes/actions.
                            ChatOverflowMenu(
                                expanded = showMore,
                                onDismiss = { showMore = false },
                                menu = menuState,
                                peerName = peerName,
                                isGroup = isGroupChat,
                                onContactInfo = onProfileClick,
                                onSearch = onSearch,
                                onSelectMessages = onEnterSelection,
                                onMute = onMute,
                                onDisappearing = onDisappearing,
                                onToggleFavourite = onToggleFavourite,
                                onCreateList = onCreateList,
                                onToggleListMember = onToggleListMember,
                                onCloseChat = onCloseChat,
                                onSendCallLink = onSendCallLink,
                                onNewGroupCall = onOpenGroupCall,
                                onReport = onReport,
                                onToggleBlock = onToggleBlock,
                                onClearChat = onClearChat,
                                onDeleteChat = onDeleteChat
                            )
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
                // Edge-to-edge (MainActivity enableEdgeToEdge) + adjustResize:
                // consume IME insets so the composer rides above the keyboard
                // instead of being covered by it.
                .imePadding()
        ) {
            // Spec 35: subtle offline indicator (chat stays usable).
            val (chatOnline, chatWasOffline) = com.ollacore.app.ui.common.rememberConnectivity()
            com.ollacore.app.ui.common.OfflineBanner(isOnline = chatOnline, wasOffline = chatWasOffline)
            // Inline action errors (e.g. group leave rejected): dismissible, chat stays usable.
            if (uiState.error != null && uiState.messages.isNotEmpty()) {
                Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            uiState.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onClearError) { Text("Dismiss") }
                    }
                }
            }
            // Loading: spinner instead of a blank screen while history loads.
            if (uiState.isLoading && uiState.messages.isEmpty() && uiState.error == null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    CircularProgressIndicator()
                }
            }
            // Spec 34: friendly full error with rejoin retry (raw errors mapped, never shown).
            // Auth-expired errors offer log-in (retry can't revive a dead session).
            if (uiState.error != null && uiState.messages.isEmpty() && !uiState.isLoading) {
                com.ollacore.app.ui.common.ErrorState(
                    message = uiState.error,
                    onRetry = onReconnect,
                    onBack = onBack,
                    onLoginExpired = onLoginExpired
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
                        .padding(horizontal = 8.dp)
                        .testTag("chat_list"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                // Centered encryption notice (WhatsApp-style system bubble, real info).
                item(key = "sys-e2ee") {
                    SystemNoticeChip(
                        text = if (uiState.isEncrypted) "🔒 Messages are end-to-end encrypted."
                        else "Messages appear here.",
                        icon = Icons.Default.Lock
                    )
                }
                itemsIndexed(uiState.messages, key = { _, m -> m.id }) { index, message ->
                    // Day separator chip between different calendar days.
                    val day = messageDay(message.createdAt)
                    val prevDay = uiState.messages.getOrNull(index - 1)?.let { messageDay(it.createdAt) }
                    if (day != null && day != prevDay) {
                        SystemNoticeChip(text = dayLabel(day), icon = null)
                    }
                    val isStarred = message.id in uiState.starredIds
                    val isSelected = message.id in uiState.selectedIds
                    val isDeleted = message.id in uiState.deletedIds
                    val haptics = LocalHapticFeedback.current
                    // Quoted-reply lookup: resolve reply_to against loaded history so the
                    // bubble shows WHO + WHAT was quoted (not just a "Reply" label).
                    val messagesById = remember(uiState.messages) { uiState.messages.associateBy { it.id } }
                    val quotedMsg = message.replyTo?.let { messagesById[it] }
                    val quotedSender = quotedMsg?.let {
                        if (it.senderId == uiState.currentUserId) "You"
                        else uiState.participantNames[it.senderId]
                            ?: uiState.participantPhones[it.senderId]?.takeIf { p -> p.isNotBlank() }
                            ?: peerName
                    }
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
                        isDeleted = isDeleted,
                        onOpenDocument = onOpenDocument,
                        onOpenImage = onOpenImage,
                        isStarred = isStarred,
                        isSelected = isSelected,
                        attachmentUrls = attachmentUrls,
                        onResolveUrl = onResolveUrl,
                        onForceResolveUrl = onForceResolveUrl,
                        status = status,
                        quoted = quotedMsg,
                        quotedSender = quotedSender,
                        onQuoteClick = { targetId ->
                            val idx = uiState.messages.indexOfFirst { it.id == targetId }
                            if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) }
                        },
                        onRetry = { onRetryMessage(message) },
                        onClick = {
                            // Tap: in selection mode it toggles (popup follows
                            // the latest tapped message, closes on deselect);
                            // otherwise it opens the popup. Tombstones open
                            // the Info-only popup without entering selection.
                            if (isDeleted) {
                                showActions = message
                                return@MessageBubble
                            }
                            if (uiState.selectionMode) {
                                val wasSelected = message.id in uiState.selectedIds
                                onToggleSelect(message.id)
                                showActions = if (wasSelected) null else message
                            } else showActions = message
                        },
                        onLongClick = {
                            // Long-press: highlight + popup together
                            // (WhatsApp-style); tombstones get the popup only.
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (isDeleted) {
                                showActions = message
                                return@MessageBubble
                            }
                            val wasSelected = message.id in uiState.selectedIds
                            onToggleSelect(message.id)
                            showActions = if (wasSelected) null else message
                        },
                        trackBounds = message.id == showActions?.id,
                        onBounds = { selectedBounds = it }
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

            // Typing indicator (WhatsApp-style; typingNames already excludes self)
            if (typingNames.isNotEmpty()) {
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
                // Name the reply target so it's obvious WHICH message is quoted
                // before sending (peer name / roster / You).
                val targetName = if (reply.senderId == uiState.currentUserId) "yourself"
                else uiState.participantNames[reply.senderId]
                    ?: uiState.participantPhones[reply.senderId]?.takeIf { it.isNotBlank() }
                    ?: peerName
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
                            Text("Replying to $targetName", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(preview, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { onReply(null) }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel reply")
                        }
                    }
                }
            }

            // Editing preview (mirrors the reply strip): original text + cancel.
            uiState.editingMessage?.let { editing ->
                val original = bodyString(editing.body, "text") ?: ""
                // Prefill once per target so the user edits, not retypes.
                LaunchedEffect(editing.id) {
                    messageText = original
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Editing message", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(original, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { onCancelEdit(); messageText = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel edit")
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

            // Recorder failures: dismissible banner, never fullscreen.
            uiState.recordError?.let { recordErr ->
                Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.MicOff, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            recordErr,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onClearRecordError) { Text("Dismiss") }
                    }
                }
            }

            // Composer — WhatsApp-style: Emoji | Attachment | Camera | Text | Voice/Send
            // Desktop-style input: Enter sends, Shift+Enter inserts a newline.
            val sendNow = {
                if (messageText.isNotBlank()) {
                    val editing = uiState.editingMessage
                    if (editing != null) {
                        onSubmitEdit(editing.id, messageText.trim())
                    } else {
                        onSendMessage(messageText.trim())
                    }
                    messageText = ""
                    onTypingStopped()
                }
            }
            // Composer — reference layout: one rounded bar holding
            // Emoji | Text | Attachment | Camera, plus a circular mic/send
            // button on the right. Bar background stays transparent so the
            // chat pattern shows through; the pill carries its own surface.
            // Desktop-style input: Enter sends, Shift+Enter inserts a newline.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = androidx.compose.ui.graphics.Color.Transparent
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                        ),
                        shadowElevation = 1.dp,
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { showEmojiPicker = !showEmojiPicker }) {
                                Icon(Icons.Default.EmojiEmotions, contentDescription = "Emoji")
                            }
                            OutlinedTextField(
                                value = messageText,
                                onValueChange = {
                                    messageText = it
                                    if (it.isNotEmpty()) onTypingStarted() else onTypingStopped()
                                },
                                modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                                    // Hardware keyboards (incl. emulator): plain Enter sends,
                                    // Shift+Enter falls through to the default newline.
                                    // Honors Settings > Chats > Enter key to send.
                                    if (enterToSend && event.key == Key.Enter && event.type == KeyEventType.KeyDown && !event.isShiftPressed) {
                                        sendNow()
                                        true
                                    } else false
                                },
                                placeholder = { Text("Message") },
                                maxLines = 4,
                                shape = RoundedCornerShape(24.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                    unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                    focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                                    unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent
                                ),
                                // autoCorrectEnabled = false removes Gboard's empty white
                                // suggestion strip above the keyboard (it can't be hidden
                                // per-app any other way; tradeoff: no autocorrect here).
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Text,
                                    autoCorrectEnabled = false,
                                    imeAction = if (enterToSend) ImeAction.Send else ImeAction.Default
                                ),
                                keyboardActions = if (enterToSend) KeyboardActions(onSend = { sendNow() })
                                else KeyboardActions()
                            )
                            // "+" -> Camera / Gallery / Document / Audio / Location(API check)
                            IconButton(onClick = { showAttachmentSheet = true; onPickAttachment() }) {
                                Icon(Icons.Default.AttachFile, contentDescription = "Attachment")
                            }
                            IconButton(onClick = { try { cameraLauncher.launch(null) } catch (_: Exception) { }; onOpenCamera() }) {
                                Icon(Icons.Default.PhotoCamera, contentDescription = "Camera")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    if (messageText.isNotBlank()) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable { sendNow() }
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    } else if (!uiState.voiceDraft.isRecording) {
                        // Spec 13: tap mic to record (no voice_note backend, so it sends
                        // as kind=audio with duration + waveform body fields).
                        // Hidden while recording (VM also guards double-start).
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable(onClick = ::onMicTap)
                        ) {
                            Icon(
                                Icons.Default.Mic,
                                contentDescription = "Record voice message",
                                tint = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
    } // CompositionLocalProvider(LocalChatTheme)

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

    // Microphone permission explainer (first tap, or after a denial).
    if (micDialog) {
        AlertDialog(
            onDismissRequest = { micDialog = false },
            icon = { Icon(Icons.Default.Mic, contentDescription = null) },
            title = { Text("Allow microphone access?") },
            text = { Text("Voice messages need the microphone. You can change this anytime in the app settings.") },
            confirmButton = {
                TextButton(onClick = {
                    micDialog = false
                    runCatching { micPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO) }
                }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = {
                    micDialog = false
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.fromParts("package", context.packageName, null)
                            )
                        )
                    }
                }) { Text("App settings") }
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

    // Message context menu: WhatsApp-style anchored popup (spec 11) - reaction
    // pill + vertical menu (Reply/Forward/Copy/Info/Star/Delete/More).
    // Edit/Delete stay own-message only; Copy hides when there is no text.
    showActions?.let { message ->
        val isOwnAction = message.senderId == uiState.currentUserId ||
            (uiState.currentUserId.isBlank() && (message.senderId == "self" || message.senderId.isBlank()))
        val starredAction = message.id in uiState.starredIds
        val statusAction = uiState.messageStatus[message.id]
            ?: message.clientMessageId?.let { uiState.messageStatus[it] }
        val msgText = bodyString(message.body, "text") ?: ""
        MessageContextMenu(
            message = message,
            isOwn = isOwnAction,
            isStarred = starredAction,
            status = statusAction,
            hasText = msgText.isNotBlank(),
            isText = message.kind.equals(MessageKinds.TEXT, ignoreCase = true),
            isDeleted = message.id in uiState.deletedIds ||
                message.kind.equals("deleted", ignoreCase = true),
            anchor = selectedBounds,
            onDismiss = { showActions = null; selectedBounds = null },
            onReply = { onReply(message); showActions = null },
            onCopy = {
                val t = bodyString(message.body, "text") ?: ""
                copyToClipboard(context, t); onCopy(t); showActions = null
            },
            onForward = { onForward(message); showActions = null },
            onEdit = { onStartEdit(message); showActions = null },
            onDelete = { deleteQueue = listOf(message); showActions = null },
            onReact = { emoji -> onAddReaction(message.id, emoji); showActions = null },
            onStar = { onToggleStar(message.id); showActions = null },
            onSelect = { onToggleSelect(message.id); showActions = null }
        )
    }

    // Delete confirmation (reference dialog): everyone = server tombstone both
    // sides; me = local hide only. Everyone offered for own messages only.
    if (deleteQueue.isNotEmpty()) {
        val allOwn = deleteQueue.all { isOwnMessage(it, uiState.currentUserId) }
        DeleteMessageDialog(
            showEveryone = allOwn,
            onEveryone = {
                deleteQueue.forEach { onDeleteForEveryone(it.id) }
                deleteQueue = emptyList()
                onClearSelection()
            },
            onMe = {
                deleteQueue.forEach { onDeleteForMe(it.id) }
                deleteQueue = emptyList()
                onClearSelection()
            },
            onDismiss = { deleteQueue = emptyList() }
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

/** One-line preview of a quoted message: text snippet or a media-kind label. */
private fun quoteSnippet(msg: MessageResponse): String {
    bodyString(msg.body, "text")?.takeIf { it.isNotBlank() }?.let {
        return if (it.length > 90) it.take(90) + "…" else it
    }
    if (msg.attachmentIds.isNotEmpty() || msg.kind.equals("image", ignoreCase = true)) return "📷 Photo"
    return when (msg.kind.lowercase()) {
        "video" -> "🎥 Video"
        "audio", "voice_note" -> "🎵 Voice message"
        "video_note" -> "🎥 Video message"
        "file" -> "📄 ${bodyString(msg.body, "filename") ?: "Document"}"
        "location" -> "📍 Location"
        "contact" -> "👤 Contact"
        "poll" -> "📊 Poll"
        "event" -> "📅 Event"
        else -> msg.kind
    }
}

/**
 * Message context menu lives in MessageContextMenu.kt now (anchored popup:
 * reaction pill + vertical menu). This file keeps MessageInfoRow for it.
 */

@Composable
internal fun MessageInfoRow(label: String, value: String) {
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
    onForceResolveUrl: (String) -> Unit = {},
    status: MessageStatus? = null,
    onRetry: () -> Unit = {},
    senderLabel: String? = null,
    showSender: Boolean = false,
    onOpenDocument: (String, String, String) -> Unit = { _, _, _ -> },
    onOpenImage: (String) -> Unit = {},
    isDeleted: Boolean = false,
    quoted: MessageResponse? = null,
    quotedSender: String? = null,
    onQuoteClick: ((String) -> Unit)? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    trackBounds: Boolean = false,
    onBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {}
) {
    val alignment = if (isOwn) Alignment.CenterEnd else Alignment.CenterStart
    // Appearance → Chat Theme: bubbles follow the selected theme; outside a
    // themed subtree (or before it loads) the app scheme is the fallback.
    val chatTheme = LocalChatTheme.current
    val shape = chatTheme?.let {
        val (own, peer) = remember(it.corner) { bubbleShapes(it.corner) }
        if (isOwn) own else peer
    } ?: if (isOwn) BubbleRadiusOwn else BubbleRadiusPeer
    // Spec 10 + 31: outgoing = theme primary (adapts to Blue/Green/Purple/Dark);
    // incoming = white/light surface card with hairline border.
    val peerCard = chatTheme?.incomingBubble
        ?: if (isSelected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surface
    val main = chatTheme?.let { if (isOwn) it.outgoingText else it.incomingText }
        ?: if (isOwn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val soft = main.copy(alpha = 0.78f)
    val ownBg = chatTheme?.outgoingBubble

    // Spec 15: group peer messages carry a colored sender label + mini avatar.
    val bubbleBox: @Composable (Modifier) -> Unit = { boxModifier ->
        Box(
            modifier = boxModifier
                .clip(shape)
                .then(
                    if (isOwn) Modifier.background(ownBg ?: MaterialTheme.colorScheme.primary)
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
                        // WhatsApp-style quoted card: colored bar + quoted sender + snippet.
                        // Falls back to the raw id tail when the original isn't loaded.
                        val snippet = quoted?.let { quoteSnippet(it) }
                            ?: "Original message"
                        val qName = quotedSender ?: "Reply"
                        Row(
                            modifier = Modifier
                                .padding(bottom = 6.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isOwn) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.18f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                                )
                                .then(
                                    if (onQuoteClick != null && quoted != null) Modifier.clickable { onQuoteClick(message.replyTo) }
                                    else Modifier
                                )
                                .padding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .heightIn(min = 32.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            // Wrap content: a weight here would force the whole
                            // bubble to max width even for one-word quotes.
                            Column {
                                Text(
                                    "↩ $qName",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                    color = if (isOwn) androidx.compose.ui.graphics.Color.White
                                    else MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    snippet,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = soft.copy(alpha = 0.95f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    // ── Media rendering (was text-only gap): image / video / doc / audio / location ──
                    MediaMessageContent(message = message, attachmentUrls = attachmentUrls, onResolveUrl = onResolveUrl, onForceResolveUrl = onForceResolveUrl, isOwn = isOwn, onGradient = isOwn, onOpenDocument = onOpenDocument, onOpenImage = onOpenImage)
                    BubbleFooter(message = message, isStarred = isStarred, isSelected = isSelected, status = status, isOwn = isOwn, onRetry = onRetry, onGradient = isOwn)
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent)
            .onGloballyPositioned { coords ->
                if (trackBounds) onBounds(coords.boundsInWindow())
            },
        contentAlignment = alignment
    ) {
        // Server tombstoned rows (kind=deleted, e.g. removed before this client
        // saw the event) render exactly like live-deleted ones - never the raw
        // "Unsupported message (deleted)" fallback.
        val gone = isDeleted || message.kind.equals("deleted", ignoreCase = true)
        if (gone) {
            // Tombstone: placeholder bubble with blocked icon, sender-aware copy
            // ("You…" for own, "This…" for peer) + original timestamp position.
            Surface(
                shape = shape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.widthIn(max = 300.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Block,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            tombstoneText(isOwn),
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    messageTime(message.createdAt)?.let { stamp ->
                        Text(
                            stamp,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            modifier = Modifier.align(Alignment.End).padding(top = 2.dp)
                        )
                    }
                }
            }
            return@Box
        }
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

/** Reference-style forwarded header: small arrow + italic label above content. */
@Composable
private fun ForwardedLabel(isOwn: Boolean) {
    val soft = if (isOwn) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f)
    else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 2.dp)
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Forward,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = soft
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            "Forwarded",
            style = MaterialTheme.typography.bodySmall,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            color = soft
        )
    }
}

/** Own-message check shared by delete/selection UI. */
private fun isOwnMessage(m: MessageResponse, currentUserId: String): Boolean =
    m.senderId == currentUserId ||
        (currentUserId.isBlank() && (m.senderId == "self" || m.senderId.isBlank()))

/** Tombstone copy: the sender sees "You…", everyone else "This…". Pure (unit-tested). */
fun tombstoneText(isOwn: Boolean): String =
    if (isOwn) "You deleted this message" else "This message was deleted"

/**
 * Delete confirmation matching the reference: dark rounded card, title up
 * top, full-width pill buttons (red "Delete for everyone", green
 * "Delete for me"), borderless green "Cancel". The everyone-option only
 * renders for own messages (server enforces sender-only).
 */
@Composable
private fun DeleteMessageDialog(
    showEveryone: Boolean,
    onEveryone: () -> Unit,
    onMe: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp)
            ) {
                Text(
                    "Delete message?",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(20.dp))
                if (showEveryone) {
                    OutlinedButton(
                        onClick = onEveryone,
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Delete for everyone",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
                OutlinedButton(
                    onClick = onMe,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Delete for me",
                        color = com.ollacore.app.ui.theme.OllaGreenBright,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = com.ollacore.app.ui.theme.OllaGreenBright)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.BubbleFooter(
    message: MessageResponse,
    isStarred: Boolean,
    isSelected: Boolean,
    status: MessageStatus? = null,
    isOwn: Boolean = false,
    onRetry: () -> Unit = {},
    onGradient: Boolean = false
) {
    val soft = bubbleTextColor(isOwn).copy(alpha = 0.8f)
    // Reference layout: timestamp + ticks tuck into the bubble's bottom-right
    // for every message (incoming and outgoing alike). The row WRAPS and aligns
    // end: a weight spacer here would stretch every bubble to max width.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .align(Alignment.End)
            .padding(top = 2.dp, start = 4.dp, end = 4.dp, bottom = 4.dp)
    ) {
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
        }
        // ── Message status ticks (own bubbles only; driven by Ollacore ack + receipts) ──
        if (isOwn && status != null) {
            Spacer(modifier = Modifier.width(4.dp))
            when (status) {
                MessageStatus.SENDING -> Icon(
                    Icons.Default.Schedule, contentDescription = "Sending",
                    modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                MessageStatus.SENT -> Icon(
                    Icons.Default.Check, contentDescription = "Sent",
                    modifier = Modifier.size(14.dp), tint = bubbleTextColor(isOwn)
                )
                MessageStatus.DELIVERED -> Icon(
                    Icons.Default.DoneAll, contentDescription = "Delivered",
                    modifier = Modifier.size(16.dp), tint = bubbleTextColor(isOwn)
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
    onForceResolveUrl: (String) -> Unit = {},
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
        ?: com.ollacore.app.data.model.attachmentRefIds(message).firstOrNull()
    val kind = message.kind.lowercase()

    // Location bubble (backend/API check required for kind=location)
    if (kind == MessageKinds.LOCATION || (lat != null && lng != null)) {
        LocationBubble(lat = lat, lng = lng, label = caption, onGradient = onGradient)
        return
    }

    val isMedia = attachmentId != null || kind in setOf(MessageKinds.IMAGE, MessageKinds.VIDEO, MessageKinds.AUDIO, MessageKinds.FILE)
    if (!isMedia) {
        // Forwarded messages arrive prefixed "[Forwarded] " (see ChatViewModel
        // forward): render the reference-style Forwarded label and strip the
        // wire prefix so the bubble shows clean content.
        val forwarded = caption.startsWith("[Forwarded] ")
        val displayCaption = if (forwarded) caption.removePrefix("[Forwarded] ") else caption
        if (forwarded) {
            ForwardedLabel(isOwn = isOwn)
        }
        if (displayCaption.isNotEmpty()) {
            LinkifiedText(displayCaption, modifier = Modifier.padding(4.dp))
            // Client-side link preview card (no unfurl backend): domain + open.
            com.ollacore.app.data.util.firstUrl(displayCaption)?.let { url ->
                LinkPreviewCard(url = url)
            }
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
            // duration_ms (ours) or duration seconds (peer clients) - voiceDurationMs handles both.
            val durationMs = com.ollacore.app.data.model.voiceDurationMs(body)
            val wave = body["waveform"]?.jsonArray?.mapNotNull {
                try { it.jsonPrimitive.content.toInt() } catch (_: Exception) { null }
            }?.take(48)
            val byteSize = bodyString(body, "byte_size")?.toLongOrNull()
            AudioBubbleContent(
                url = url,
                filename = filename,
                onGradient = onGradient,
                durationMs = durationMs,
                waveform = wave,
                attachmentId = attachmentId,
                byteSize = byteSize,
                onResolveUrl = { aid -> onResolveUrl(aid) },
                onForceResolveUrl = { aid -> onForceResolveUrl(aid) }
            )
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
    val soft = bubbleTextColor(onGradient).copy(alpha = 0.8f)
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
    waveform: List<Int>? = null,
    attachmentId: String? = null,
    byteSize: Long? = null,
    onResolveUrl: (String) -> Unit = {},
    onForceResolveUrl: (String) -> Unit = {}
) {
    val soft = bubbleTextColor(onGradient).copy(alpha = 0.8f)
    var isPlaying by remember { mutableStateOf(false) }
    var isPreparing by remember { mutableStateOf(false) }
    var playError by remember { mutableStateOf<String?>(null) }
    var knownDurationMs by remember(durationMs) { mutableStateOf(durationMs) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    // 418-byte MP3 stubs (early probes) decode as near-silence; surface honestly.
    val isStubAudio = byteSize != null && byteSize in 1..1024L
    // Peer history can omit attachments entirely (Alice webm): no download id.
    val missingAttachment = attachmentId == null && url == null && !isStubAudio
    // Armed when a tap had no URL yet, or a play error forced a re-resolve.
    var autoStartArmed by remember { mutableStateOf(false) }

    fun releasePlayer() {
        try { player?.reset() } catch (_: Exception) { }
        try { player?.release() } catch (_: Exception) { }
        player = null
        isPlaying = false
        isPreparing = false
    }

    fun startPlayback(source: String) {
        isPreparing = true
        playError = null
        try {
            player?.let {
                try { it.reset() } catch (_: Exception) { }
                try { it.release() } catch (_: Exception) { }
            }
            player = MediaPlayer().apply {
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(source)
                setOnPreparedListener {
                    if (knownDurationMs == null) knownDurationMs = it.duration.toLong()
                    it.start(); isPlaying = true; isPreparing = false
                }
                setOnCompletionListener { isPlaying = false }
                setOnErrorListener { _, _, _ ->
                    // Expired presigned URL or unsupported bytes: never stick on spinner.
                    releasePlayer()
                    playError = "Can't play this audio"
                    autoStartArmed = true
                    val aid = attachmentId
                    if (aid != null) onForceResolveUrl(aid)
                    true
                }
                prepareAsync()
            }
        } catch (_: Exception) {
            releasePlayer()
            playError = "Can't play this audio"
            autoStartArmed = true
            val aid = attachmentId
            if (aid != null) onForceResolveUrl(aid)
        }
    }

    DisposableEffect(url) {
        onDispose { releasePlayer() }
    }

    // URL arrived after tap-without-URL, or after force re-resolve on error:
    // start (or retry) playback without requiring a second tap.
    LaunchedEffect(url) {
        if (url != null && attachmentId != null && !isStubAudio && !missingAttachment) {
            if (autoStartArmed || (isPreparing && player == null)) {
                autoStartArmed = false
                startPlayback(url)
            }
        }
    }

    // Spec 13 bubble: play button + waveform + duration + timestamp ticks (ticks live in footer).
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp)) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp).widthIn(max = 250.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(enabled = !isStubAudio && !missingAttachment, onClick = {
                playError = null
                if (url == null) {
                    // Tap with no URL: resolve now (expiry/force path lives in the VM).
                    val aid = attachmentId ?: return@FilledIconButton
                    isPreparing = true
                    autoStartArmed = true
                    onResolveUrl(aid)
                    return@FilledIconButton
                }
                try {
                    if (isPlaying) {
                        player?.pause(); isPlaying = false
                    } else if (player != null) {
                        isPreparing = true
                        player?.start(); isPlaying = true; isPreparing = false
                    } else {
                        startPlayback(url)
                    }
                } catch (_: Exception) {
                    releasePlayer()
                    playError = "Can't play this audio"
                    autoStartArmed = true
                    val aid = attachmentId
                    if (aid != null) onForceResolveUrl(aid)
                }
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
                    val statusLine = when {
                        isStubAudio -> "Empty audio stub"
                        missingAttachment -> "Voice message (audio not linked)"
                        playError != null -> playError!!
                        else -> filename?.takeIf { it.isNotBlank() } ?: "🎵 Voice message"
                    }
                    Text(
                        statusLine,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (playError != null && !isStubAudio && !missingAttachment)
                            MaterialTheme.colorScheme.error else soft,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    val shownDuration = knownDurationMs
                    if (!isStubAudio && !missingAttachment && shownDuration != null && shownDuration > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(formatVoiceTime(shownDuration), style = MaterialTheme.typography.labelSmall, color = soft)
                    } else if (url == null && attachmentId != null && !isStubAudio && playError == null) {
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
    val soft = bubbleTextColor(onGradient).copy(alpha = 0.8f)
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

/**
 * WhatsApp-style doodle background (spec 10): deterministic scattered glyphs
 * (rings, plus marks, arcs, rounded squares) at very low alpha over dots.
 * Pure Canvas, no assets, theme-aware, zero backend involved.
 *
 * Appearance → Chat Theme: renders the selected wallpaper; the default
 * theme's doodle derives from the app scheme (same look as before).
 */
@Composable
private fun ChatPatternBackground(modifier: Modifier = Modifier) {
    val wallpaper = LocalChatTheme.current?.wallpaper
        ?: com.ollacore.app.data.model.ChatWallpaper.Doodle()
    ChatWallpaperView(wallpaper = wallpaper, modifier = modifier)
}

/**
 * Bubble content (text) color: theme-resolved when a chat theme is active,
 * otherwise the historical scheme colors. Keeps ticks, timestamps and media
 * controls readable on custom bubble colors.
 */
@Composable
private fun bubbleTextColor(isOwn: Boolean): androidx.compose.ui.graphics.Color {
    val t = LocalChatTheme.current
    return when {
        t != null && isOwn -> t.outgoingText
        t != null -> t.incomingText
        isOwn -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
}

/** Centered system chip: encryption notice + day separators. */
@Composable
private fun SystemNoticeChip(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 1.dp,
            modifier = Modifier.padding(vertical = 6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Calendar day of a server timestamp, null when missing/unparseable. */
private fun messageDay(raw: String?): java.time.LocalDate? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        val instant = try {
            java.time.Instant.parse(raw)
        } catch (_: Exception) {
            val n = raw.toLong()
            java.time.Instant.ofEpochMilli(if (n < 1_000_000_000_000L) n * 1000 else n)
        }
        instant.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    }.getOrNull()
}

private fun dayLabel(day: java.time.LocalDate): String {
    val today = java.time.LocalDate.now()
    return when {
        day.isEqual(today) -> "Today"
        day.isEqual(today.minusDays(1)) -> "Yesterday"
        day.year == today.year -> day.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM"))
        else -> day.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"))
    }
}

/** Message text with tappable links (client-side; no unfurl service). */
@Composable
private fun LinkifiedText(text: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Theme token (not system-based) so forced Light/Dark modes get the right link color.
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, linkColor) {
        androidx.compose.ui.text.buildAnnotatedString {
            append(text)
            com.ollacore.app.data.util.allUrls(text).forEach { (range, url) ->
                addStyle(
                    style = androidx.compose.ui.text.SpanStyle(
                        color = linkColor,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                    ),
                    start = range.first,
                    end = range.last + 1
                )
                addStringAnnotation(tag = "URL", annotation = url, start = range.first, end = range.last + 1)
            }
        }
    }
    androidx.compose.foundation.text.ClickableText(
        text = annotated,
        style = MaterialTheme.typography.bodyLarge.copy(color = androidx.compose.material3.LocalContentColor.current),
        modifier = modifier,
        onClick = { offset ->
            annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { ann ->
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ann.item)))
                }
            }
        }
    )
}

/** Compact link preview: globe + host + URL line; tap opens the browser. */
@Composable
private fun LinkPreviewCard(url: String) {
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .padding(top = 6.dp)
            .widthIn(max = 260.dp)
            .clickable {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    com.ollacore.app.data.util.linkHost(url),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Bubble timestamp HH:mm; null when the server timestamp is missing/unparseable. */
internal fun messageTime(raw: String?): String? {
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

/** Camera capture respects Settings > Storage > Media upload quality. */
private fun bitmapToCacheFile(context: Context, bitmap: Bitmap, quality: Int = 80): File? {
    return try {
        val out = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
        FileOutputStream(out).use { fos -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), fos) }
        out.takeIf { it.exists() }
    } catch (_: Exception) { null }
}

private fun uploadQualityValue(name: String): Int = when (name) {
    "high" -> 92
    "saver" -> 65
    else -> 80
}
