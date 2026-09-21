package com.ollacore.app.ui.globalsearch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ollacore.app.data.model.InboxItem

/**
 * Global Search (spec 22): "Search Ollacore…" with Chats | Messages | Media |
 * Images | Videos | Documents | Links | Audio chips, results grouped by
 * category with highlighted matches, plus recent searches.
 * Client-side fan-out over confirmed APIs (inbox + per-room search); media
 * tabs filter aggregated hits because no media-index endpoint exists.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GlobalSearchScreen(
    uiState: GlobalSearchUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onTab: (SearchTab) -> Unit,
    onOpenChat: (String) -> Unit,
    onBack: () -> Unit,
    onClear: () -> Unit,
    recentSearches: List<String> = emptyList(),
    onRecentClick: (String) -> Unit = {},
    onClearRecents: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = uiState.query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search Ollacore…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (uiState.query.isNotEmpty()) {
                        TextButton(onClick = onClear) { Text("Clear") }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            )

            ScrollableTabRow(selectedTabIndex = uiState.tab.ordinal, edgePadding = 12.dp) {
                SearchTab.entries.forEach { tab ->
                    Tab(
                        selected = uiState.tab == tab,
                        onClick = { onTab(tab) },
                        text = {
                            Text(
                                when (tab) {
                                    SearchTab.CHATS -> "Chats"
                                    SearchTab.MESSAGES -> "Messages"
                                    SearchTab.MEDIA -> "Media"
                                    SearchTab.IMAGES -> "Images"
                                    SearchTab.VIDEOS -> "Videos"
                                    SearchTab.DOCUMENTS -> "Documents"
                                    SearchTab.LINKS -> "Links"
                                    SearchTab.AUDIO -> "Audio"
                                }
                            )
                        }
                    )
                }
            }

            if (uiState.isSearching) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // Spec 33/34: skeletons while searching; friendly error with retry.
            if (uiState.isSearching && !uiState.searched) {
                com.ollacore.app.ui.common.SkeletonList(rows = 4)
            }
            if (uiState.error != null && !uiState.isSearching &&
                uiState.hits.isEmpty() && uiState.chats.isEmpty()
            ) {
                com.ollacore.app.ui.common.ErrorState(
                    message = uiState.error,
                    onRetry = onSearch
                )
            }

            uiState.error?.let { err ->
                Surface(modifier = Modifier.fillMaxWidth().padding(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Text(err, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }

            when {
                !uiState.searched -> {
                    if (recentSearches.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "Search across chats and messages.\nMedia tabs filter message results.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        // Recent searches (spec 22, CLIENT-ONLY).
                        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Recent searches",
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = onClearRecents) { Text("Clear") }
                            }
                            androidx.compose.foundation.layout.FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                recentSearches.forEach { recent ->
                                    AssistChip(
                                        onClick = {
                                            onQueryChange(recent)
                                            onRecentClick(recent)
                                        },
                                        label = { Text(recent) }
                                    )
                                }
                            }
                        }
                    }
                }
                uiState.tab == SearchTab.CHATS -> {
                    if (uiState.chats.isEmpty()) {
                        EmptyResult("No chats match")
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(uiState.chats, key = { it.roomId }) { item ->
                                ChatRow(
                                    item = item,
                                    query = uiState.query,
                                    onClick = { onOpenChat(item.roomId) }
                                )
                            }
                        }
                    }
                }
                else -> {
                    val hits = remember(uiState.hits, uiState.tab) {
                        uiState.hits.filter { GlobalSearchViewModel.matchesTab(it.message, uiState.tab) }
                    }
                    if (hits.isEmpty()) {
                        EmptyResult("Nothing found (${uiState.roomsScanned} chats scanned)")
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(hits, key = { it.message.id }) { hit ->
                                ListItem(
                                    headlineContent = {
                                        HighlightedText(
                                            full = GlobalSearchViewModel.captionFor(hit.message),
                                            query = uiState.query,
                                            maxLines = 1
                                        )
                                    },
                                    supportingContent = { Text("in ${hit.roomName}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    modifier = Modifier.clickable { onOpenChat(hit.roomId) }
                                )
                                HorizontalDivider()
                            }
                            item {
                                Text(
                                    "${hits.size} result(s) from ${uiState.roomsScanned} chats scanned",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyResult(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Spec 22: query matches render bold + primary inside results. */
@Composable
private fun HighlightedText(full: String, query: String, maxLines: Int = 1) {
    val annotated = remember(full, query) {
        androidx.compose.ui.text.buildAnnotatedString {
            if (query.isBlank()) {
                append(full)
            } else {
                var start = 0
                while (true) {
                    val idx = full.indexOf(query, start, ignoreCase = true)
                    if (idx < 0) {
                        append(full.substring(start))
                        break
                    }
                    append(full.substring(start, idx))
                    pushStyle(
                        androidx.compose.ui.text.SpanStyle(
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    )
                    append(full.substring(idx, idx + query.length))
                    pop()
                    start = idx + query.length
                }
            }
        }
    }
    Text(
        annotated,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun ChatRow(item: InboxItem, onClick: () -> Unit, query: String = "") {
    val isGroup = item.kind.equals("group", ignoreCase = true)
    ListItem(
        headlineContent = {
            HighlightedText(
                full = item.name ?: item.peer?.displayName ?: item.peer?.phone ?: "Unknown",
                query = query
            )
        },
        supportingContent = {
            Text(item.lastMessage?.preview ?: "No messages yet", maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            com.ollacore.app.ui.theme.BrandAvatar(
                name = item.name ?: item.peer?.displayName ?: item.peer?.phone ?: "?",
                size = 40.dp
            )
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
    HorizontalDivider()
}
