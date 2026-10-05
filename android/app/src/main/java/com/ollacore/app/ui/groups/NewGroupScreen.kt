package com.ollacore.app.ui.groups

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File
import java.io.FileOutputStream

/**
 * New Group: Select contacts -> Group name/photo -> Create.
 * Create = POST /v1/directory/conversations/group (Category 1 YES).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewGroupScreen(
    uiState: NewGroupUiState,
    onToggleSelect: (String) -> Unit,
    onNext: () -> Unit,
    onBackStep: () -> Unit,
    onBack: () -> Unit,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit = {},
    onReview: () -> Unit = {},
    onBackToDetails: () -> Unit = {},
    onCreate: (File?, String?) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var iconFile by remember { mutableStateOf<File?>(null) }
    var iconPreview by remember { mutableStateOf<Uri?>(null) }
    val context = LocalContext.current

    val iconPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            iconPreview = uri
            iconFile = copyUriToCacheFile(context, uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (uiState.step) {
                            NewGroupStep.SELECT_MEMBERS -> "New group"
                            NewGroupStep.DETAILS -> "Group details"
                            NewGroupStep.REVIEW -> "Review & create"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when (uiState.step) {
                            NewGroupStep.SELECT_MEMBERS -> onBack()
                            NewGroupStep.DETAILS -> onBackStep()
                            NewGroupStep.REVIEW -> onBackToDetails()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            if (uiState.step == NewGroupStep.SELECT_MEMBERS && uiState.selectedIds.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onNext,
                    icon = { Icon(Icons.Default.Check, contentDescription = null) },
                    text = { Text("${uiState.selectedIds.size} selected") }
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (uiState.error != null) {
                Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer) {
                    Text(uiState.error, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }

            if (uiState.step == NewGroupStep.SELECT_MEMBERS) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search contacts…") },
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    singleLine = true
                )
                // Selected participant chips (spec 16).
                val selected = uiState.contacts.filter { it.userId in uiState.selectedIds }
                if (selected.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(selected, key = { it.userId }) { contact ->
                            AssistChip(
                                onClick = { onToggleSelect(contact.userId) },
                                label = { Text(contact.displayName ?: contact.phone) },
                                trailingIcon = {
                                    Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp))
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
                if (uiState.isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    val shown = uiState.contacts.filter {
                        query.isBlank() ||
                            (it.displayName ?: "").contains(query, ignoreCase = true) ||
                            it.phone.contains(query)
                    }
                    if (shown.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No contacts - start a chat first, or add by phone in the next step")
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(shown, key = { it.userId }) { contact ->
                                val selected = contact.userId in uiState.selectedIds
                                ListItem(
                                    headlineContent = {
                                        Text(
                                            contact.displayName ?: contact.phone,
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                    },
                                    supportingContent = { Text(contact.phone) },
                                    leadingContent = {
                                        com.ollacore.app.ui.theme.BrandAvatar(
                                            name = contact.displayName ?: contact.phone,
                                            size = 40.dp
                                        )
                                    },
                                    trailingContent = { Checkbox(checked = selected, onCheckedChange = { onToggleSelect(contact.userId) }) },
                                    modifier = Modifier.clickable { onToggleSelect(contact.userId) }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            } else if (uiState.step == NewGroupStep.DETAILS) {
                // DETAILS: photo + name + description -> Review
                Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(96.dp).clickable {
                            try {
                                iconPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            } catch (_: Exception) {
                            }
                        }
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            if (iconPreview != null) {
                                AsyncImage(
                                    model = iconPreview,
                                    contentDescription = "Group photo",
                                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(Icons.Default.PhotoCamera, contentDescription = "Group photo", modifier = Modifier.size(36.dp))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Tap to add group photo (best-effort - needs backend icon support)", style = MaterialTheme.typography.labelSmall)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = uiState.groupName,
                        onValueChange = onNameChange,
                        label = { Text("Group name") },
                        placeholder = { Text("e.g. Family") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = uiState.groupDescription,
                        onValueChange = onDescriptionChange,
                        label = { Text("Group description (optional)") },
                        placeholder = { Text("What is this group about?") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Group, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("${uiState.selectedIds.size} members", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    com.ollacore.app.ui.theme.GradientButton(
                        text = "Continue",
                        onClick = onReview,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                // REVIEW: summary + large primary Create (spec 16 step 3).
                val selected = uiState.contacts.filter { it.userId in uiState.selectedIds }
                Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (iconPreview != null) {
                        AsyncImage(
                            model = iconPreview,
                            contentDescription = "Group photo",
                            modifier = Modifier.size(96.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(96.dp)) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Icon(Icons.Default.Group, contentDescription = null, modifier = Modifier.size(44.dp))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        uiState.groupName.ifBlank { "Untitled group" },
                        style = MaterialTheme.typography.headlineSmall
                    )
                    if (uiState.groupDescription.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            uiState.groupDescription,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "${selected.size} members",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(selected, key = { it.userId }) { contact ->
                            AssistChip(
                                onClick = {},
                                label = { Text(contact.displayName ?: contact.phone) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    if (uiState.iconNote != null) {
                        Text(uiState.iconNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Button(
                        onClick = { onCreate(iconFile, iconFile?.let { "image/jpeg" }) },
                        enabled = !uiState.isCreating,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        if (uiState.isCreating) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text("Create group", style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
    }
}

private fun copyUriToCacheFile(context: Context, uri: Uri): File? {
    return try {
        var displayName = "group_icon_${System.currentTimeMillis()}.jpg"
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) displayName = c.getString(idx) ?: displayName
            }
        } catch (_: Exception) {
        }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)
        val out = File(context.cacheDir, "${System.currentTimeMillis()}_$safeName")
        context.contentResolver.openInputStream(uri)?.use { ins ->
            FileOutputStream(out).use { outs -> ins.copyTo(outs) }
        }
        out.takeIf { it.exists() && it.length() > 0 }
    } catch (_: Exception) {
        null
    }
}
