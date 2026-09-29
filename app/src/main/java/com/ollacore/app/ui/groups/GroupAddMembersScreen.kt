package com.ollacore.app.ui.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ollacore.app.data.model.ContactUser
import com.ollacore.app.ui.theme.BrandAvatar

/**
 * Add-member workflow (reference layout): back + title, live search field,
 * checkbox contact rows with already-added state, confirm FAB with count.
 * Operates on real inbox contacts; membership validated against the live
 * roster; failures keep selection for retry (ViewModel).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupAddMembersScreen(
    groupName: String,
    candidates: List<ContactUser>,
    memberIds: Set<String>,
    query: String,
    selected: Set<String>,
    errors: Map<String, String>,
    busy: Boolean,
    canAdd: Boolean,
    onBack: () -> Unit,
    onQuery: (String) -> Unit,
    onToggle: (String) -> Unit,
    onConfirm: () -> Unit
) {
    val shown = remember(candidates, query) {
        val q = query.trim()
        if (q.isEmpty()) candidates
        else candidates.filter {
            (it.displayName ?: "").contains(q, ignoreCase = true) ||
                it.phone.contains(q) ||
                it.userId.contains(q, ignoreCase = true)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Add member")
                        Text(
                            groupName,
                            maxLines = 1,
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
        },
        floatingActionButton = {
            if (selected.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onConfirm,
                    icon = {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Default.Check, contentDescription = null)
                        }
                    },
                    text = { Text("Add (${selected.size})") }
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("Search name, number or @username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            )
            if (!canAdd) {
                Text(
                    "Only group admins can add members.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            if (candidates.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Text("No contacts yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Start a chat first - its members become addable contacts.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (shown.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No contacts found for \"$query\"",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(shown, key = { it.userId }) { contact ->
                        val alreadyMember = contact.userId in memberIds
                        val checked = contact.userId in selected
                        val error = errors[contact.userId]
                        ListItem(
                            headlineContent = {
                                Text(
                                    contact.displayName?.ifBlank { null } ?: contact.phone,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1
                                )
                            },
                            supportingContent = {
                                Column {
                                    Text(contact.phone)
                                    when {
                                        alreadyMember -> Text(
                                            "Already added to group",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        error != null -> Text(
                                            error,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            },
                            leadingContent = {
                                BrandAvatar(
                                    name = contact.displayName?.ifBlank { null } ?: contact.phone,
                                    size = 44.dp
                                )
                            },
                            trailingContent = {
                                if (!alreadyMember) {
                                    Checkbox(
                                        checked = checked,
                                        enabled = canAdd && !busy,
                                        onCheckedChange = { onToggle(contact.userId) }
                                    )
                                }
                            },
                            modifier = Modifier.clickable(
                                enabled = canAdd && !alreadyMember && !busy
                            ) { onToggle(contact.userId) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
