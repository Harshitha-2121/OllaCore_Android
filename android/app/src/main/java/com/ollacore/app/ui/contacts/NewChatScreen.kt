package com.ollacore.app.ui.contacts

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * "New chat" sheet matching the reference flow: back + title + 3-dot menu,
 * rounded "Search name, number or @username" field, New group / New contact
 * (QR affordance) / New community options, then the "Contacts on Ollacore"
 * section. Rows reuse the existing merged contacts data (inbox + saved) so
 * nothing previously stored is lost. Single-select with a confirm FAB that
 * opens the 1-to-1 chat; tapping the avatar opens contact details.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatScreen(
    state: ContactsUiState,
    viewModel: ContactsViewModel,
    onBack: () -> Unit,
    onNewGroup: () -> Unit,
    onNewContact: () -> Unit,
    onNewCommunity: () -> Unit,
    onOpenChat: (String) -> Unit,
    onOpenDetails: (ContactRow) -> Unit,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var query by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var opening by remember { mutableStateOf(false) }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    val shown = remember(state.rows, query) { ContactBook.filterRows(state.rows, query) }
    val selected = shown.find { it.key == selectedKey }

    fun openSelected() {
        val row = selected ?: return
        scope.launch {
            opening = true
            when (val result = viewModel.openChat(row)) {
                is OpenChatResult.Opened -> onOpenChat(result.roomId)
                is OpenChatResult.NotOnOllacore ->
                    snackbar.showSnackbar("${row.phone} isn't on Ollacore yet.")
                is OpenChatResult.Failed -> snackbar.showSnackbar(result.message)
            }
            opening = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New chat") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Invite a friend") },
                                onClick = {
                                    showMenu = false
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(
                                            Intent.EXTRA_TEXT,
                                            "Connect. Chat. Share. Try Ollacore with me!"
                                        )
                                    }
                                    context.startActivity(
                                        Intent.createChooser(send, "Invite a friend")
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Refresh") },
                                onClick = { showMenu = false; onRefresh() }
                            )
                            DropdownMenuItem(
                                text = { Text("Help") },
                                onClick = {
                                    showMenu = false
                                    scope.launch {
                                        snackbar.showSnackbar(
                                            "Pick a contact to start chatting, or create a group."
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (selected != null) {
                FloatingActionButton(
                    onClick = ::openSelected,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    if (opening) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Default.Check, contentDescription = "Start chat")
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search name, number or @username") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
            if (state.isLoading && state.rows.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        NewChatOption(
                            icon = Icons.Default.Group,
                            label = "New group",
                            onClick = onNewGroup
                        )
                    }
                    item {
                        NewChatOption(
                            icon = Icons.Default.PersonAdd,
                            label = "New contact",
                            trailing = {
                                IconButton(onClick = {
                                    scope.launch {
                                        snackbar.showSnackbar("QR contact sharing isn't available yet.")
                                    }
                                }) {
                                    Icon(
                                        Icons.Default.QrCode2,
                                        contentDescription = "Share QR code",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = onNewContact
                        )
                    }
                    item {
                        NewChatOption(
                            icon = Icons.Default.Groups,
                            label = "New community",
                            onClick = onNewCommunity
                        )
                    }
                    item {
                        Text(
                            "Contacts on Ollacore",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
                        )
                    }
                    if (state.rows.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    "No contacts yet",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Add a contact to start chatting",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else if (shown.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "No contacts found for \"$query\"",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    } else {
                        items(shown, key = { it.key }) { row ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        row.displayName,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        row.username?.let { "@$it • ${row.phone}" } ?: row.phone
                                    )
                                },
                                leadingContent = {
                                    Box(
                                        modifier = Modifier.clickable {
                                            onOpenDetails(row)
                                        }
                                    ) { ContactAvatar(row, 44.dp) }
                                },
                                trailingContent = {
                                    RadioButton(
                                        selected = row.key == selectedKey,
                                        onClick = {
                                            selectedKey =
                                                if (row.key == selectedKey) null else row.key
                                        }
                                    )
                                },
                                modifier = Modifier.clickable {
                                    selectedKey =
                                        if (row.key == selectedKey) null else row.key
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewChatOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
    ListItem(
        headlineContent = {
            Text(label, style = MaterialTheme.typography.titleMedium)
        },
        leadingContent = {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        },
        trailingContent = trailing,
        modifier = Modifier.clickable(onClick = onClick)
    )
}
