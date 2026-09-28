package com.ollacore.app.ui.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ollacore.app.ui.theme.BrandAvatar
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailsScreen(
    row: ContactRow,
    viewModel: ContactsViewModel,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onVoiceCall: (roomId: String, peerName: String) -> Unit,
    onVideoCall: (roomId: String, peerName: String) -> Unit,
    onDeleted: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    fun resolveThen(action: (roomId: String) -> Unit, actionName: String) {
        scope.launch {
            busy = true
            when (val result = viewModel.openChat(row)) {
                is OpenChatResult.Opened -> action(result.roomId)
                is OpenChatResult.NotOnOllacore ->
                    snackbar.showSnackbar("Can't $actionName: ${row.phone} isn't on Ollacore yet.")
                is OpenChatResult.Failed -> snackbar.showSnackbar(result.message)
            }
            busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contact details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val file = row.photoUri?.let { File(it) }?.takeIf { it.exists() }
            if (file != null) {
                AsyncImage(
                    model = file,
                    contentDescription = "Photo of ${row.displayName}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(96.dp).clip(CircleShape)
                )
            } else {
                BrandAvatar(name = row.displayName.ifBlank { row.phone }, size = 96.dp)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(row.displayName, style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                row.phone,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            row.username?.let {
                Text(
                    "@$it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!row.onOllacore) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Not on Ollacore yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(
                    onClick = { resolveThen(onOpenChat, "start chat") },
                    enabled = !busy
                ) {
                    Icon(Icons.Default.ChatBubble, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Chat")
                }
                FilledTonalButton(
                    onClick = { resolveThen({ roomId -> onVoiceCall(roomId, row.displayName) }, "call") },
                    enabled = !busy
                ) {
                    Icon(Icons.Default.Call, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Voice")
                }
                FilledTonalButton(
                    onClick = { resolveThen({ roomId -> onVideoCall(roomId, row.displayName) }, "call") },
                    enabled = !busy
                ) {
                    Icon(Icons.Default.Videocam, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Video")
                }
            }
            if (busy) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            if (row.localId != null) {
                ListItem(
                    headlineContent = { Text("Edit contact") },
                    leadingContent = {
                        Icon(Icons.Default.Edit, contentDescription = null)
                    },
                    modifier = Modifier.clickable { showEdit = true }
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = {
                        Text("Delete contact", color = MaterialTheme.colorScheme.error)
                    },
                    leadingContent = {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    modifier = Modifier.clickable { showDelete = true }
                )
                HorizontalDivider()
            } else {
                Text(
                    "This contact comes from your chats. Save it to edit details or delete it from your list.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                OutlinedButton(onClick = { showSave = true }) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save to contacts")
                }
            }
        }
    }

    if (showEdit && row.localId != null) {
        val localId = row.localId
        ContactFormDialog(
            title = "Edit contact",
            initialFirst = row.firstName,
            initialLast = row.lastName,
            initialPhone = row.phone,
            initialPhoto = row.photoUri,
            onDismiss = { showEdit = false },
            onSave = { first, last, phone, photo ->
                val err = viewModel.updateContact(localId, first, last, phone, photo)
                if (err == null) showEdit = false
                err
            }
        )
    }

    if (showSave) {
        ContactFormDialog(
            title = "Save to contacts",
            initialFirst = row.firstName,
            initialLast = row.lastName,
            initialPhone = row.phone,
            initialPhoto = null,
            onDismiss = { showSave = false },
            onSave = { first, last, phone, photo ->
                val err = viewModel.addContact(first, last, phone, photo)
                if (err == null) showSave = false
                err
            }
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete contact?") },
            text = {
                Text(
                    "This removes ${row.displayName} from your contacts. " +
                        "Your chats with them are kept."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            if (viewModel.deleteRow(row)) onDeleted()
                            else {
                                showDelete = false
                                snackbar.showSnackbar("Nothing saved to delete.")
                            }
                        }
                    }
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("Cancel") }
            }
        )
    }
}
