package com.ollacore.app.ui.home

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ollacore.app.data.local.CallLogEntry
import com.ollacore.app.data.model.InboxItem
import com.ollacore.app.ui.calls.CallHistoryContent
import com.ollacore.app.ui.settings.SettingsContent
import com.ollacore.app.ui.theme.BrandAvatar
import com.ollacore.app.ui.theme.BrandGradient
import com.ollacore.app.ui.theme.OllacoreLogo
import com.ollacore.app.ui.updates.UpdatesContent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class HomeTab { CHATS, UPDATES, COMMUNITIES, CALLS }

/**
 * WhatsApp-reference layout tokens shared by Home tabs (Updates/Communities +
 * nav). Theme-derived (NOT hardcoded): identical usage sites render dark in
 * Dark mode and light in Light mode via MaterialTheme colorScheme.
 */
internal val WaBg: Color @Composable get() = MaterialTheme.colorScheme.background
internal val WaCard: Color @Composable get() = MaterialTheme.colorScheme.surface
internal val WaText: Color @Composable get() = MaterialTheme.colorScheme.onBackground
internal val WaSub: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
internal val WaGreen: Color @Composable get() = MaterialTheme.colorScheme.primary
internal val WaPill: Color @Composable get() = MaterialTheme.colorScheme.primaryContainer
internal val WaDivider: Color @Composable get() = MaterialTheme.colorScheme.outline

private enum class ChatFilter { ALL, UNREAD, GROUPS, CHANNELS }

/**
 * Phase 1 / spec-9 Home dashboard: logo title, search + menu actions,
 * big search bar, All/Unread/Groups/Channels chips, rich conversation
 * rows (avatar, name, You-prefix, timestamp, unread), bottom nav,
 * gradient FAB for New Chat.
 *
 * Honest gaps (no backend): per-row online/last-seen, receipt ticks,
 * pin/mute (client stores not built yet), Channels list, camera action
 * (no chat context to attach media to).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onConversationClick: (String) -> Unit,
    onNewChat: () -> Unit,
    onProfile: () -> Unit,
    onRefresh: () -> Unit,
    onSearch: () -> Unit = {},
    // Calls tab (CLIENT-ONLY history)
    callLog: List<CallLogEntry> = emptyList(),
    onCallBack: (CallLogEntry) -> Unit = {},
    onDeleteCallLog: (String) -> Unit = {},
    onClearCallLog: () -> Unit = {},
    // Settings tab + overflow menu
    onDevices: () -> Unit = {},
    onPrivacy: () -> Unit = {},
    onNotifications: () -> Unit = {},
    onNewGroup: () -> Unit = {},
    // Closed/archived chats (3-dot menu; hidden from main list, section below)
    archivedRooms: Set<String> = emptySet(),
    // WhatsApp-style list multi-selection (long-press rows to enter)
    selectedIds: Set<String> = emptySet(),
    pinnedRooms: Set<String> = emptySet(),
    mutedRooms: Set<String> = emptySet(),
    onToggleSelect: (String) -> Unit = {},
    onClearSelection: () -> Unit = {},
    onSelectAll: () -> Unit = {},
    onTogglePin: () -> Unit = {},
    onToggleMute: () -> Unit = {},
    onToggleArchive: () -> Unit = {},
    onDeleteSelected: () -> Unit = {},
    // Updates-tab overflow (Settings lives here now that tabs match the reference)
    onSettings: () -> Unit = {},
    // Reference home-menu parity: communities has no creation backend yet.
    onNewCommunity: () -> Unit = {},
    // Reference "Read all": really marks unread rooms read (progress + result).
    readAllState: ReadAllState = ReadAllState.Idle,
    onReadAll: () -> Unit = {},
    onConsumeReadAll: () -> Unit = {},
    // Reference overflow "Starred": cross-chat browser (own route).
    onStarred: () -> Unit = {},
    // Reference overflow "Payments": integration-point screen (no backend yet).
    onPayments: () -> Unit = {},
    // Reference overflow "Broadcast lists": local lists screen (send needs backend).
    onBroadcasts: () -> Unit = {},
    // Reference "Archived" screen entry + swipe gestures.
    onArchived: () -> Unit = {},
    onArchiveChat: (String) -> Unit = {},
    onUnarchiveChat: (String) -> Unit = {}
) {
    // Enums are not SaveableStateRegistry-compatible: persist the name, derive the tab.
    var tabName by rememberSaveable { mutableStateOf(HomeTab.CHATS.name) }
    var tab = runCatching { HomeTab.valueOf(tabName) }.getOrElse { HomeTab.CHATS }
    var showMenu by remember { mutableStateOf(false) }
    // Honest notes for reference-menu entries with no backend (Category 2):
    // shown as dialogs, never faked.
    var menuNote by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Selection mode lives on the CHATS tab only; anywhere else it clears.
    val selecting = selectedIds.isNotEmpty() && tab == HomeTab.CHATS
    var showSelMenu by remember { mutableStateOf(false) }
    var showDeleteChats by remember { mutableStateOf(false) }
    BackHandler(enabled = selecting) { onClearSelection() }

    // Pin/mute/archive icons reflect the whole selection (toggle semantics).
    val allPinned = selecting && selectedIds.all { it in pinnedRooms }
    val allMuted = selecting && selectedIds.all { it in mutedRooms }
    val allArchived = selecting && selectedIds.all { it in archivedRooms }

    Scaffold(
        containerColor = WaBg,
        topBar = {
            // Updates + Communities + Calls own their in-page headers (reference layout).
            if (tab != HomeTab.UPDATES && tab != HomeTab.COMMUNITIES && tab != HomeTab.CALLS) {
            if (selecting) {
                // WhatsApp-style selection toolbar replaces the normal bar
                // on the SAME screen (no separate route).
                TopAppBar(
                    title = {
                        Text(
                            selectedIds.size.toString(),
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onClearSelection) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Clear selection"
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onTogglePin) {
                            Icon(
                                Icons.Default.PushPin,
                                contentDescription = if (allPinned) "Unpin" else "Pin"
                            )
                        }
                        IconButton(onClick = { showDeleteChats = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                        IconButton(onClick = onToggleMute) {
                            Icon(
                                if (allMuted) Icons.Default.Notifications
                                else Icons.Default.NotificationsOff,
                                contentDescription = if (allMuted) "Unmute" else "Mute"
                            )
                        }
                        IconButton(onClick = onToggleArchive) {
                            Icon(
                                if (allArchived) Icons.Default.Unarchive else Icons.Default.Archive,
                                contentDescription = if (allArchived) "Unarchive" else "Archive"
                            )
                        }
                        Box {
                            IconButton(onClick = { showSelMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(
                                expanded = showSelMenu,
                                onDismissRequest = { showSelMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Select all") },
                                    onClick = { showSelMenu = false; onSelectAll() }
                                )
                            }
                        }
                    }
                )
            } else {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OllacoreLogo(size = 34.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            when (tab) {
                                HomeTab.CHATS -> "Ollacore"
                                HomeTab.UPDATES -> "Updates"
                                HomeTab.COMMUNITIES -> "Communities"
                                HomeTab.CALLS -> "Calls"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    if (tab == HomeTab.CHATS) {
                        IconButton(
                            onClick = onSearch,
                            modifier = Modifier.testTag("search_icon")
                        ) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("New chat") },
                                    onClick = { showMenu = false; onNewChat() }
                                )
                                DropdownMenuItem(
                                    text = { Text("New group") },
                                    onClick = { showMenu = false; onNewGroup() }
                                )
                                DropdownMenuItem(
                                    text = { Text("New community") },
                                    onClick = { showMenu = false; onNewCommunity() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Broadcast lists") },
                                    onClick = { showMenu = false; onBroadcasts() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Linked devices") },
                                    onClick = { showMenu = false; onDevices() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Starred") },
                                    onClick = { showMenu = false; onStarred() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Payments") },
                                    onClick = { showMenu = false; onPayments() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Read all") },
                                    onClick = { showMenu = false; onReadAll() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Settings") },
                                    onClick = { showMenu = false; onSettings() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Profile") },
                                    onClick = { showMenu = false; onProfile() }
                                )
                            }
                            menuNote?.let { (title, body) ->
                                AlertDialog(
                                    onDismissRequest = { menuNote = null },
                                    title = { Text(title) },
                                    text = { Text(body) },
                                    confirmButton = {
                                        TextButton(onClick = { menuNote = null }) { Text("Got it") }
                                    }
                                )
                            }
                            // Reference "Read all" states: working progress + result.
                            when (val ra = readAllState) {
                                is ReadAllState.Working -> AlertDialog(
                                    onDismissRequest = {},
                                    title = { Text("Marking all read") },
                                    text = {
                                        Column {
                                            Text("Sending read receipts… ${ra.done} of ${ra.total}")
                                            Spacer(modifier = Modifier.height(12.dp))
                                            LinearProgressIndicator(
                                                progress = { ra.done.toFloat() / ra.total.coerceAtLeast(1).toFloat() },
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    },
                                    confirmButton = {}
                                )
                                is ReadAllState.Done -> AlertDialog(
                                    onDismissRequest = onConsumeReadAll,
                                    title = { Text("Read all") },
                                    text = {
                                        Text(
                                            if (ra.marked == 0 && ra.failed == 0) "No unread chats - nothing to mark."
                                            else "Marked ${ra.marked} chat(s) read." +
                                                if (ra.failed > 0) " ${ra.failed} failed - open them to retry." else ""
                                        )
                                    },
                                    confirmButton = {
                                        TextButton(onClick = onConsumeReadAll) { Text("OK") }
                                    }
                                )
                                ReadAllState.Idle -> Unit
                            }
                        }
                    }
                }
            )
            } // end normal toolbar else
            } // end non-Updates/Communities top bar
            if (showDeleteChats && selecting) {
                AlertDialog(
                    onDismissRequest = { showDeleteChats = false },
                    title = { Text("Delete ${selectedIds.size} chats?") },
                    text = {
                        Text(
                            "Messages in these chats will be cleared on this device " +
                                "and the chats archived. Server history is unchanged."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showDeleteChats = false
                            onDeleteSelected()
                        }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteChats = false }) { Text("Cancel") }
                    }
                )
            }
        },
        floatingActionButton = {
            if (tab == HomeTab.CHATS) {
                // Spec: FAB uses the Ollacore gradient.
                FloatingActionButton(
                    onClick = onNewChat,
                    containerColor = Color.Transparent,
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(BrandGradient)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New Chat", tint = Color.White)
                }
            }
        },
        bottomBar = {
            // Reference look: near-black bar, dark-green pill behind the active tab.
            NavigationBar(containerColor = WaBg) {
                // Bottom Chats badge = DISTINCT conversations with unread > 0
                // (never the message sum): Alice 2 + Bob 3 shows 2, not 5.
                val totalUnread = remember(uiState.inbox, archivedRooms) {
                    com.ollacore.app.data.util.unreadConversationCount(uiState.inbox, archivedRooms)
                }
                val itemColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    unselectedIconColor = WaSub,
                    unselectedTextColor = WaSub,
                    indicatorColor = WaPill
                )
                NavigationBarItem(
                    selected = tab == HomeTab.CHATS,
                    onClick = { tabName = HomeTab.CHATS.name },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (totalUnread > 0) {
                                    Badge(containerColor = WaGreen, contentColor = MaterialTheme.colorScheme.onPrimary) {
                                        Text(if (totalUnread > 99) "99+" else totalUnread.toString())
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Chat, contentDescription = null)
                        }
                    },
                    label = { Text("Chats") },
                    colors = itemColors
                )
                NavigationBarItem(
                    selected = tab == HomeTab.UPDATES,
                    onClick = { tabName = HomeTab.UPDATES.name },
                    icon = { Icon(Icons.Default.Update, contentDescription = null) },
                    label = { Text("Updates") },
                    colors = itemColors
                )
                NavigationBarItem(
                    selected = tab == HomeTab.COMMUNITIES,
                    onClick = { tabName = HomeTab.COMMUNITIES.name },
                    icon = { Icon(Icons.Default.Groups, contentDescription = null) },
                    label = { Text("Communities") },
                    colors = itemColors
                )
                NavigationBarItem(
                    selected = tab == HomeTab.CALLS,
                    onClick = { tabName = HomeTab.CALLS.name },
                    icon = { Icon(Icons.Default.Call, contentDescription = null) },
                    label = { Text("Calls") },
                    colors = itemColors
                )
            }
        }
    ) { padding ->
        // Spec 36: subtle crossfade between destinations (no excessive motion).
        androidx.compose.animation.AnimatedContent(
            targetState = tab,
            label = "home-tab",
            modifier = Modifier.padding(padding)
        ) { current ->
            when (current) {
                HomeTab.CHATS -> ChatsContent(
                    uiState = uiState,
                    onConversationClick = onConversationClick,
                    onSearch = onSearch,
                    onRefresh = onRefresh,
                    archivedRooms = archivedRooms,
                    selectedIds = selectedIds,
                    pinnedRooms = pinnedRooms,
                    onToggleSelect = onToggleSelect,
                    onArchived = onArchived,
                    onArchiveChat = onArchiveChat,
                    onUnarchiveChat = onUnarchiveChat,
                    modifier = Modifier.fillMaxSize()
                )
                HomeTab.UPDATES -> com.ollacore.app.ui.updates.UpdatesContent(
                    onSearch = onSearch,
                    onSettings = onSettings,
                    onPrivacy = onPrivacy,
                    modifier = Modifier.fillMaxSize()
                )
                HomeTab.COMMUNITIES -> com.ollacore.app.ui.communities.CommunitiesContent(
                    modifier = Modifier.fillMaxSize()
                )
                HomeTab.CALLS -> CallHistoryContent(
                    log = callLog,
                    onCallBack = onCallBack,
                    onDelete = onDeleteCallLog,
                    onClearAll = onClearCallLog,
                    onSearch = onSearch,
                    onSettings = onSettings,
                    onNewCall = onNewChat,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun ChatsContent(
    uiState: HomeUiState,
    onConversationClick: (String) -> Unit,
    onSearch: () -> Unit,
    onRefresh: () -> Unit = {},
    archivedRooms: Set<String> = emptySet(),
    selectedIds: Set<String> = emptySet(),
    pinnedRooms: Set<String> = emptySet(),
    onToggleSelect: (String) -> Unit = {},
    onArchived: () -> Unit = {},
    onArchiveChat: (String) -> Unit = {},
    onUnarchiveChat: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Same SaveableStateRegistry rule as tabs: persist the enum name, not the enum.
    var filterName by rememberSaveable { mutableStateOf(ChatFilter.ALL.name) }
    val filter = runCatching { ChatFilter.valueOf(filterName) }.getOrElse { ChatFilter.ALL }
    val haptics = LocalHapticFeedback.current
    // Tap toggles while selecting, opens otherwise; long-press always selects.
    fun onTap(roomId: String) {
        if (selectedIds.isNotEmpty()) onToggleSelect(roomId)
        else onConversationClick(roomId)
    }
    fun onLongPress(roomId: String) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onToggleSelect(roomId)
    }
    val (isOnline, wasOffline) = com.ollacore.app.ui.common.rememberConnectivity()

    // Closed chats never appear in the main list; they live in Archived below.
    val unarchived = remember(uiState.inbox, archivedRooms) {
        uiState.inbox.filter { it.roomId !in archivedRooms }
    }
    val archivedItems = remember(uiState.inbox, archivedRooms) {
        uiState.inbox.filter { it.roomId in archivedRooms }
    }
    val visible = remember(unarchived, filter, pinnedRooms) {
        val base = when (filter) {
            ChatFilter.ALL -> unarchived
            ChatFilter.UNREAD -> unarchived.filter { it.unreadCount > 0 }
            ChatFilter.GROUPS -> unarchived.filter { it.kind.equals("group", ignoreCase = true) }
            ChatFilter.CHANNELS -> emptyList()
        }
        // Pinned chats float to the top (stable otherwise).
        base.sortedWith(compareBy({ it.roomId !in pinnedRooms }))
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Spec 35: subtle offline indicator (never blocks the list).
        com.ollacore.app.ui.common.OfflineBanner(isOnline = isOnline, wasOffline = wasOffline)
        // Large rounded search bar -> global search.
        Card(
            onClick = onSearch,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    "Search chats, messages…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Filter chips.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            ChatFilter.entries.forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { filterName = option.name },
                    label = {
                        Text(
                            when (option) {
                                ChatFilter.ALL -> "All"
                                ChatFilter.UNREAD -> "Unread"
                                ChatFilter.GROUPS -> "Groups"
                                ChatFilter.CHANNELS -> "Channels"
                            }
                        )
                    }
                )
            }
        }

        // Spec 33/34: skeletons while loading, friendly error with retry.
        if (uiState.error != null && uiState.inbox.isEmpty() && !uiState.isLoading) {
            com.ollacore.app.ui.common.ErrorState(
                message = uiState.error,
                onRetry = onRefresh
            )
        } else if (uiState.isLoading && uiState.inbox.isEmpty()) {
            com.ollacore.app.ui.common.SkeletonList(rows = 7)
        } else if (filter == ChatFilter.CHANNELS) {
            // Honest empty state: channels need the backend service.
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(88.dp)
                            .clip(CircleShape)
                            .background(BrandGradient)
                    ) {
                        Icon(Icons.Default.Chat, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("No channels yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Channels are a one-way broadcast service that needs backend support - see OLLACORE-BACKEND-SPEC.txt.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (visible.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        when (filter) {
                            ChatFilter.UNREAD -> "No unread chats"
                            ChatFilter.GROUPS -> "No groups yet"
                            else -> "No chats yet"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Your conversations will appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(visible, key = { it.roomId }) { item ->
                    SwipeableChatRow(
                        roomId = item.roomId,
                        archived = false,
                        onArchive = onArchiveChat,
                        onUnarchive = onUnarchiveChat
                    ) {
                        InboxItemRow(
                            item = item,
                            myUserId = uiState.myUserId,
                            isSelected = item.roomId in selectedIds,
                            isPinned = item.roomId in pinnedRooms,
                            onClick = { onTap(item.roomId) },
                            onLongClick = { onLongPress(item.roomId) }
                        )
                    }
                }
                // Archived entry: opens the dedicated screen (reference flow).
                if (archivedItems.isNotEmpty()) {
                    item(key = "archived-header") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onArchived() }
                                .padding(horizontal = 20.dp, vertical = 12.dp)
                        ) {
                            Icon(
                                Icons.Default.Archive,
                                contentDescription = "Archived chats",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                "Archived (${archivedItems.size})",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                Icons.Filled.KeyboardArrowRight,
                                contentDescription = "Open archived",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InboxItemRow(
    item: InboxItem,
    onClick: () -> Unit,
    myUserId: String? = null,
    isSelected: Boolean = false,
    isPinned: Boolean = false,
    onLongClick: (() -> Unit)? = null
) {
    val title = item.name ?: item.peer?.displayName ?: item.peer?.phone ?: "Unknown"
    val isMine = myUserId != null && item.lastMessage?.senderId == myUserId
    val preview = (if (isMine) "You: " else "") + (item.lastMessage?.preview ?: "No messages yet")
    // Reference highlight: light green in light mode, deep green in dark
    // mode (derived from the background luminance, so forced themes work).
    val darkBg = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val selectedBg = if (darkBg) Color(0xFF0B3B2E) else Color(0xFFD9FDD3)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { onLongClick?.invoke() }
            )
            .testTag("inbox_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) selectedBg else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (item.unreadCount > 0) 3.dp else 1.dp
        )
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (item.unreadCount > 0) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            supportingContent = {
                Text(
                    text = preview,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (item.unreadCount > 0) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            leadingContent = {
                Box(contentAlignment = Alignment.BottomEnd) {
                    BrandAvatar(name = title, size = 48.dp)
                    // Reference checkmark over the avatar while selected.
                    if (isSelected) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00A884))
                                .border(2.dp, Color.White, CircleShape)
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            },
            trailingContent = {
                Column(horizontalAlignment = Alignment.End) {
                    chatStamp(item.lastMessage?.createdAt)?.let { stamp ->
                        Text(
                            stamp,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (item.unreadCount > 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    if (item.unreadCount > 0) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    ) {
                            Text(
                                item.unreadCount.toString(),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (isPinned) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = "Pinned",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        )
    }
}

/** WhatsApp-style stamp: time today, "Yesterday", else short date. Null-safe. */
private fun chatStamp(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        val instant = try {
            Instant.parse(raw)
        } catch (_: Exception) {
            val n = raw.toLong()
            Instant.ofEpochMilli(if (n < 1_000_000_000_000L) n * 1000 else n)
        }
        val zone = ZoneId.systemDefault()
        val date = instant.atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        when {
            date.isEqual(today) -> DateTimeFormatter.ofPattern("HH:mm").format(instant.atZone(zone))
            date.isEqual(today.minusDays(1)) -> "Yesterday"
            date.year == today.year -> DateTimeFormatter.ofPattern("dd/MM").format(instant.atZone(zone))
            else -> DateTimeFormatter.ofPattern("dd/MM/yy").format(instant.atZone(zone))
        }
    }.getOrNull() ?: runCatching {
        DateUtils.getRelativeTimeSpanString(
            Instant.parse(raw).toEpochMilli(),
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        ).toString()
    }.getOrNull()
}
