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
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
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
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ollacore.app.data.local.CallLogEntry
import com.ollacore.app.data.model.InboxItem
import com.ollacore.app.ui.contact.ChatListBuilderSheet
import com.ollacore.app.ui.contact.NewListIntroContent
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

/** Replaced by [HomeFilter] (adds Favorites + custom lists); kept out to avoid dead code. */

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
    onUnarchiveChat: (String) -> Unit = {},
    // Favourites + custom chat lists (CLIENT-ONLY ChatPrefsStore sets).
    favouriteRooms: Set<String> = emptySet(),
    chatLists: Map<String, List<String>> = emptyMap(),
    onToggleFavourite: () -> Unit = {},
    onCreateChatList: (String, List<String>, (Boolean) -> Unit) -> Unit = { _, _, done -> done(false) }
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
    val allFavourited = selecting && selectedIds.all { it in favouriteRooms }

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
                        IconButton(onClick = onToggleFavourite) {
                            Icon(
                                if (allFavourited) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = if (allFavourited) "Remove from favorites" else "Add to favorites"
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
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                modifier = Modifier
                                    .width(260.dp)
                                    .background(
                                        color = Color(0xFF1F2C34),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = Color(0xFF2A3942),
                                        shape = RoundedCornerShape(12.dp)
                                    ),
                                offset = DpOffset(x = 0.dp, y = 4.dp)
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "New group",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Normal,
                                            color = Color(0xFFE9EDEF)
                                        )
                                    },
                                    onClick = { showMenu = false; onNewGroup() },
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "New community",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Normal,
                                            color = Color(0xFFE9EDEF)
                                        )
                                    },
                                    onClick = { showMenu = false; onNewCommunity() },
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Broadcast lists",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Normal,
                                            color = Color(0xFFE9EDEF)
                                        )
                                    },
                                    onClick = { showMenu = false; onBroadcasts() },
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Linked devices",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Normal,
                                            color = Color(0xFFE9EDEF)
                                        )
                                    },
                                    onClick = { showMenu = false; onDevices() },
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Starred",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Normal,
                                            color = Color(0xFFE9EDEF)
                                        )
                                    },
                                    onClick = { showMenu = false; onStarred() },
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Payments",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Normal,
                                            color = Color(0xFFE9EDEF)
                                        )
                                    },
                                    onClick = { showMenu = false; onPayments() },
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Read all",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Normal,
                                            color = Color(0xFFE9EDEF)
                                        )
                                    },
                                    onClick = { showMenu = false; onReadAll() },
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Settings",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Normal,
                                            color = Color(0xFFE9EDEF)
                                        )
                                    },
                                    onClick = { showMenu = false; onSettings() },
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
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
                    favouriteRooms = favouriteRooms,
                    chatLists = chatLists,
                    onToggleSelect = onToggleSelect,
                    onArchived = onArchived,
                    onArchiveChat = onArchiveChat,
                    onUnarchiveChat = onUnarchiveChat,
                    onCreateChatList = onCreateChatList,
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

/**
 * Chats filter model. Built-ins map to stable keys; custom lists carry the
 * user list name (store is name-keyed; duplicates rejected case-insensitively
 * at creation, so the name is effectively stable).
 */
private sealed interface HomeFilter {
    data object All : HomeFilter
    data object Unread : HomeFilter
    data object Favorites : HomeFilter
    data object Groups : HomeFilter
    data object Channels : HomeFilter
    data class Custom(val name: String) : HomeFilter

    fun key(): String = when (this) {
        All -> "ALL"
        Unread -> "UNREAD"
        Favorites -> "FAVORITES"
        Groups -> "GROUPS"
        Channels -> "CHANNELS"
        is Custom -> "LIST:$name"
    }

    companion object {
        fun fromKey(key: String): HomeFilter = when {
            key == "UNREAD" -> Unread
            key == "FAVORITES" -> Favorites
            key == "GROUPS" -> Groups
            key == "CHANNELS" -> Channels
            key.startsWith("LIST:") -> Custom(key.removePrefix("LIST:"))
            else -> All
        }
    }
}

/** Single-line chip label: never wraps, truncates with ellipsis instead. */
@Composable
private fun FilterLabel(text: String) {
    Text(
        text,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatsContent(
    uiState: HomeUiState,
    onConversationClick: (String) -> Unit,
    onSearch: () -> Unit,
    onRefresh: () -> Unit = {},
    archivedRooms: Set<String> = emptySet(),
    selectedIds: Set<String> = emptySet(),
    pinnedRooms: Set<String> = emptySet(),
    favouriteRooms: Set<String> = emptySet(),
    chatLists: Map<String, List<String>> = emptyMap(),
    onToggleSelect: (String) -> Unit = {},
    onArchived: () -> Unit = {},
    onArchiveChat: (String) -> Unit = {},
    onUnarchiveChat: (String) -> Unit = {},
    onCreateChatList: (String, List<String>, (Boolean) -> Unit) -> Unit = { _, _, done -> done(false) },
    modifier: Modifier = Modifier
) {
    // Persist the stable filter key (SaveableStateRegistry-safe String).
    var filterKey by rememberSaveable { mutableStateOf(HomeFilter.All.key()) }
    // A custom filter whose list vanished falls back to All (never stranded).
    val filter = remember(filterKey, chatLists) {
        val parsed = HomeFilter.fromKey(filterKey)
        if (parsed is HomeFilter.Custom && parsed.name !in chatLists) HomeFilter.All else parsed
    }
    // New-list flow sheets.
    var showListIntro by rememberSaveable { mutableStateOf(false) }
    var showListBuilder by rememberSaveable { mutableStateOf(false) }
    var savingList by remember { mutableStateOf(false) }
    var listSaveError by remember { mutableStateOf<String?>(null) }
    val customNames = remember(chatLists) { chatLists.keys.sorted() }
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
    // Filtering only selects the displayed collection; inbox data is untouched.
    val visible = remember(unarchived, filter, pinnedRooms, favouriteRooms, chatLists) {
        val base = when (filter) {
            HomeFilter.All -> unarchived
            HomeFilter.Unread -> unarchived.filter { it.unreadCount > 0 }
            HomeFilter.Favorites -> unarchived.filter { it.roomId in favouriteRooms }
            HomeFilter.Groups -> unarchived.filter { it.kind.equals("group", ignoreCase = true) }
            HomeFilter.Channels -> emptyList()
            is HomeFilter.Custom -> {
                val members = chatLists[filter.name] ?: emptyList()
                unarchived.filter { it.roomId in members }
            }
        }
        // Pinned chats float to the top (stable otherwise).
        base.sortedWith(compareBy({ it.roomId !in pinnedRooms }))
    }
    // Titles for member picking (roomId -> display title).
    val chatTitles = remember(unarchived) {
        unarchived.map { item ->
            item.roomId to (item.name ?: item.peer?.displayName ?: item.peer?.phone ?: item.roomId)
        }
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

        // Filter chips: single scrollable row, labels never wrap.
        // Order: All, Unread, Favorites, Groups, Channels, custom lists, +.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            item(key = "filter-all") {
                FilterChip(
                    selected = filter == HomeFilter.All,
                    onClick = { filterKey = HomeFilter.All.key() },
                    label = { FilterLabel("All") }
                )
            }
            item(key = "filter-unread") {
                FilterChip(
                    selected = filter == HomeFilter.Unread,
                    onClick = { filterKey = HomeFilter.Unread.key() },
                    label = { FilterLabel("Unread") }
                )
            }
            item(key = "filter-favorites") {
                FilterChip(
                    selected = filter == HomeFilter.Favorites,
                    onClick = { filterKey = HomeFilter.Favorites.key() },
                    label = { FilterLabel("Favorites") }
                )
            }
            item(key = "filter-groups") {
                FilterChip(
                    selected = filter == HomeFilter.Groups,
                    onClick = { filterKey = HomeFilter.Groups.key() },
                    label = { FilterLabel("Groups") }
                )
            }
            item(key = "filter-channels") {
                FilterChip(
                    selected = filter == HomeFilter.Channels,
                    onClick = { filterKey = HomeFilter.Channels.key() },
                    label = { FilterLabel("Channels") }
                )
            }
            items(customNames, key = { "filter-list-$it" }) { name ->
                FilterChip(
                    selected = filter == HomeFilter.Custom(name),
                    onClick = { filterKey = HomeFilter.Custom(name).key() },
                    label = {
                        Text(
                            name,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 160.dp)
                        )
                    }
                )
            }
            item(key = "filter-add") {
                FilterChip(
                    selected = false,
                    onClick = { showListIntro = true },
                    label = {
                        Text(
                            "+",
                            maxLines = 1,
                            softWrap = false,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                )
            }
        }
        // New-list intro + builder sheets (creation flow, not decoration).
        if (showListIntro) {
            ModalBottomSheet(onDismissRequest = { showListIntro = false }) {
                NewListIntroContent(
                    onClose = { showListIntro = false },
                    onContinue = { showListIntro = false; showListBuilder = true }
                )
            }
        }
        if (showListBuilder) {
            ChatListBuilderSheet(
                chats = chatTitles,
                existingNames = chatLists.keys,
                saving = savingList,
                saveError = listSaveError,
                onSave = { name, memberIds ->
                    savingList = true
                    listSaveError = null
                    onCreateChatList(name, memberIds) { ok ->
                        savingList = false
                        if (ok) {
                            showListBuilder = false
                            // Jump straight to the new filter (immediate feedback).
                            filterKey = HomeFilter.Custom(name.trim().take(40)).key()
                        } else {
                            listSaveError = "Could not create this list. Try another name."
                        }
                    }
                },
                onDismiss = { showListBuilder = false; listSaveError = null }
            )
        }

        // Spec 33/34: skeletons while loading, friendly error with retry.
        if (uiState.error != null && uiState.inbox.isEmpty() && !uiState.isLoading) {
            com.ollacore.app.ui.common.ErrorState(
                message = uiState.error,
                onRetry = onRefresh
            )
        } else if (uiState.isLoading && uiState.inbox.isEmpty()) {
            com.ollacore.app.ui.common.SkeletonList(rows = 7)
        } else if (filter == HomeFilter.Channels) {
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
                            HomeFilter.Unread -> "No unread chats"
                            HomeFilter.Favorites -> "No favorites yet"
                            HomeFilter.Groups -> "No groups yet"
                            is HomeFilter.Custom -> "No chats in this list"
                            else -> "No chats yet"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        when (filter) {
                            HomeFilter.Favorites -> "Long-press chats and tap ★ to add favorites."
                            is HomeFilter.Custom -> "This list is empty."
                            else -> "Your conversations will appear here."
                        },
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
                            isFavorite = item.roomId in favouriteRooms,
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
    isFavorite: Boolean = false,
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
                    if (isFavorite) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Icon(
                            Icons.Default.Star,
                            contentDescription = "Favorite",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
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
