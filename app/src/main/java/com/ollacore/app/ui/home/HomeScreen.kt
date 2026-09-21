package com.ollacore.app.ui.home

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.ollacore.app.ui.theme.OllaPrimaryBlue
import com.ollacore.app.ui.theme.OllacoreLogo
import com.ollacore.app.ui.updates.UpdatesContent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class HomeTab { CHATS, UPDATES, CALLS, SETTINGS }

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
    onNewGroup: () -> Unit = {}
) {
    var tab by rememberSaveable { mutableStateOf(HomeTab.CHATS) }
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OllacoreLogo(size = 34.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            when (tab) {
                                HomeTab.CHATS -> "Ollacore"
                                HomeTab.UPDATES -> "Updates"
                                HomeTab.CALLS -> "Calls"
                                HomeTab.SETTINGS -> "Settings"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    if (tab == HomeTab.CHATS) {
                        IconButton(onClick = onSearch) {
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
                                    text = { Text("Linked devices") },
                                    onClick = { showMenu = false; onDevices() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Profile") },
                                    onClick = { showMenu = false; onProfile() }
                                )
                            }
                        }
                    }
                    if (tab == HomeTab.SETTINGS) {
                        IconButton(onClick = onProfile) {
                            Icon(Icons.Default.Person, contentDescription = "Profile")
                        }
                    }
                }
            )
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
            NavigationBar {
                // Spec 37: unread badge on Chats (small circular badge, real counts).
                val totalUnread = remember(uiState.inbox) { uiState.inbox.sumOf { it.unreadCount } }
                NavigationBarItem(
                    selected = tab == HomeTab.CHATS,
                    onClick = { tab = HomeTab.CHATS },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (totalUnread > 0) {
                                    Badge { Text(if (totalUnread > 99) "99+" else totalUnread.toString()) }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Chat, contentDescription = null)
                        }
                    },
                    label = { Text("Chats") }
                )
                NavigationBarItem(
                    selected = tab == HomeTab.UPDATES,
                    onClick = { tab = HomeTab.UPDATES },
                    icon = { Icon(Icons.Default.Update, contentDescription = null) },
                    label = { Text("Updates") }
                )
                NavigationBarItem(
                    selected = tab == HomeTab.CALLS,
                    onClick = { tab = HomeTab.CALLS },
                    icon = { Icon(Icons.Default.Call, contentDescription = null) },
                    label = { Text("Calls") }
                )
                NavigationBarItem(
                    selected = tab == HomeTab.SETTINGS,
                    onClick = { tab = HomeTab.SETTINGS },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Settings") }
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
                    modifier = Modifier.fillMaxSize()
                )
                HomeTab.UPDATES -> UpdatesContent(modifier = Modifier.fillMaxSize())
                HomeTab.CALLS -> CallHistoryContent(
                    log = callLog,
                    onCallBack = onCallBack,
                    onDelete = onDeleteCallLog,
                    onClearAll = onClearCallLog,
                    modifier = Modifier.fillMaxSize()
                )
                HomeTab.SETTINGS -> SettingsContent(
                    displayName = uiState.displayName,
                    phone = uiState.phone,
                    onProfile = onProfile,
                    onDevices = onDevices,
                    onPrivacy = onPrivacy,
                    onNotifications = onNotifications,
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
    modifier: Modifier = Modifier
) {
    var filter by rememberSaveable { mutableStateOf(ChatFilter.ALL) }
    val (isOnline, wasOffline) = com.ollacore.app.ui.common.rememberConnectivity()

    val visible = remember(uiState.inbox, filter) {
        when (filter) {
            ChatFilter.ALL -> uiState.inbox
            ChatFilter.UNREAD -> uiState.inbox.filter { it.unreadCount > 0 }
            ChatFilter.GROUPS -> uiState.inbox.filter { it.kind.equals("group", ignoreCase = true) }
            ChatFilter.CHANNELS -> emptyList()
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

        // Filter chips.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            ChatFilter.entries.forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { filter = option },
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
                    InboxItemRow(
                        item = item,
                        myUserId = uiState.myUserId,
                        onClick = { onConversationClick(item.roomId) }
                    )
                }
            }
        }
    }
}

@Composable
fun InboxItemRow(item: InboxItem, onClick: () -> Unit, myUserId: String? = null) {
    val title = item.name ?: item.peer?.displayName ?: item.peer?.phone ?: "Unknown"
    val isMine = myUserId != null && item.lastMessage?.senderId == myUserId
    val preview = (if (isMine) "You: " else "") + (item.lastMessage?.preview ?: "No messages yet")
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                BrandAvatar(name = title, size = 48.dp)
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
