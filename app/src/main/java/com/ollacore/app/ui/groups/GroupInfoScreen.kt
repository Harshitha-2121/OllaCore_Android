package com.ollacore.app.ui.groups

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ollacore.app.data.model.Participant
import com.ollacore.app.data.util.ScannedMediaItem
import java.io.File
import java.util.UUID

/**
 * Group Info, reference layout + app theme + real group data:
 * header (photo/name/Group·N) + Audio/Video/Add/Search actions + media row +
 * starred + mute + encryption + privacy + similar group + member section
 * (search/add/You-first/view-all) + action rows (changes/favourites/lists/
 * clear/exit/report). Sub-screens (add/starred/changes) are full-screen
 * overlays inside this destination: back always returns to Group Info.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(
    uiState: GroupInfoUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRename: (String) -> Unit,
    onDescription: (String) -> Unit,
    onIcon: (String) -> Unit,
    onIconFile: (File) -> Unit = {},
    onRemoveMember: (String) -> Unit,
    onSetAdmin: (String, Boolean) -> Unit,
    onInvite: () -> Unit,
    onLeave: () -> Unit,
    onClearTransient: () -> Unit,
    onLeft: () -> Unit = {},
    onVoiceCall: () -> Unit = {},
    onVideoCall: () -> Unit = {},
    onSearchChat: () -> Unit = {},
    onToggleMute: () -> Unit = {},
    onOpenMediaBrowser: () -> Unit = {},
    onOpenMedia: (url: String, mime: String, name: String) -> Unit = { _, _, _ -> },
    onOpenChat: () -> Unit = {},
    onOpenStarredChat: (String) -> Unit = {},
    onPrivacy: () -> Unit = {},
    onSimilarGroup: (memberIds: String, name: String) -> Unit = { _, _ -> },
    onToggleFavourite: () -> Unit = {},
    onSetRoomList: (String, Boolean) -> Unit = { _, _ -> },
    onClearChat: () -> Unit = {},
    onReport: (String) -> Unit = {},
    onAddQuery: (String) -> Unit = {},
    onToggleAddSelect: (String) -> Unit = {},
    onConfirmAdd: () -> Unit = {},
    onConsumeAddDone: () -> Unit = {}
) {
    var showRename by remember { mutableStateOf(false) }
    var showDesc by remember { mutableStateOf(false) }
    var showIcon by remember { mutableStateOf(false) }
    var showLeave by remember { mutableStateOf(false) }
    var showClear by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    var showLists by remember { mutableStateOf(false) }
    var showEncryption by remember { mutableStateOf(false) }
    var memberMenu by remember { mutableStateOf<Participant?>(null) }
    var memberSearchOpen by remember { mutableStateOf(false) }
    var memberQuery by remember { mutableStateOf("") }
    var showAllMembers by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var showStarred by remember { mutableStateOf(false) }
    var showEvents by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.left) {
        if (uiState.left) onLeft()
    }
    LaunchedEffect(uiState.addDone) {
        if (uiState.addDone) {
            onConsumeAddDone()
            showAdd = false
        }
    }
    // Back from sub-screens returns to Group Info (never closes the app).
    BackHandler(enabled = showAdd || showStarred || showEvents) {
        showAdd = false
        showStarred = false
        showEvents = false
    }

    when {
        showAdd -> GroupAddMembersScreen(
            groupName = uiState.name,
            candidates = uiState.addCandidates,
            memberIds = uiState.members.map { it.principalId }.toSet(),
            query = uiState.addQuery,
            selected = uiState.addSelected,
            errors = uiState.addErrors,
            busy = uiState.addBusy,
            canAdd = uiState.isAdmin,
            onBack = { showAdd = false },
            onQuery = onAddQuery,
            onToggle = onToggleAddSelect,
            onConfirm = onConfirmAdd
        )
        showStarred -> GroupStarredScreen(
            groupName = uiState.name,
            starred = uiState.starred,
            onBack = { showStarred = false },
            onOpen = { messageId ->
                showStarred = false
                onOpenStarredChat(messageId)
            }
        )
        showEvents -> GroupEventsScreen(
            groupName = uiState.name,
            events = uiState.events,
            onBack = { showEvents = false }
        )
        else -> GroupInfoContent(
            uiState = uiState,
            onBack = onBack,
            onRefresh = onRefresh,
            onRename = onRename,
            onDescription = onDescription,
            onIcon = onIcon,
            onIconFile = onIconFile,
            onRemoveMember = onRemoveMember,
            onSetAdmin = onSetAdmin,
            onInvite = onInvite,
            onLeave = onLeave,
            onClearTransient = onClearTransient,
            onVoiceCall = onVoiceCall,
            onVideoCall = onVideoCall,
            onSearchChat = onSearchChat,
            onToggleMute = onToggleMute,
            onOpenMediaBrowser = onOpenMediaBrowser,
            onOpenMedia = onOpenMedia,
            onPrivacy = onPrivacy,
            onSimilarGroup = onSimilarGroup,
            onToggleFavourite = onToggleFavourite,
            onSetRoomList = onSetRoomList,
            onClearChat = onClearChat,
            onReport = onReport,
            memberSearchOpen = memberSearchOpen,
            memberQuery = memberQuery,
            showAllMembers = showAllMembers,
            onMemberSearchOpen = { memberSearchOpen = it; if (!it) memberQuery = "" },
            onMemberQuery = { memberQuery = it },
            onShowAllMembers = { showAllMembers = it },
            onShowAdd = { showAdd = true },
            onShowStarred = { showStarred = true },
            onShowEvents = { showEvents = true },
            onShowRename = { showRename = it },
            onShowDesc = { showDesc = it },
            onShowIcon = { showIcon = it },
            onShowLeave = { showLeave = it },
            onShowClear = { showClear = it },
            onShowReport = { showReport = it },
            onShowLists = { showLists = it },
            onShowEncryption = { showEncryption = true },
            onMemberMenu = { memberMenu = it },
            showRename = showRename,
            showDesc = showDesc,
            showIcon = showIcon,
            showLeave = showLeave,
            showClear = showClear,
            showReport = showReport,
            showLists = showLists,
            showEncryption = showEncryption,
            memberMenu = memberMenu
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupInfoContent(
    uiState: GroupInfoUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRename: (String) -> Unit,
    onDescription: (String) -> Unit,
    onIcon: (String) -> Unit,
    onIconFile: (File) -> Unit,
    onRemoveMember: (String) -> Unit,
    onSetAdmin: (String, Boolean) -> Unit,
    onInvite: () -> Unit,
    onLeave: () -> Unit,
    onClearTransient: () -> Unit,
    onVoiceCall: () -> Unit,
    onVideoCall: () -> Unit,
    onSearchChat: () -> Unit,
    onToggleMute: () -> Unit,
    onOpenMediaBrowser: () -> Unit,
    onOpenMedia: (String, String, String) -> Unit,
    onPrivacy: () -> Unit,
    onSimilarGroup: (String, String) -> Unit,
    onToggleFavourite: () -> Unit,
    onSetRoomList: (String, Boolean) -> Unit,
    onClearChat: () -> Unit,
    onReport: (String) -> Unit,
    memberSearchOpen: Boolean,
    memberQuery: String,
    showAllMembers: Boolean,
    onMemberSearchOpen: (Boolean) -> Unit,
    onMemberQuery: (String) -> Unit,
    onShowAllMembers: (Boolean) -> Unit,
    onShowAdd: () -> Unit,
    onShowStarred: () -> Unit,
    onShowEvents: () -> Unit,
    onShowRename: (Boolean) -> Unit,
    onShowDesc: (Boolean) -> Unit,
    onShowIcon: (Boolean) -> Unit,
    onShowLeave: (Boolean) -> Unit,
    onShowClear: (Boolean) -> Unit,
    onShowReport: (Boolean) -> Unit,
    onShowLists: (Boolean) -> Unit,
    onShowEncryption: () -> Unit,
    onMemberMenu: (Participant?) -> Unit,
    showRename: Boolean,
    showDesc: Boolean,
    showIcon: Boolean,
    showLeave: Boolean,
    showClear: Boolean,
    showReport: Boolean,
    showLists: Boolean,
    showEncryption: Boolean,
    memberMenu: Participant?
) {
    val context = LocalContext.current
    val iconPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            copyGroupIconToCache(context, uri)?.let { onIconFile(it) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Group info") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to group chat")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                com.ollacore.app.ui.common.SkeletonList(rows = 8)
            }
            return@Scaffold
        }
        val sortedMembers = remember(uiState.members, uiState.selfId) {
            sortMembersYouFirst(uiState.members, uiState.selfId)
        }
        val shownMembers = remember(sortedMembers, memberQuery) {
            filterMembers(sortedMembers, memberQuery)
        }
        val previewMembers = if (showAllMembers) shownMembers else shownMembers.take(8)
        val hiddenCount = (shownMembers.size - previewMembers.size).coerceAtLeast(0)

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Header: photo + name + Group · N.
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(72.dp)) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            if (uiState.iconUrl != null) {
                                AsyncImage(
                                    model = uiState.iconUrl,
                                    contentDescription = "Group photo",
                                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(Icons.Default.Group, contentDescription = "Group photo", modifier = Modifier.size(44.dp))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            uiState.name.ifBlank { "Group" },
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (uiState.isAdmin) {
                            IconButton(onClick = { onShowRename(true) }) {
                                Icon(Icons.Default.Edit, contentDescription = "Rename group")
                            }
                        }
                    }
                    Text(
                        "Group · ${uiState.members.size} members",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!uiState.description.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            uiState.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        GroupHeaderAction(icon = Icons.Default.Call, label = "Voice", onClick = onVoiceCall)
                        GroupHeaderAction(icon = Icons.Default.Videocam, label = "Video", onClick = onVideoCall)
                        GroupHeaderAction(icon = Icons.Default.PersonAdd, label = "Add", onClick = onShowAdd)
                        GroupHeaderAction(icon = Icons.Default.Search, label = "Search", onClick = onSearchChat)
                    }
                }
                if (uiState.isWorking) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                uiState.error?.let { err ->
                    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.errorContainer) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(err, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = onClearTransient) { Text("Dismiss") }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                uiState.inviteText?.let { inv ->
                    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Link, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(inv, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = onClearTransient) { Text("Done") }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // Media, links and docs (real sweep; browser entry).
            item {
                val total = uiState.mediaCount + uiState.docCount
                SectionRow(
                    icon = Icons.Default.PermMedia,
                    title = "Media, links and docs",
                    subtitle = when {
                        total > 0 -> "$total items · ${uiState.linkCount} links"
                        else -> "No media yet"
                    },
                    trailing = {
                        if (total > 0) {
                            Text(
                                total.toString(),
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
                            GroupMediaThumb(
                                thumb = thumb,
                                onOpen = { url ->
                                    onOpenMedia(url, thumb.mime ?: "", thumb.filename ?: "media")
                                }
                            )
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            }

            // Starred messages (real persisted ids + history bodies).
            item {
                SectionRow(
                    icon = Icons.Default.StarBorder,
                    title = "Starred messages",
                    subtitle = if (uiState.starred.isEmpty()) "None yet" else "${uiState.starred.size} starred",
                    onClick = onShowStarred
                )
                HorizontalDivider()
            }

            // Notifications (real persisted mute).
            item {
                ListItem(
                    headlineContent = { Text("Mute notifications") },
                    supportingContent = {
                        Text(if (uiState.isMuted) "Muted on this device" else "Notify for this group")
                    },
                    leadingContent = {
                        Icon(
                            if (uiState.isMuted) Icons.Default.NotificationsOff else Icons.Default.Notifications,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingContent = {
                        Switch(checked = uiState.isMuted, onCheckedChange = { onToggleMute() })
                    },
                    modifier = Modifier.clickable { onToggleMute() }
                )
                HorizontalDivider()
            }

            // Encryption (REAL room state, never claimed blindly).
            item {
                SectionRow(
                    icon = Icons.Default.Lock,
                    title = "Encryption",
                    subtitle = if (uiState.isEncrypted) {
                        "Messages are end-to-end encrypted. Tap to learn more."
                    } else {
                        "Standard transport protection. Tap to learn more."
                    },
                    onClick = onShowEncryption
                )
                HorizontalDivider()
            }

            // Advanced chat privacy -> real privacy settings.
            item {
                SectionRow(
                    icon = Icons.Default.Security,
                    title = "Advanced chat privacy",
                    subtitle = "Manage in privacy settings",
                    onClick = onPrivacy
                )
                HorizontalDivider()
            }

            // Create a similar group (prefilled creation flow).
            item {
                SectionRow(
                    icon = Icons.Default.GroupAdd,
                    title = "Create a similar group",
                    subtitle = "Start with the same members",
                    onClick = {
                        val ids = uiState.members.map { it.principalId }.filter { it.isNotBlank() }
                        onSimilarGroup(ids.joinToString(","), uiState.name)
                    }
                )
                HorizontalDivider()
            }

            // Member section: count + search + add + You-first rows + view-all.
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)
                ) {
                    Text(
                        "${uiState.members.size} members",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        onMemberSearchOpen(!memberSearchOpen)
                    }) {
                        Icon(Icons.Default.Search, contentDescription = "Search members")
                    }
                }
            }
            if (memberSearchOpen) {
                item {
                    OutlinedTextField(
                        value = memberQuery,
                        onValueChange = onMemberQuery,
                        placeholder = { Text("Search members…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
            if (uiState.isAdmin) {
                item {
                    ListItem(
                        headlineContent = { Text("Add member") },
                        leadingContent = {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(Icons.Default.PersonAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        },
                        modifier = Modifier.clickable(onClick = onShowAdd)
                    )
                    HorizontalDivider()
                }
            }
            if (shownMembers.isEmpty()) {
                item {
                    Text(
                        if (uiState.members.isEmpty()) "No members loaded - pull to refresh."
                        else "No members match \"$memberQuery\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
            items(previewMembers, key = { it.principalId }) { member ->
                MemberRow(
                    member = member,
                    selfId = uiState.selfId,
                    isAdminViewer = uiState.isAdmin,
                    onClick = { if (uiState.isAdmin) onMemberMenu(member) }
                )
            }
            if (hiddenCount > 0) {
                item {
                    TextButton(
                        onClick = { onShowAllMembers(true) },
                        modifier = Modifier.padding(start = 16.dp)
                    ) { Text("View all ($hiddenCount more)") }
                }
            }

            // Action rows: changes / favourites / lists / clear / report / exit.
            item {
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                SectionRow(
                    icon = Icons.Default.History,
                    title = "See member changes",
                    subtitle = if (uiState.events.isEmpty()) "No recorded changes yet" else "${uiState.events.size} events",
                    onClick = onShowEvents
                )
                HorizontalDivider()
                SectionRow(
                    icon = if (uiState.isFavourite) Icons.Default.Star else Icons.Default.StarBorder,
                    title = if (uiState.isFavourite) "Remove from favourites" else "Add to favourites",
                    subtitle = if (uiState.isFavourite) "Favourited on this device" else "Pin to favourites",
                    onClick = onToggleFavourite
                )
                HorizontalDivider()
                SectionRow(
                    icon = Icons.Default.Label,
                    title = "Add to list",
                    subtitle = if (uiState.memberOfLists.isEmpty()) "Organise chats into lists"
                    else "In: ${uiState.memberOfLists.sorted().joinToString(", ")}",
                    onClick = { onShowLists(true) }
                )
                HorizontalDivider()
                SectionRow(
                    icon = Icons.Default.DeleteSweep,
                    title = "Clear chat",
                    subtitle = "Hide messages on this device; group stays",
                    onClick = { onShowClear(true) }
                )
                HorizontalDivider()
                SectionRow(
                    icon = Icons.Default.Flag,
                    title = "Report group",
                    subtitle = if (uiState.reportDone) "Report noted on this device" else "Report to moderators",
                    onClick = { onShowReport(true) }
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = {
                        Text("Exit group", color = MaterialTheme.colorScheme.error)
                    },
                    leadingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    modifier = Modifier.clickable { onShowLeave(true) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Add/remove/rename/icon/admin/invite/leave use conventional Ollacore room endpoints - rejections mean backend work is required.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    GroupInfoDialogs(
        uiState = uiState,
        showRename = showRename,
        showDesc = showDesc,
        showIcon = showIcon,
        showLeave = showLeave,
        showClear = showClear,
        showReport = showReport,
        showLists = showLists,
        showEncryption = showEncryption,
        memberMenu = memberMenu,
        iconPicker = { iconPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onRename = onRename,
        onDescription = onDescription,
        onIcon = onIcon,
        onRemoveMember = onRemoveMember,
        onSetAdmin = onSetAdmin,
        onLeave = onLeave,
        onClearChat = onClearChat,
        onReport = onReport,
        onSetRoomList = onSetRoomList,
        onShowRename = onShowRename,
        onShowDesc = onShowDesc,
        onShowIcon = onShowIcon,
        onShowLeave = onShowLeave,
        onShowClear = onShowClear,
        onShowReport = onShowReport,
        onShowLists = onShowLists,
        onShowEncryption = onShowEncryption,
        onMemberMenu = onMemberMenu,
        onClearTransient = onClearTransient
    )
}

/** Reference-style section row: icon + title + subtitle + chevron. */
@Composable
private fun SectionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        trailingContent = trailing ?: {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun GroupHeaderAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = Modifier.size(52.dp)
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun MemberRow(
    member: Participant,
    selfId: String?,
    isAdminViewer: Boolean,
    onClick: () -> Unit
) {
    val you = selfId != null && member.principalId == selfId
    ListItem(
        headlineContent = {
            Text(
                memberDisplayName(member),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = { Text(memberSubtitle(member, selfId)) },
        leadingContent = {
            com.ollacore.app.ui.theme.BrandAvatar(name = memberDisplayName(member), size = 40.dp)
        },
        trailingContent = {
            if (isGroupAdmin(member)) {
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer) {
                    Text("admin", modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        modifier = if (isAdminViewer || you) Modifier.clickable(onClick = onClick) else Modifier
    )
    HorizontalDivider()
}

@Composable
private fun GroupMediaThumb(
    thumb: ScannedMediaItem,
    onOpen: (String) -> Unit
) {
    val url = thumb.filename
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = thumb.downloadUrl != null) {
                thumb.downloadUrl?.let { onOpen(it) }
            },
        contentAlignment = Alignment.Center
    ) {
        if (thumb.downloadUrl != null && (thumb.mime?.startsWith("image/") == true || thumb.kind.equals("image", ignoreCase = true))) {
            AsyncImage(
                model = thumb.downloadUrl,
                contentDescription = url,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxSize()) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        when {
                            thumb.kind.equals("video", ignoreCase = true) -> Icons.Default.Videocam
                            thumb.kind.equals("audio", ignoreCase = true) ||
                                thumb.kind.equals("voice_note", ignoreCase = true) -> Icons.Default.Mic
                            else -> Icons.Default.Description
                        },
                        contentDescription = url,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
        thumb.durationMs?.takeIf { it > 0 }?.let { ms ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
            ) {
                Text(
                    formatThumbDuration(ms),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
    }
}

private fun formatThumbDuration(ms: Long): String {
    val s = (ms / 1000).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

/** Copies a picked group icon into app cache for upload. */
private fun copyGroupIconToCache(context: android.content.Context, uri: android.net.Uri): File? {
    return try {
        val dir = File(context.cacheDir, "group_icons").apply { mkdirs() }
        val target = File(dir, "group_${UUID.randomUUID()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        if (target.length() == 0L) {
            target.delete()
            return null
        }
        target.absolutePath.let { target }
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun GroupInfoDialogs(
    uiState: GroupInfoUiState,
    showRename: Boolean,
    showDesc: Boolean,
    showIcon: Boolean,
    showLeave: Boolean,
    showClear: Boolean,
    showReport: Boolean,
    showLists: Boolean,
    showEncryption: Boolean,
    memberMenu: Participant?,
    iconPicker: () -> Unit,
    onRename: (String) -> Unit,
    onDescription: (String) -> Unit,
    onIcon: (String) -> Unit,
    onRemoveMember: (String) -> Unit,
    onSetAdmin: (String, Boolean) -> Unit,
    onLeave: () -> Unit,
    onClearChat: () -> Unit,
    onReport: (String) -> Unit,
    onSetRoomList: (String, Boolean) -> Unit,
    onShowRename: (Boolean) -> Unit,
    onShowDesc: (Boolean) -> Unit,
    onShowIcon: (Boolean) -> Unit,
    onShowLeave: (Boolean) -> Unit,
    onShowClear: (Boolean) -> Unit,
    onShowReport: (Boolean) -> Unit,
    onShowLists: (Boolean) -> Unit,
    onShowEncryption: () -> Unit,
    onMemberMenu: (Participant?) -> Unit,
    onClearTransient: () -> Unit
) {
    if (showRename) {
        var v by remember { mutableStateOf(uiState.name) }
        AlertDialog(
            onDismissRequest = { onShowRename(false) },
            title = { Text("Rename group") },
            text = {
                OutlinedTextField(value = v, onValueChange = { v = it }, label = { Text("Group name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = { TextButton(onClick = { onRename(v.trim()); onShowRename(false) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { onShowRename(false) }) { Text("Cancel") } }
        )
    }
    if (showDesc) {
        var v by remember { mutableStateOf(uiState.description ?: "") }
        AlertDialog(
            onDismissRequest = { onShowDesc(false) },
            title = { Text("Group description") },
            text = {
                OutlinedTextField(value = v, onValueChange = { v = it }, label = { Text("Description") }, maxLines = 3, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = { TextButton(onClick = { onDescription(v.trim()); onShowDesc(false) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { onShowDesc(false) }) { Text("Cancel") } }
        )
    }
    if (showIcon) {
        var v by remember { mutableStateOf(uiState.iconUrl ?: "") }
        AlertDialog(
            onDismissRequest = { onShowIcon(false) },
            title = { Text("Group icon") },
            text = {
                Column {
                    FilledTonalButton(onClick = iconPicker, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Pick a photo to upload")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = v, onValueChange = { v = it }, label = { Text("…or paste an icon URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { onIcon(v.trim()); onShowIcon(false) }) { Text("Save URL") } },
            dismissButton = { TextButton(onClick = { onShowIcon(false) }) { Text("Cancel") } }
        )
    }
    memberMenu?.let { member ->
        // Admin-gated upstream: this menu only opens for admins (see MemberRow).
        val isAdmin = member.role.equals("admin", ignoreCase = true)
        AlertDialog(
            onDismissRequest = { onMemberMenu(null) },
            title = { Text(memberDisplayName(member)) },
            text = {
                Column {
                    Text(memberSubtitle(member, uiState.selfId), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    ListItem(headlineContent = { Text(if (isAdmin) "Dismiss as admin" else "Make group admin") }, leadingContent = { Icon(Icons.Default.AdminPanelSettings, null) },
                        modifier = Modifier.clickable { onSetAdmin(member.principalId, !isAdmin); onMemberMenu(null) })
                    ListItem(headlineContent = { Text("Remove from group", color = MaterialTheme.colorScheme.error) }, leadingContent = { Icon(Icons.Default.PersonRemove, null, tint = MaterialTheme.colorScheme.error) },
                        modifier = Modifier.clickable { onRemoveMember(member.principalId); onMemberMenu(null) })
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { onMemberMenu(null) }) { Text("Cancel") } }
        )
    }
    if (showLists) {
        var v by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { onShowLists(false) },
            title = { Text("Add to list") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (uiState.allLists.isEmpty()) {
                        Text("No lists yet - type a name below to create one.", style = MaterialTheme.typography.bodyMedium)
                    }
                    uiState.allLists.keys.sorted().forEach { name ->
                        val member = name in uiState.memberOfLists
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSetRoomList(name, !member) }
                                .padding(vertical = 6.dp)
                        ) {
                            Checkbox(checked = member, onCheckedChange = { onSetRoomList(name, it) })
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(
                                "${uiState.allLists[name]?.size ?: 0}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = v,
                        onValueChange = { if (it.length <= 40) v = it },
                        label = { Text("New list name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (v.trim().isNotEmpty()) {
                            onSetRoomList(v.trim(), true)
                            v = ""
                        }
                    },
                    enabled = v.trim().isNotEmpty()
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { onShowLists(false) }) { Text("Done") } }
        )
    }
    if (showReport) {
        var reason by remember { mutableStateOf("Spam") }
        AlertDialog(
            onDismissRequest = { onShowReport(false) },
            title = { Text("Report group?") },
            text = {
                Column {
                    listOf("Spam", "Harassment", "Inappropriate content", "Other").forEach { option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { reason = option }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(selected = reason == option, onClick = { reason = option })
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(option, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "The report is queued on this device until the report endpoint lands.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { TextButton(onClick = { onReport(reason); onShowReport(false) }) { Text("Submit report") } },
            dismissButton = { TextButton(onClick = { onShowReport(false) }) { Text("Cancel") } }
        )
    }
    if (showClear) {
        AlertDialog(
            onDismissRequest = { onShowClear(false) },
            title = { Text("Clear chat?") },
            text = {
                Text("Messages will be hidden on this device. The group, its members and server history stay untouched.")
            },
            confirmButton = { TextButton(onClick = { onClearChat(); onShowClear(false); onClearTransient() }) { Text("Clear chat") } },
            dismissButton = { TextButton(onClick = { onShowClear(false) }) { Text("Cancel") } }
        )
    }
    if (showLeave) {
        AlertDialog(
            onDismissRequest = { onShowLeave(false) },
            title = { Text("Exit group?") },
            text = { Text("You will no longer receive messages from this group.") },
            confirmButton = { TextButton(onClick = { onLeave(); onShowLeave(false) }) { Text("Exit group", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { onShowLeave(false) }) { Text("Cancel") } }
        )
    }
    if (showEncryption) {
        AlertDialog(
            onDismissRequest = onShowEncryption,
            title = { Text("Encryption") },
            text = {
                Text(
                    if (uiState.isEncrypted) {
                        "Messages in this group are end-to-end encrypted. Only members hold the keys; each session rotation mints fresh epoch keys. Verify safety details with members out-of-band."
                    } else {
                        "This group does not advertise end-to-end encryption for this session. Messages still travel over TLS to Ollacore servers."
                    }
                )
            },
            confirmButton = { TextButton(onClick = onShowEncryption) { Text("Got it") } },
            dismissButton = {}
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupStarredScreen(
    groupName: String,
    starred: List<GroupStarredMsg>,
    onBack: () -> Unit,
    onOpen: (String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Starred messages")
                        Text(
                            groupName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to group info")
                    }
                }
            )
        }
    ) { padding ->
        if (starred.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Icon(Icons.Default.StarBorder, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("No starred messages", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Long-press any group message and tap Star.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(starred, key = { it.messageId }) { item ->
                    ListItem(
                        headlineContent = { Text(item.preview, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("${item.sender} · ${item.at}") },
                        leadingContent = {
                            Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        },
                        modifier = Modifier.clickable { onOpen(item.messageId) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupEventsScreen(
    groupName: String,
    events: List<com.ollacore.app.data.local.GroupEvent>,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Member changes")
                        Text(
                            groupName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to group info")
                    }
                }
            )
        }
    ) { padding ->
        if (events.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("No member changes yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Actions taken on this device are journaled here. A server audit log will also appear here when it lands.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(events, key = { it.id }) { event ->
                    ListItem(
                        headlineContent = { Text(event.action) },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    event.detail,
                                    "by ${event.actor}",
                                    formatEventAt(event.at)
                                ).joinToString(" · ")
                            )
                        },
                        leadingContent = {
                            Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun formatEventAt(at: Long): String {
    if (at <= 0L) return ""
    return runCatching {
        val z = java.time.ZoneId.systemDefault()
        java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm")
            .format(java.time.Instant.ofEpochMilli(at).atZone(z))
    }.getOrElse { "" }
}
