package com.ollacore.app.ui.contact

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
