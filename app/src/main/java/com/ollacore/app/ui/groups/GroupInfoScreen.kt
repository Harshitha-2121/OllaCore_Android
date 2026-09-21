package com.ollacore.app.ui.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ollacore.app.data.model.Participant

/**
 * Group Info: photo/name/description + Members (add/remove/admin) + Invite/Rename/Icon/Leave.
 * Participants = confirmed Ollacore API; mutations surface backend rejections
 * as "backend check required" (Category 2) instead of failing silently.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(
    uiState: GroupInfoUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRename: (String) -> Unit,
    onDescription: (String) -> Unit,
    onIcon: (String) -> Unit,
    onAddMember: (String) -> Unit,
    onRemoveMember: (String) -> Unit,
    onSetAdmin: (String, Boolean) -> Unit,
    onInvite: () -> Unit,
    onLeave: () -> Unit,
    onClearTransient: () -> Unit,
    onLeft: () -> Unit = {},
    // Spec 17 header actions + search + mute (all real: calls route, search route, local mute).
    onVoiceCall: () -> Unit = {},
    onVideoCall: () -> Unit = {},
    onSearchChat: () -> Unit = {},
    onToggleMute: () -> Unit = {}
) {
    var showRename by remember { mutableStateOf(false) }
    var showDesc by remember { mutableStateOf(false) }
    var showIcon by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var showLeave by remember { mutableStateOf(false) }
    var memberMenu by remember { mutableStateOf<Participant?>(null) }

    LaunchedEffect(uiState.left) {
        if (uiState.left) onLeft()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Group info") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            // Spec 33: member skeletons instead of a blank spinner screen.
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                com.ollacore.app.ui.common.SkeletonList(rows = 8)
            }
            return@Scaffold
        }
        // Admins section (spec 17): partitioned above the list (LazyListScope forbids remember).
        val admins = uiState.members.filter { it.role.equals("admin", ignoreCase = true) }
        val regulars = uiState.members.filter { !it.role.equals("admin", ignoreCase = true) }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Header: photo + name + description
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(64.dp)) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            if (uiState.iconUrl != null) {
                                AsyncImage(
                                    model = uiState.iconUrl,
                                    contentDescription = "Group photo",
                                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(Icons.Default.Group, contentDescription = "Group photo", modifier = Modifier.size(44.dp))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(uiState.name.ifBlank { "Group" }, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { showRename = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "Rename")
                        }
                    }
                    if (!uiState.description.isNullOrBlank()) {
                        Text(uiState.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { showDesc = true }) {
                        Text(if (uiState.description.isNullOrBlank()) "Add description" else "Edit description")
                    }
                    TextButton(onClick = { showIcon = true }) { Text("Change icon") }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "${uiState.members.size} members",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    // Spec 17 header actions: audio / video / add / search.
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        GroupHeaderAction(icon = Icons.Default.Call, label = "Audio", onClick = onVoiceCall)
                        GroupHeaderAction(icon = Icons.Default.Videocam, label = "Video", onClick = onVideoCall)
                        GroupHeaderAction(icon = Icons.Default.PersonAdd, label = "Add", onClick = { showAdd = true })
                        GroupHeaderAction(icon = Icons.Default.Search, label = "Search", onClick = onSearchChat)
                    }
                }
                if (uiState.isWorking) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                uiState.error?.let { err ->
                    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.errorContainer) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(err, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = onClearTransient) { Text("Dismiss") }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                uiState.inviteText?.let { inv ->
                    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Link, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(inv, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = onClearTransient) { Text("Done") }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // Quick actions: Mute (client-only, enforced) + Invite link.
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    ListItem(
                        headlineContent = { Text("Mute notifications") },
                        supportingContent = { Text("Silence this group on this device") },
                        leadingContent = {
                            Icon(
                                if (uiState.isMuted) Icons.Default.NotificationsOff else Icons.Default.Notifications,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        trailingContent = {
                            Switch(checked = uiState.isMuted, onCheckedChange = { onToggleMute() })
                        }
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { showAdd = true }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add member")
                        }
                        FilledTonalButton(onClick = onInvite, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Invite link")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Admins section (spec 17) + Members.
            if (uiState.members.isEmpty()) {
                item {
                    Text(
                        "No members loaded - pull to refresh.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
            if (admins.isNotEmpty()) {
                item {
                    Text(
                        "Admins (${admins.size})",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                items(admins, key = { "admin-${it.principalId}" }) { member ->
                    MemberRow(member = member, onClick = { memberMenu = member })
                }
            }
            item {
                Text(
                    "Members (${regulars.size})${if (uiState.isAdmin) " - you are admin" else ""}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            items(regulars, key = { it.principalId }) { member ->
                MemberRow(member = member, onClick = { memberMenu = member })
            }

            // Leave
            item {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { showLeave = true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Leave group")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Add/remove/rename/icon/admin/invite/leave use conventional Ollacore room endpoints - rejections mean backend work is required.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Dialogs
    if (showRename) {
        var v by remember { mutableStateOf(uiState.name) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename group") },
            text = { OutlinedTextField(value = v, onValueChange = { v = it }, label = { Text("Group name") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { onRename(v.trim()); showRename = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } }
        )
    }
    if (showDesc) {
        var v by remember { mutableStateOf(uiState.description ?: "") }
        AlertDialog(
            onDismissRequest = { showDesc = false },
            title = { Text("Group description") },
            text = { OutlinedTextField(value = v, onValueChange = { v = it }, label = { Text("Description") }, maxLines = 3, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { onDescription(v.trim()); showDesc = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showDesc = false }) { Text("Cancel") } }
        )
    }
    if (showIcon) {
        var v by remember { mutableStateOf(uiState.iconUrl ?: "") }
        AlertDialog(
            onDismissRequest = { showIcon = false },
            title = { Text("Change icon") },
            text = {
                Column {
                    OutlinedTextField(value = v, onValueChange = { v = it }, label = { Text("Icon image URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Backend check required - icon URL storage may not exist yet.", style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = { TextButton(onClick = { onIcon(v.trim()); showIcon = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showIcon = false }) { Text("Cancel") } }
        )
    }
    if (showAdd) {
        var v by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add member") },
            text = {
                Column {
                    OutlinedTextField(value = v, onValueChange = { v = it }, label = { Text("User ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Pick the user ID from Contacts (backend check required).", style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = { TextButton(onClick = { onAddMember(v.trim()); showAdd = false }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } }
        )
    }
    memberMenu?.let { member ->
        val isAdmin = member.role.equals("admin", ignoreCase = true)
        AlertDialog(
            onDismissRequest = { memberMenu = null },
            title = { Text(member.displayName ?: member.phone ?: "Member") },
            text = {
                Column {
                    ListItem(headlineContent = { Text(if (isAdmin) "Dismiss as admin" else "Make group admin") }, leadingContent = { Icon(Icons.Default.AdminPanelSettings, null) },
                        modifier = Modifier.clickable { onSetAdmin(member.principalId, !isAdmin); memberMenu = null })
                    ListItem(headlineContent = { Text("Remove from group") }, leadingContent = { Icon(Icons.Default.PersonRemove, null) },
                        modifier = Modifier.clickable { onRemoveMember(member.principalId); memberMenu = null })
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { memberMenu = null }) { Text("Cancel") } }
        )
    }
    if (showLeave) {
        AlertDialog(
            onDismissRequest = { showLeave = false },
            title = { Text("Leave group?") },
            text = { Text("You will stop receiving messages from this group.") },
            confirmButton = { TextButton(onClick = { onLeave(); showLeave = false }) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { showLeave = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun GroupHeaderAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(52.dp)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun MemberRow(member: Participant, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(
                member.displayName ?: member.phone ?: member.principalId.take(8),
                style = MaterialTheme.typography.titleMedium
            )
        },
        supportingContent = { Text(member.phone ?: member.principalId) },
        leadingContent = {
                        com.ollacore.app.ui.theme.BrandAvatar(
                            name = member.displayName ?: member.phone ?: member.principalId,
                            size = 40.dp
                        )
        },
        trailingContent = {
            if (member.role.equals("admin", ignoreCase = true)) {
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer) {
                    Text("admin", modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
    HorizontalDivider()
}
