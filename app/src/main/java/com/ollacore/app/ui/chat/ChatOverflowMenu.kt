package com.ollacore.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ollacore.app.data.local.ChatPrefsStore

private val MenuDark = Color(0xFF1F2937)
private val MenuDarkText = Color(0xFFF9FAFB)
private val MenuDarkSub = Color(0xFF9CA3AF)
private val MenuDarkDivider = Color(0xFF374151)
private val MenuDarkDanger = Color(0xFFF87171)

/** Which modal dialog the overflow menu has open (menu stays mounted behind). */
private enum class MenuDialog {
    NONE, DISAPPEARING, REPORT, BLOCK, CLEAR, DELETE, CLOSE, NEW_LIST
}

private val REPORT_REASONS = listOf(
    "Spam", "Harassment or abuse", "Hate speech", "Violence or threats",
    "Sexually explicit content", "Scam or fraud", "Other"
)

/**
 * 3-dot chat overflow menu (WhatsApp-style ordering, dark popup anchored to ⋮).
 * DropdownMenu handles outside-tap + back-button dismiss, edge repositioning,
 * and its OWN internal scrolling when content exceeds the screen.
 *
 * CRASH CONSTRAINT (proven by logcat IllegalStateException): never place a
 * scrollable container (verticalScroll Column, LazyColumn) inside DropdownMenu
 * content - the Popup measures children with infinite max height. Rows are
 * emitted directly; Mute/Add-to-list expand inline; tall content scrolls via
 * the menu's built-in scroll. Same dark styling as the basic popup.
 */
@Composable
fun ChatOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    menu: ChatViewModel.ChatMenuState,
    peerName: String,
    isGroup: Boolean,
    onContactInfo: () -> Unit,
    onSearch: () -> Unit,
    onSelectMessages: () -> Unit,
    onMute: (Long?) -> Unit,
    onDisappearing: (Long) -> Unit,
    onToggleFavourite: () -> Unit,
    onCreateList: (String, (Boolean) -> Unit) -> Unit,
    onToggleListMember: (String, Boolean) -> Unit,
    onCloseChat: () -> Unit,
    onSendCallLink: () -> Unit,
    onNewGroupCall: () -> Unit,
    onReport: (String) -> Unit,
    onToggleBlock: () -> Unit,
    onClearChat: () -> Unit,
    onDeleteChat: () -> Unit
) {
    var muteOpen by remember(expanded) { mutableStateOf(false) }
    var listsOpen by remember(expanded) { mutableStateOf(false) }
    var dialog by remember(expanded) { mutableStateOf(MenuDialog.NONE) }

    val muted = menu.muteUntilMs != null
    val muteLabel = when (val u = menu.muteUntilMs) {
        null -> null
        ChatPrefsStore.MUTE_ALWAYS -> "Always"
        else -> {
            val leftMin = ((u - System.currentTimeMillis()) / 60000).coerceAtLeast(0)
            if (leftMin <= 0) null
            else if (leftMin < 90) "$leftMin min left"
            else "${(leftMin + 30) / 60}h left"
        }
    }
    val disappearLabel = when (menu.disappearingTtlSec) {
        ChatPrefsStore.DISAPPEAR_24H -> "24 hours"
        ChatPrefsStore.DISAPPEAR_7D -> "7 days"
        ChatPrefsStore.DISAPPEAR_90D -> "90 days"
        else -> "Off"
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { onDismiss() },
        shape = RoundedCornerShape(16.dp),
        containerColor = MenuDark,
        modifier = Modifier.widthIn(min = 250.dp, max = 300.dp)
    ) {
        MenuRow(Icons.Default.Info, "Contact info", contentDesc = "Open contact info") {
            onDismiss(); onContactInfo()
        }
        MenuRow(Icons.Default.Search, "Search", contentDesc = "Search messages in this chat") {
            onDismiss(); onSearch()
        }
        MenuRow(Icons.Default.SelectAll, "Select messages", contentDesc = "Enter multi-message selection mode") {
            onDismiss(); onSelectMessages()
        }
        // ── Mute with inline submenu ──
        MenuRow(
            icon = if (muted) Icons.Default.NotificationsOff else Icons.Default.Notifications,
            title = "Mute notifications",
            contentDesc = "Mute notification options",
            trailing = {
                if (muteLabel != null) Text(
                    muteLabel, style = MaterialTheme.typography.labelSmall, color = MenuDarkSub,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Icon(
                    if (muteOpen) Icons.Default.ExpandLess else Icons.Default.ChevronRight,
                    contentDescription = null, tint = MenuDarkSub, modifier = Modifier.size(20.dp)
                )
            },
            onClick = { muteOpen = !muteOpen }
        )
        if (muteOpen) {
            val now = System.currentTimeMillis()
            MuteOption("8 hours", selected = menu.muteUntilMs?.let { it != ChatPrefsStore.MUTE_ALWAYS && it - now in 1..9 * 60 * 60 * 1000 } == true) {
                onMute(ChatPrefsStore.MUTE_8H_MS); onDismiss()
            }
            MuteOption("1 week", selected = menu.muteUntilMs?.let { it != ChatPrefsStore.MUTE_ALWAYS && it - now > 9 * 60 * 60 * 1000 } == true) {
                onMute(ChatPrefsStore.MUTE_WEEK_MS); onDismiss()
            }
            MuteOption("Always", selected = menu.muteUntilMs == ChatPrefsStore.MUTE_ALWAYS) {
                onMute(ChatPrefsStore.MUTE_ALWAYS); onDismiss()
            }
            if (muted) MuteOption("Unmute", selected = false) {
                onMute(null); onDismiss()
            }
        }
        MenuRow(Icons.Default.Timer, "Disappearing messages", contentDesc = "Disappearing message settings",
            trailing = {
                Text(disappearLabel, style = MaterialTheme.typography.labelSmall, color = MenuDarkSub)
            },
            onClick = { dialog = MenuDialog.DISAPPEARING }
        )
        MenuRow(
            icon = if (menu.isFavourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            title = if (menu.isFavourite) "Remove from favourites" else "Add to favourites",
            contentDesc = "Toggle favourite",
            onClick = { onToggleFavourite() }
        )
        // ── Lists with inline submenu ──
        MenuRow(
            icon = Icons.Default.List,
            title = "Add to list",
            contentDesc = "Chat list membership",
            trailing = {
                if (menu.memberOfLists.isNotEmpty()) Text(
                    menu.memberOfLists.size.toString(),
                    style = MaterialTheme.typography.labelSmall, color = MenuDarkSub,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Icon(
                    if (listsOpen) Icons.Default.ExpandLess else Icons.Default.ChevronRight,
                    contentDescription = null, tint = MenuDarkSub, modifier = Modifier.size(20.dp)
                )
            },
            onClick = { listsOpen = !listsOpen }
        )
        if (listsOpen) {
            if (menu.allLists.isEmpty()) {
                Text(
                    "No lists yet",
                    style = MaterialTheme.typography.bodySmall, color = MenuDarkSub,
                    modifier = Modifier.padding(start = 52.dp, top = 4.dp, bottom = 4.dp)
                )
            }
            menu.allLists.keys.sorted().forEach { name ->
                val member = name in menu.memberOfLists
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clickable { onToggleListMember(name, !member) }
                        .padding(start = 52.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
                ) {
                    Checkbox(
                        checked = member,
                        onCheckedChange = { onToggleListMember(name, it) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MenuDarkText,
                            checkmarkColor = MenuDark,
                            uncheckedColor = MenuDarkSub
                        ),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(name, style = MaterialTheme.typography.bodyMedium, color = MenuDarkText)
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clickable { dialog = MenuDialog.NEW_LIST }
                    .padding(start = 52.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = MenuDarkSub, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text("New list", style = MaterialTheme.typography.bodyMedium, color = MenuDarkText)
            }
        }
        MenuRow(Icons.Default.Archive, "Close chat", contentDesc = "Archive this chat") {
            dialog = MenuDialog.CLOSE
        }

        HorizontalDivider(color = MenuDarkDivider, thickness = 0.75.dp)

        MenuRow(Icons.Default.Link, "Send call link", contentDesc = "Generate and send a call link") {
            onDismiss(); onSendCallLink()
        }
        MenuRow(Icons.Default.GroupAdd, "New group call", contentDesc = "Pick contacts and start a group call") {
            onDismiss(); onNewGroupCall()
        }

        HorizontalDivider(color = MenuDarkDivider, thickness = 0.75.dp)

        MenuRow(Icons.Default.Flag, "Report", contentDesc = "Report this chat") {
            dialog = MenuDialog.REPORT
        }
        MenuRow(
            icon = Icons.Default.Block,
            title = if (menu.isBlocked) "Unblock" else "Block",
            contentDesc = "Block or unblock this contact",
            onClick = {
                if (menu.isBlocked) { onToggleBlock() } else { dialog = MenuDialog.BLOCK }
            }
        )
        MenuRow(Icons.Default.DeleteSweep, "Clear chat", contentDesc = "Clear messages in this view") {
            dialog = MenuDialog.CLEAR
        }
        MenuRow(
            icon = Icons.Default.Delete,
            title = "Delete chat",
            contentDesc = "Delete this conversation",
            danger = true,
            onClick = { dialog = MenuDialog.DELETE }
        )
    }

    // ── Dialogs (menu stays mounted behind; back closes dialog first) ──
    when (dialog) {
        MenuDialog.NONE -> {}
        MenuDialog.DISAPPEARING -> DisappearingDialog(
            // Prefill: explicit room choice wins, else the Settings global default.
            current = if (menu.disappearingTtlSec != 0L) menu.disappearingTtlSec
            else menu.defaultDisappearingTtlSec,
            onPick = { onDisappearing(it); dialog = MenuDialog.NONE },
            onDismiss = { dialog = MenuDialog.NONE }
        )
        MenuDialog.REPORT -> ReportDialog(
            peerName = peerName,
            onSubmit = { onReport(it); dialog = MenuDialog.NONE; onDismiss() },
            onDismiss = { dialog = MenuDialog.NONE }
        )
        MenuDialog.BLOCK -> ConfirmDialog(
            title = "Block $peerName?",
            body = "Blocked contacts can't message you. Their messages stop appearing here. " +
                "You can unblock anytime from this same menu.",
            confirm = "Block",
            danger = true,
            onConfirm = { onToggleBlock(); dialog = MenuDialog.NONE; onDismiss() },
            onDismiss = { dialog = MenuDialog.NONE }
        )
        MenuDialog.CLEAR -> ConfirmDialog(
            title = "Clear chat?",
            body = "Messages are removed from this view on this device only. " +
                "$peerName keeps their copy and server history is unchanged.",
            confirm = "Clear",
            onConfirm = { onClearChat(); dialog = MenuDialog.NONE; onDismiss() },
            onDismiss = { dialog = MenuDialog.NONE }
        )
        MenuDialog.DELETE -> ConfirmDialog(
            title = "Delete chat?",
            body = if (isGroup) "You will leave this group and the conversation is removed " +
                "from your chats. This can't be undone."
            else "This conversation is removed from your chats. $peerName is kept as a contact. " +
                "A new message from them will bring the chat back.",
            confirm = "Delete",
            danger = true,
            // Close dialog+menu either way: success navigates home, failure shows
            // the inline error banner in chat (never trap the user on a dialog).
            onConfirm = { onDeleteChat(); dialog = MenuDialog.NONE; onDismiss() },
            onDismiss = { dialog = MenuDialog.NONE }
        )
        MenuDialog.CLOSE -> ConfirmDialog(
            title = "Close chat?",
            body = "The chat moves to Archived and leaves your main list. " +
                "Reopen it anytime from Archived.",
            confirm = "Close chat",
            onConfirm = { onCloseChat() },
            onDismiss = { dialog = MenuDialog.NONE }
        )
        MenuDialog.NEW_LIST -> NewListDialog(
            onCreate = { onCreateList(it) { dialog = MenuDialog.NONE } },
            onDismiss = { dialog = MenuDialog.NONE }
        )
    }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    title: String,
    contentDesc: String,
    danger: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(icon, contentDescription = contentDesc,
                    tint = if (danger) MenuDarkDanger else MenuDarkText, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(14.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (danger) MenuDarkDanger else MenuDarkText,
                    modifier = Modifier.weight(1f)
                )
                trailing?.invoke(this)
            }
        },
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp)
    )
}

@Composable
private fun MuteOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(onClick = onClick)
            .padding(start = 52.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MenuDarkText,
            modifier = Modifier.weight(1f)
        )
        if (selected) Icon(Icons.Default.Check, contentDescription = "Selected",
            tint = MenuDarkText, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun DisappearingDialog(current: Long, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    var picked by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Disappearing messages") },
        text = {
            Column {
                listOf(
                    ChatPrefsStore.DISAPPEAR_OFF to "Off",
                    ChatPrefsStore.DISAPPEAR_24H to "24 hours",
                    ChatPrefsStore.DISAPPEAR_7D to "7 days",
                    ChatPrefsStore.DISAPPEAR_90D to "90 days"
                ).forEach { (value, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { picked = value }
                            .padding(vertical = 8.dp)
                    ) {
                        RadioButton(selected = picked == value, onClick = { picked = value })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Your choice is saved and shown. Server-side auto-delete needs backend TTL support.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = { onPick(picked) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ReportDialog(peerName: String, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var picked by remember { mutableStateOf(REPORT_REASONS[0]) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report $peerName?") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                REPORT_REASONS.forEach { reason ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { picked = reason }
                            .padding(vertical = 6.dp)
                    ) {
                        RadioButton(selected = picked == reason, onClick = { picked = reason })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(reason, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "The report is queued on this device until the report endpoint lands.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(picked) }) { Text("Submit report") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun NewListDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New list") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 40) name = it },
                singleLine = true,
                placeholder = { Text("List name") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.trim()) }, enabled = name.trim().isNotEmpty()) {
                Text("Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Group-call sheet: multi-select inbox contacts + group name, then create a real
 * group conversation (existing API) and start the call there. No mock calls.
 * (Kept: hosted by MainActivity chat route; opened from the full menu.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupCallSheet(
    peerName: String,
    peerUserId: String?,
    onLoadCandidates: suspend () -> List<ChatViewModel.CallCandidate>,
    onStart: (String, List<String>, (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var candidates by remember { mutableStateOf<List<ChatViewModel.CallCandidate>?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var groupName by remember { mutableStateOf("") }
    var starting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        candidates = runCatching { onLoadCandidates() }.getOrElse { emptyList() }
    }
    // Include the current peer by default when known.
    LaunchedEffect(candidates) {
        peerUserId?.takeIf { it.isNotBlank() }?.let { selected = selected + it }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text("New group call", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Pick contacts, then start. A group is created first (existing API), the call starts inside it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = groupName,
                onValueChange = { if (it.length <= 40) groupName = it },
                singleLine = true,
                placeholder = { Text("Group name (optional)") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            when (val list = candidates) {
                null -> {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 16.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Loading contacts…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                else -> {
                    if (list.isEmpty()) {
                        Text("No contacts found.", style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 12.dp))
                    } else {
                        // Plain Column: candidate lists are short; avoids nested-scroll crash class.
                        Column {
                            // Current peer row when not in inbox candidates.
                            if (peerUserId?.isNotBlank() == true && list.none { it.userId == peerUserId }) {
                                CandidateRow(
                                    name = peerName, phone = "",
                                    checked = peerUserId in selected,
                                    onCheck = {
                                        selected = if (it) selected + peerUserId else selected - peerUserId
                                    }
                                )
                            }
                            list.forEach { c ->
                                CandidateRow(
                                    name = c.name, phone = c.phone,
                                    checked = c.userId in selected,
                                    onCheck = {
                                        selected = if (it) selected + c.userId else selected - c.userId
                                    }
                                )
                            }
                        }
                    }
                }
            }
            error?.let {
                Spacer(modifier = Modifier.height(6.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    if (selected.isEmpty()) { error = "Select at least one contact."; return@Button }
                    starting = true
                    error = null
                    onStart(groupName.trim(), selected.toList()) { roomId ->
                        if (roomId == null) {
                            starting = false
                            error = "Couldn't create the group. Check connection and try again."
                        }
                    }
                },
                enabled = !starting && selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (starting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Start group call (${selected.size})")
            }
        }
    }
}

@Composable
private fun CandidateRow(name: String, phone: String, checked: Boolean, onCheck: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onCheck(!checked) }
            .padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheck)
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (phone.isNotBlank()) Text(phone, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
