package com.ollacore.app.ui.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.PersonAdd
import com.ollacore.app.ui.theme.BrandAvatar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ollacore.app.data.model.ContactUser

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    contacts: List<ContactUser>,
    isLoading: Boolean,
    onBack: () -> Unit,
    onContactClick: (String) -> Unit,
    onRefresh: () -> Unit,
    onNewGroup: () -> Unit = {},
    onAddByPhone: (String) -> Unit = {}
) {
    var showAddPhone by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contacts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (contacts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No contacts found", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onRefresh) {
                        Text("Refresh")
                    }
                    FilledTonalButton(onClick = onNewGroup) {
                        Text("New group")
                    }
                }
            }
        } else {
            // New Chat -> Search contact -> Contact found -> Start conversation -> Chat screen
            var query by remember { mutableStateOf("") }
            val shown = remember(contacts, query) {
                if (query.isBlank()) contacts
                else contacts.filter {
                    (it.displayName ?: "").contains(query, ignoreCase = true) || it.phone.contains(query)
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search name or number…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(12.dp)
                    )
                }
                // New Group entry (Category 1 - create group API YES)
                item {
                    ListItem(
                        headlineContent = { Text("New group") },
                        leadingContent = {
                            Surface(
                                modifier = Modifier.size(40.dp),
                                shape = MaterialTheme.shapes.extraLarge,
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Group,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        },
                        modifier = Modifier.clickable { onNewGroup() }
                    )
                    HorizontalDivider()
                }
                // Unknown number -> directory lookup -> start conversation (Category 1 YES)
                item {
                    ListItem(
                        headlineContent = { Text("Add by phone number") },
                        leadingContent = {
                            Surface(
                                modifier = Modifier.size(40.dp),
                                shape = MaterialTheme.shapes.extraLarge,
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.PersonAdd,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        },
                        modifier = Modifier.clickable { showAddPhone = true }
                    )
                    HorizontalDivider()
                }
                if (shown.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text("No contact matches \"$query\"", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                items(shown) { contact ->
                    ListItem(
                        headlineContent = {
                            Text(
                                contact.displayName ?: "Unknown",
                                style = MaterialTheme.typography.titleMedium
                            )
                        },
                        supportingContent = {
                            Text(contact.phone)
                        },
                        leadingContent = {
                            BrandAvatar(name = contact.displayName ?: contact.phone, size = 40.dp)
                        },
                        modifier = Modifier.clickable { onContactClick(contact.userId) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showAddPhone) {
        var phone by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddPhone = false },
            title = { Text("Start chat by number") },
            text = {
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone number") },
                    placeholder = { Text("+15550001111") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (phone.isNotBlank()) {
                        onAddByPhone(phone.trim())
                        showAddPhone = false
                    }
                }) { Text("Chat") }
            },
            dismissButton = {
                TextButton(onClick = { showAddPhone = false }) { Text("Cancel") }
            }
        )
    }
}
