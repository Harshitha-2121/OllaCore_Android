package com.ollacore.app.ui.broadcast

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ollacore.app.OllacoreApp
import com.ollacore.app.data.local.BroadcastList
import com.ollacore.app.data.model.ContactUser
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class BroadcastsUiState(
    val lists: List<BroadcastList> = emptyList(),
    val candidates: List<ContactUser> = emptyList(),
    val isLoading: Boolean = false,
    val showCreate: Boolean = false,
    val sendNote: String? = null
)

/**
 * Broadcast lists (reference overflow "Broadcast lists"): lists + member
 * selection persist locally and are fully manageable. SENDING needs the
 * backend broadcast API, so Send opens an honest note - never faked.
 */
class BroadcastsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as OllacoreApp).container
    private val directoryRepo = container.directoryRepository
    private val sessionStore = container.sessionStore

    private val _uiState = MutableStateFlow(BroadcastsUiState())
    val uiState: StateFlow<BroadcastsUiState> = _uiState.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val lists = runCatching { container.broadcastStore.lists.first() }.getOrElse { emptyList() }
            val token = sessionStore.sessionToken.first()
            val candidates = if (token == null) emptyList() else runCatching {
                val seen = LinkedHashMap<String, ContactUser>()
                directoryRepo.getInbox(token).getOrThrow().conversations.forEach { item ->
                    val peer = item.peer
                    if (peer != null && !seen.containsKey(peer.userId)) {
                        seen[peer.userId] = ContactUser(peer.userId, peer.phone, peer.displayName ?: item.name)
                    }
                }
                seen.values.toList()
            }.getOrElse { emptyList() }
            _uiState.update { it.copy(isLoading = false, lists = lists, candidates = candidates) }
        }
    }

    fun setShowCreate(show: Boolean) {
        _uiState.update { it.copy(showCreate = show) }
    }

    fun createList(name: String, memberIds: List<String>) {
        viewModelScope.launch {
            val entry = runCatching { container.broadcastStore.createList(name, memberIds) }.getOrNull()
            if (entry != null) {
                val lists = runCatching { container.broadcastStore.lists.first() }
                    .getOrElse { _uiState.value.lists }
                _uiState.update { it.copy(lists = lists, showCreate = false) }
            }
        }
    }

    fun deleteList(id: String) {
        viewModelScope.launch {
            runCatching { container.broadcastStore.deleteList(id) }
            val lists = runCatching { container.broadcastStore.lists.first() }
                .getOrElse { _uiState.value.lists }
            _uiState.update { it.copy(lists = lists) }
        }
    }

    fun showSendNote(name: String) {
        _uiState.update {
            it.copy(
                sendNote = "Sending to \"$name\" needs the backend broadcast " +
                    "API (one-to-many fan-out). Lists and members are saved " +
                    "on this device and will light up once it lands."
            )
        }
    }

    fun consumeSendNote() {
        _uiState.update { it.copy(sendNote = null) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BroadcastsScreen(
    uiState: BroadcastsUiState,
    onBack: () -> Unit,
    onShowCreate: (Boolean) -> Unit,
    onCreate: (String, List<String>) -> Unit,
    onDelete: (String) -> Unit,
    onSend: (String) -> Unit,
    onConsumeSendNote: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Broadcast lists") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onShowCreate(true) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New list") }
            )
        },
        modifier = modifier
    ) { padding ->
        when {
            uiState.isLoading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            uiState.lists.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Campaign,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("No broadcast lists", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Create a list of contacts to message at once. Lists live " +
                            "on this device; sending activates with the backend.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(uiState.lists, key = { it.id }) { list ->
                    ListItem(
                        headlineContent = { Text(list.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("${list.memberIds.size} recipient(s)") },
                        leadingContent = {
                            Icon(Icons.Default.Campaign, contentDescription = null)
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { onSend(list.name) }) {
                                    Icon(Icons.Default.Send, contentDescription = "Send broadcast")
                                }
                                IconButton(onClick = { onDelete(list.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete list")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    HorizontalDivider()
                }
            }
        }
    }
    if (uiState.showCreate) {
        CreateBroadcastSheet(
            candidates = uiState.candidates,
            onDismiss = { onShowCreate(false) },
            onCreate = onCreate
        )
    }
    uiState.sendNote?.let { note ->
        AlertDialog(
            onDismissRequest = onConsumeSendNote,
            title = { Text("Broadcast") },
            text = { Text(note) },
            confirmButton = { TextButton(onClick = onConsumeSendNote) { Text("Got it") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateBroadcastSheet(
    candidates: List<ContactUser>,
    onDismiss: () -> Unit,
    onCreate: (String, List<String>) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val shown = remember(candidates, query) {
        val q = query.trim()
        if (q.isEmpty()) candidates
        else candidates.filter {
            (it.displayName ?: "").contains(q, ignoreCase = true) || it.phone.contains(q)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        // Header carries the Create action: sheet bottoms sit behind the
        // system nav bar on edge-to-edge devices (window insets read zero
        // inside bottom sheets here), so no primary action lives at the
        // sheet bottom. Contact list scrolls in the bounded middle.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .navigationBarsPadding()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "New broadcast list",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { onCreate(name, selected.toList()) },
                    enabled = name.isNotBlank() && selected.isNotEmpty()
                ) {
                    Text("Create (${selected.size})")
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("List name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search contacts") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(shown, key = { it.userId }) { contact ->
                    val checked = contact.userId in selected
                    ListItem(
                        headlineContent = {
                            Text(contact.displayName?.ifBlank { null } ?: contact.phone)
                        },
                        supportingContent = { Text(contact.phone) },
                        trailingContent = {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = {
                                    selected = if (checked) selected - contact.userId
                                    else selected + contact.userId
                                }
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            // Bottom clearance is structural now (fixed action above nav);
            // keep a small visual gap only.
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
