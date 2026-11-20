package com.ollacore.app.ui.contact

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Max list-name length (validation parity with overflow-menu lists). */
const val CHAT_LIST_NAME_MAX = 60

/**
 * Intro sheet content for the Lists feature (reference flow: illustration,
 * what lists do, privacy note, where to manage them, Continue).
 * All copy is product-neutral; colors come from the app theme.
 */
@Composable
fun ListIntroContent(onContinue: () -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.FormatListBulleted,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(44.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("Organize your chats with lists", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Group conversations into lists like Family or Work so the chats you need are always one tap away.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "Lists are private to this device. Create, rename and reorder them anytime from Settings > Chats.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
        TextButton(onClick = onDismiss) { Text("Not now") }
    }
}

/**
 * Bottom-sheet list picker: existing lists with check state + New list row.
 * Confirm applies on tap (each row toggles immediately); Done closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChooseListSheet(
    allLists: Map<String, List<String>>,
    memberOfLists: Set<String>,
    onToggleMember: (String, Boolean) -> Unit,
    onCreateNew: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState()
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp)) {
            Text(
                "Choose list",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            if (allLists.isEmpty()) {
                Text(
                    "No lists yet. Create your first one below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(allLists.keys.sorted(), key = { it }) { name ->
                        val member = name in memberOfLists
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleMember(name, !member) }
                                .padding(horizontal = 24.dp, vertical = 12.dp)
                        ) {
                            Checkbox(checked = member, onCheckedChange = { onToggleMember(name, it) })
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            if (member) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "In list",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onCreateNew)
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(12.dp))
                Text("New list", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * WhatsApp-style "New list" introduction sheet content (reference: dark
 * sheet, drag handle, X + centered title, triple-card illustration,
 * heading, three icon rows, wide green Continue).
 * Hosted in a ModalBottomSheet by the caller (handle + insets free).
 */
@Composable
fun NewListIntroContent(
    onClose: () -> Unit,
    onContinue: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp)
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                "New list",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.width(48.dp))
        }
        Spacer(modifier = Modifier.height(20.dp))
        // Triple overlapping cards: heart / briefcase / plus.
        Row(
            horizontalArrangement = Arrangement.spacedBy((-30).dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            ListIntroCard(
                icon = Icons.Default.Favorite,
                contentDesc = "Favorites illustration"
            )
            ListIntroCard(
                icon = Icons.Default.Work,
                contentDesc = "Work illustration"
            )
            ListIntroCard(
                icon = Icons.Default.Add,
                contentDesc = "Create illustration"
            )
        }
        Spacer(modifier = Modifier.height(28.dp))
        Text(
            "Organize your chats with Lists",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Spacer(modifier = Modifier.height(28.dp))
        NewListIntroRow(
            icon = Icons.AutoMirrored.Filled.FormatListBulleted,
            contentDesc = null,
            text = "Any list you create becomes a filter at the top of your Chats tab."
        )
        Spacer(modifier = Modifier.height(20.dp))
        NewListIntroRow(
            icon = Icons.Default.Lock,
            contentDesc = null,
            text = "Only you can see your lists."
        )
        Spacer(modifier = Modifier.height(20.dp))
        NewListIntroRow(
            icon = Icons.Default.Settings,
            contentDesc = null,
            text = "You can change or edit them in Settings."
        )
        Spacer(modifier = Modifier.height(28.dp))
        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .height(52.dp),
            shape = RoundedCornerShape(28.dp)
        ) {
            Text("Continue", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun ListIntroCard(
    icon: ImageVector,
    contentDesc: String?
) {
    // Stacked-paper look: two offset back layers + light face card.
    Box(modifier = Modifier.size(width = 116.dp, height = 88.dp)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(x = (-10).dp, y = 6.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(x = (-5).dp, y = 3.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
        )
        Surface(
            color = Color(0xFFE4F6E2),
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier.matchParentSize()
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    icon,
                    contentDescription = contentDesc,
                    tint = Color(0xFF0B8A4B),
                    modifier = Modifier.size(44.dp)
                )
            }
        }
    }
}

@Composable
private fun NewListIntroRow(
    icon: ImageVector,
    contentDesc: String?,
    text: String
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp)
    ) {
        Icon(
            icon,
            contentDescription = contentDesc,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(24.dp)
        )
        Spacer(modifier = Modifier.width(20.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * List builder sheet: name field with [validateListName] plus a member
 * checklist of existing chats. Save disabled until the name is valid;
 * Cancel discards without touching existing data.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListBuilderSheet(
    chats: List<Pair<String, String>>,
    existingNames: Set<String>,
    saving: Boolean,
    saveError: String?,
    onSave: (name: String, memberIds: List<String>) -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
) {
    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val error = validateListName(name, existingNames)
    val canSave = !saving && error == null && name.isNotBlank()
    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() }, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
        ) {
            Text(
                "New list",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("List name") },
                singleLine = true,
                enabled = !saving,
                isError = name.isNotBlank() && error != null,
                supportingText = {
                    when {
                        saveError != null -> Text(saveError, color = MaterialTheme.colorScheme.error)
                        name.isNotBlank() && error != null -> Text(error)
                        else -> Text("${selected.size} chat(s) selected")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (chats.isEmpty()) {
                Text(
                    "No chats yet. You can create the list now and add chats later.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(chats, key = { it.first }) { (roomId, title) ->
                        val member = roomId in selected
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !saving) {
                                    selected = if (member) selected - roomId else selected + roomId
                                }
                                .padding(horizontal = 24.dp, vertical = 10.dp)
                        ) {
                            Checkbox(
                                checked = member,
                                enabled = !saving,
                                onCheckedChange = {
                                    selected = if (it) selected + roomId else selected - roomId
                                }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                title.ifBlank { roomId },
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                OutlinedButton(
                    enabled = !saving,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) { Text("Cancel") }
                Button(
                    enabled = canSave,
                    onClick = { onSave(name.trim(), selected.toList()) },
                    modifier = Modifier.weight(1f)
                ) {
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Create")
                    }
                }
            }
        }
    }
}

/** Validation result for a candidate list name. Null = valid. */
fun validateListName(raw: String, existing: Set<String>): String? {
    val name = raw.trim()
    if (name.isEmpty()) return "Enter a name for this list."
    if (name.length > CHAT_LIST_NAME_MAX) return "Keep it under $CHAT_LIST_NAME_MAX characters."
    if (existing.any { it.equals(name, ignoreCase = true) }) return "A list with this name already exists."
    return null
}

/** Creation dialog with inline validation; Save stays disabled until valid. */
@Composable
fun CreateListDialog(
    existingNames: Set<String>,
    creating: Boolean,
    createError: String?,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    val error = validateListName(name, existingNames)
    AlertDialog(
        onDismissRequest = { if (!creating) onDismiss() },
        title = { Text("New list") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("List name") },
                    singleLine = true,
                    enabled = !creating,
                    isError = name.isNotBlank() && error != null,
                    supportingText = {
                        when {
                            createError != null -> Text(createError, color = MaterialTheme.colorScheme.error)
                            name.isNotBlank() && error != null -> Text(error)
                            else -> Text("This chat will be added to the new list.")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (creating) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Creating…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !creating && error == null && name.isNotBlank(),
                onClick = { onCreate(name.trim()) }
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(enabled = !creating, onClick = onDismiss) { Text("Cancel") }
        }
    )
}
