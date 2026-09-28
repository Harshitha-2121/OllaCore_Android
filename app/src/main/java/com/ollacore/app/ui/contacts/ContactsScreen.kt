package com.ollacore.app.ui.contacts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ollacore.app.ui.theme.BrandAvatar
import kotlinx.coroutines.launch
import java.io.File

/** Add-form state: hidden, fresh add (optionally prefilled), or editing a row. */
private sealed interface FormState {
    data object Hidden : FormState
    data class Add(val phone: String = "", val name: String = "") : FormState
    data class Edit(val row: ContactRow) : FormState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    state: ContactsUiState,
    viewModel: ContactsViewModel,
    onBack: () -> Unit,
    onOpenDetails: (ContactRow) -> Unit,
    onRefresh: () -> Unit,
    onNewGroup: () -> Unit,
    onOpenChat: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var query by remember { mutableStateOf("") }
    var form by remember { mutableStateOf<FormState>(FormState.Hidden) }
    var showAddPhone by remember { mutableStateOf(false) }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    val shown = remember(state.rows, query) { ContactBook.filterRows(state.rows, query) }
    val sections = remember(shown) {
        val groups = LinkedHashMap<String, MutableList<ContactRow>>()
        shown.forEach { row ->
            groups.getOrPut(ContactBook.sectionKey(row.displayName)) { mutableListOf() }.add(row)
        }
        groups
    }

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
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search name or number…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            )
            if (state.isLoading && state.rows.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        ListItem(
                            headlineContent = { Text("New contact") },
                            leadingContent = {
                                ActionIcon(Icons.Default.PersonAdd, "Add contact") {
                                    form = FormState.Add()
                                }
                            },
                            modifier = Modifier.clickable { form = FormState.Add() }
                        )
                        HorizontalDivider()
                    }
                    // New Group entry (Category 1 - create group API YES, unchanged)
                    item {
                        ListItem(
                            headlineContent = { Text("New group") },
                            leadingContent = {
                                ActionIcon(Icons.Default.Group, "New group") { onNewGroup() }
                            },
                            modifier = Modifier.clickable { onNewGroup() }
                        )
                        HorizontalDivider()
                    }
                    // Directory lookup for unknown numbers (Category 1 YES, unchanged purpose)
                    item {
                        ListItem(
                            headlineContent = { Text("Add by phone number") },
                            supportingContent = { Text("Find someone on Ollacore by their number") },
                            leadingContent = {
                                ActionIcon(Icons.Default.Dialpad, "Add by phone number") {
                                    showAddPhone = true
                                }
                            },
                            modifier = Modifier.clickable { showAddPhone = true }
                        )
                        HorizontalDivider()
                    }
                    if (state.rows.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("No contacts yet", style = MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Add a contact to get started",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                FilledTonalButton(onClick = { form = FormState.Add() }) {
                                    Text("Add contact")
                                }
                                TextButton(onClick = onRefresh) { Text("Refresh") }
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
                        sections.forEach { (letter, rows) ->
                            item(key = "header-$letter") {
                                Text(
                                    letter,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                                )
                            }
                            items(rows, key = { it.key }) { row ->
                                ListItem(
                                    headlineContent = {
                                        Text(row.displayName, style = MaterialTheme.typography.titleMedium)
                                    },
                                    supportingContent = {
                                        Column {
                                            Text(row.phone)
                                            if (!row.onOllacore) {
                                                Text(
                                                    "Not on Ollacore yet",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    },
                                    leadingContent = { ContactAvatar(row, 40.dp) },
                                    modifier = Modifier.clickable { onOpenDetails(row) }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    when (val current = form) {
        is FormState.Hidden -> Unit
        is FormState.Add -> ContactFormDialog(
            title = "New contact",
            initialFirst = current.name,
            initialLast = "",
            initialPhone = current.phone,
            initialPhoto = null,
            onDismiss = { form = FormState.Hidden },
            onSave = { first, last, phone, photo ->
                val err = viewModel.addContact(first, last, phone, photo)
                if (err == null) form = FormState.Hidden
                err
            }
        )
        is FormState.Edit -> Unit // Edit lives on the details screen.
    }

    if (showAddPhone) {
        AddByPhoneDialog(
            viewModel = viewModel,
            onDismiss = { showAddPhone = false },
            onOpenChat = { roomId ->
                showAddPhone = false
                onOpenChat(roomId)
            },
            onSaveContact = { phone, name ->
                showAddPhone = false
                form = FormState.Add(phone = phone, name = name)
            },
            onError = { message ->
                scope.launch { snackbar.showSnackbar(message) }
            }
        )
    }
}

@Composable
private fun ActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    container: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primaryContainer,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.size(40.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = container
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.clickable(onClick = onClick)) {
            Icon(
                icon,
                contentDescription = description,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
fun ContactAvatar(row: ContactRow, size: androidx.compose.ui.unit.Dp) {
    val file = row.photoUri?.let { File(it) }?.takeIf { it.exists() }
    if (file != null) {
        AsyncImage(
            model = file,
            contentDescription = "Photo of ${row.displayName}",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape)
        )
    } else {
        BrandAvatar(name = row.displayName.ifBlank { row.phone }, size = size)
    }
}

/**
 * Shared add/edit contact form. [onSave] is suspend; the returned
 * [ContactFormError] (null = saved) drives per-field error text.
 */
@Composable
fun ContactFormDialog(
    title: String,
    initialFirst: String,
    initialLast: String,
    initialPhone: String,
    initialPhoto: String?,
    onDismiss: () -> Unit,
    onSave: suspend (first: String, last: String, phone: String, photo: String?) -> ContactFormError?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var first by remember(initialPhone, initialFirst) { mutableStateOf(initialFirst) }
    var last by remember { mutableStateOf(initialLast) }
    var phone by remember { mutableStateOf(initialPhone) }
    var photo by remember { mutableStateOf(initialPhoto) }
    var attempted by remember { mutableStateOf<ContactFormError?>(null) }
    var saving by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            copyContactPhoto(context, uri)?.let { photo = it }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val file = photo?.let { File(it) }?.takeIf { it.exists() }
                    if (file != null) {
                        AsyncImage(
                            model = file,
                            contentDescription = "Contact photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(56.dp).clip(CircleShape)
                        )
                    } else {
                        BrandAvatar(name = first.ifBlank { phone.ifBlank { "?" } }, size = 56.dp)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    TextButton(onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text(if (photo == null) "Add photo" else "Change") }
                    if (photo != null) {
                        TextButton(onClick = { photo = null }) { Text("Remove") }
                    }
                }
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = it; attempted = null },
                    label = { Text("First name *") },
                    singleLine = true,
                    isError = attempted == ContactFormError.FIRST_REQUIRED,
                    supportingText = {
                        if (attempted == ContactFormError.FIRST_REQUIRED) {
                            Text(ContactFormError.FIRST_REQUIRED.message())
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = last,
                    onValueChange = { last = it },
                    label = { Text("Last name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it; attempted = null },
                    label = { Text("Phone number *") },
                    placeholder = { Text("+15550001111") },
                    singleLine = true,
                    isError = attempted == ContactFormError.PHONE_REQUIRED ||
                        attempted == ContactFormError.PHONE_INVALID ||
                        attempted == ContactFormError.DUPLICATE,
                    supportingText = {
                        val err = attempted
                        if (err == ContactFormError.PHONE_REQUIRED ||
                            err == ContactFormError.PHONE_INVALID ||
                            err == ContactFormError.DUPLICATE
                        ) {
                            Text(err.message())
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        saving = true
                        attempted = onSave(first.trim(), last.trim(), phone.trim(), photo)
                        saving = false
                    }
                },
                enabled = !saving
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * "Add by phone number": directory lookup with explicit outcomes. Kept
 * distinct from "New contact": this finds/starts chats, it does not save.
 */
@Composable
private fun AddByPhoneDialog(
    viewModel: ContactsViewModel,
    onDismiss: () -> Unit,
    onOpenChat: (String) -> Unit,
    onSaveContact: (phone: String, name: String) -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<NumberLookupResult?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start chat by number") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it; result = null },
                    label = { Text("Phone number") },
                    placeholder = { Text("+15550001111") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                when (val r = result) {
                    is NumberLookupResult.OnOllacore -> {
                        Text(
                            "${r.displayName?.ifBlank { null } ?: r.phone} (${r.phone}) is on Ollacore.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        val row = ContactRow(
                                            key = "uid:${r.userId}",
                                            firstName = r.displayName ?: r.phone,
                                            lastName = "",
                                            displayName = r.displayName?.ifBlank { null } ?: r.phone,
                                            phone = r.phone,
                                            normalizedPhone = ContactBook.normalizePhone(r.phone),
                                            userId = r.userId,
                                            onOllacore = true
                                        )
                                        when (val opened = viewModel.openChat(row)) {
                                            is OpenChatResult.Opened -> onOpenChat(opened.roomId)
                                            is OpenChatResult.NotOnOllacore ->
                                                onError("That number is no longer on Ollacore.")
                                            is OpenChatResult.Failed -> onError(opened.message)
                                        }
                                        busy = false
                                    }
                                },
                                enabled = !busy
                            ) { Text("Chat") }
                            if (!r.alreadySaved) {
                                OutlinedButton(onClick = {
                                    onSaveContact(r.phone, r.displayName ?: "")
                                }) { Text("Save contact") }
                            }
                        }
                    }
                    is NumberLookupResult.NotOnOllacore -> {
                        Text(
                            "${r.phone} isn't on Ollacore yet. You can still save them as a contact.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedButton(onClick = { onSaveContact(r.phone, "") }) {
                            Text("Save anyway")
                        }
                    }
                    is NumberLookupResult.Invalid ->
                        Text(r.message, color = MaterialTheme.colorScheme.error)
                    is NumberLookupResult.Failed ->
                        Text(r.message, color = MaterialTheme.colorScheme.error)
                    null -> Unit
                }
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        busy = true
                        result = viewModel.lookupNumber(phone)
                        busy = false
                    }
                },
                enabled = !busy && phone.isNotBlank()
            ) { Text("Find") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
