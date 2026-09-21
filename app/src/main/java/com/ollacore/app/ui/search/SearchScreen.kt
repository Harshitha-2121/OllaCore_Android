package com.ollacore.app.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.ollacore.app.data.model.MessageResponse
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    uiState: SearchUiState,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onResultClick: (MessageResponse) -> Unit,
    onBack: () -> Unit,
    onClear: () -> Unit
) {
    var searchText by remember { mutableStateOf(uiState.query) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = searchText,
                        onValueChange = {
                            searchText = it
                            onQueryChange(it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search messages…") },
                        singleLine = true,
                        trailingIcon = {
                            if (searchText.isNotEmpty()) {
                                IconButton(onClick = {
                                    searchText = ""
                                    onClear()
                                }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                                }
                            }
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { onSearch(searchText) }) {
                        Text("Search")
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
            // Loading indicator
            if (uiState.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // Error message
            uiState.error?.let { error ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = error,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Results
            when {
                uiState.isLoading && uiState.results.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                uiState.hasSearched && uiState.results.isEmpty() && uiState.error == null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "No messages found",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "Try a different search term",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                uiState.results.isNotEmpty() -> {
                    Text(
                        text = "${uiState.results.size} result${if (uiState.results.size != 1) "s" else ""}",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    LazyColumn(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(uiState.results) { index, message ->
                            SearchResultItem(
                                message = message,
                                query = uiState.query,
                                onClick = {
                                    onResultClick(message)
                                }
                            )
                        }
                    }
                }
                !uiState.hasSearched -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Type to search messages",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SearchResultItem(
    message: MessageResponse,
    query: String,
    onClick: () -> Unit
) {
    val text = try {
        message.body["text"]?.jsonPrimitive?.content ?: ""
    } catch (_: Exception) {
        ""
    }
    val sender = message.senderId
    val timestamp = message.createdAt

    ListItem(
        headlineContent = {
            HighlightedText(
                text = text,
                query = query,
                maxLines = 2
            )
        },
        supportingContent = {
            Text(
                text = "$sender • $timestamp",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
    HorizontalDivider()
}

@Composable
fun HighlightedText(
    text: String,
    query: String,
    maxLines: Int = 2
) {
    if (query.isBlank()) {
        Text(text = text, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        return
    }

    val annotatedString = buildAnnotatedString {
        val lowerText = text.lowercase()
        val lowerQuery = query.lowercase()
        var startIndex = 0

        while (startIndex < text.length) {
            val matchIndex = lowerText.indexOf(lowerQuery, startIndex)
            if (matchIndex == -1) {
                append(text.substring(startIndex))
                break
            }
            if (matchIndex > startIndex) {
                append(text.substring(startIndex, matchIndex))
            }
            withStyle(
                SpanStyle(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            ) {
                append(text.substring(matchIndex, matchIndex + query.length))
            }
            startIndex = matchIndex + query.length
        }
    }

    Text(
        text = annotatedString,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}
