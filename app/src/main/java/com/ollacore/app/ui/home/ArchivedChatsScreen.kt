package com.ollacore.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.*
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ollacore.app.data.model.InboxItem

/**
 * Reference "Archived Chats" screen: archived-only list with search,
 * tap opens the chat (which unarchives via the shared mark-opened path),
 * trailing action (or swipe) unarchives in place. Empty + no-match
 * states included; back returns to Home.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedChatsScreen(
    items: List<InboxItem>,
    myUserId: String?,
    onOpenChat: (String) -> Unit,
    onUnarchive: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(items, query) {
        val q = query.trim()
        if (q.isEmpty()) items
        else items.filter {
            (it.name ?: it.peer?.displayName ?: "").contains(q, ignoreCase = true) ||
                (it.peer?.phone ?: "").contains(q) ||
                (it.lastMessage?.preview ?: "").contains(q, ignoreCase = true)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Archived") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        modifier = modifier
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search archived…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            )
            when {
                items.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Archive,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("No archived chats", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Closed chats rest here. Reopen one any time - it moves back to Chats.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                shown.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No archived chats match \"$query\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item(key = "arch-count") {
                        Text(
                            "${shown.size} archived chat(s)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                        )
                    }
                    items(shown, key = { "arch-" + it.roomId }) { item ->
                        SwipeableChatRow(
                            roomId = item.roomId,
                            archived = true,
                            onArchive = {},
                            onUnarchive = onUnarchive
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.weight(1f)) {
                                    InboxItemRow(
                                        item = item,
                                        myUserId = myUserId,
                                        onClick = { onOpenChat(item.roomId) }
                                    )
                                }
                                IconButton(onClick = { onUnarchive(item.roomId) }) {
                                    Icon(Icons.Default.Unarchive, contentDescription = "Unarchive")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Swipe right on a chat row archives (or unarchives) it, then snaps back.
 * Shared by the home list and the Archived screen; same gesture language
 * as the reference (swipe where appropriate).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableChatRow(
    roomId: String,
    archived: Boolean,
    onArchive: (String) -> Unit,
    onUnarchive: (String) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd) {
                if (archived) onUnarchive(roomId) else onArchive(roomId)
            }
            // Snap back: the action is applied, the row stays visible.
            false
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            // Subtle tint wash + icon; the row snaps back after the action.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 5.dp)
                    .background(
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
                        androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                    )
                    .padding(start = 20.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Icon(
                    if (archived) Icons.Default.Unarchive else Icons.Default.Archive,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        },
        modifier = modifier,
        content = { content() }
    )
}
