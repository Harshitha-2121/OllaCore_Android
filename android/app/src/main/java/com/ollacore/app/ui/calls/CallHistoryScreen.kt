package com.ollacore.app.ui.calls

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhoneMissed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ollacore.app.data.local.CallDirection
import com.ollacore.app.data.local.CallLogEntry
import com.ollacore.app.data.local.CallStatus
import com.ollacore.app.ui.home.WaBg
import com.ollacore.app.ui.home.WaCard
import com.ollacore.app.ui.home.WaGreen
import com.ollacore.app.ui.home.WaSub
import com.ollacore.app.ui.home.WaText
import com.ollacore.app.ui.theme.BrandAvatar

private val WaRed = Color(0xFFF15C6D)

/**
 * Calls tab, WhatsApp-reference dark styling. Same contract as before:
 * tap row = call back (voice/video per entry), long-press = delete entry,
 * ⋮ menu = clear-all / scheduled / settings. CLIENT-ONLY history, no backend.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CallHistoryContent(
    log: List<CallLogEntry>,
    onCallBack: (CallLogEntry) -> Unit,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit,
    onSearch: () -> Unit = {},
    onSettings: () -> Unit = {},
    onNewCall: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<CallLogEntry?>(null) }
    var showScheduled by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(WaBg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Header: Calls + search + ⋮ ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 18.dp, bottom = 6.dp)
            ) {
                Text(
                    "Calls",
                    color = WaText,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onSearch) {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = WaText)
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Calls menu", tint = WaText)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = WaCard,
                        modifier = Modifier.widthIn(min = 230.dp, max = 290.dp)
                    ) {
                        CallsMenuRow("Clear call log") { showMenu = false; confirmClear = true }
                        CallsMenuRow("Scheduled calls") { showMenu = false; showScheduled = true }
                        CallsMenuRow("Settings") { showMenu = false; onSettings() }
                    }
                }
            }

            if (log.isEmpty()) {
                // Dark empty state (keeps the header + FAB visible).
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 120.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(88.dp)
                            .clip(CircleShape)
                            .background(WaCard)
                    ) {
                        Icon(Icons.Default.Phone, contentDescription = null, tint = WaSub,
                            modifier = Modifier.size(40.dp))
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("No calls yet", color = WaText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Calls you make or receive will appear here.",
                        color = WaSub, style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    // ── Call / Schedule action buttons ──
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        modifier = Modifier.padding(start = 24.dp, top = 10.dp, bottom = 4.dp)
                    ) {
                        CallActionButton(
                            icon = Icons.Default.Phone,
                            label = "Call",
                            contentDesc = "New call",
                            onClick = onNewCall
                        )
                        CallActionButton(
                            icon = Icons.Default.CalendarMonth,
                            label = "Schedule",
                            contentDesc = "Scheduled calls",
                            onClick = { showScheduled = true }
                        )
                    }

                    Text(
                        "Recent",
                        color = WaText,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp)
                    )

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 110.dp)
                    ) {
                        items(log, key = { it.id }) { entry ->
                            CallRow(
                                entry = entry,
                                onCallBack = { onCallBack(entry) },
                                onDelete = { deleteTarget = entry }
                            )
                        }
                    }
                }
            }
        }

        // ── Green new-call FAB ──
        Surface(
            shape = CircleShape,
            color = WaGreen,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 18.dp)
                .size(62.dp)
                .clickable(onClick = onNewCall)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(Icons.Default.Phone, contentDescription = "New call", tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(26.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 2.dp, y = 2.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(WaGreen)
                        .border(2.dp, WaBg, CircleShape)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp))
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear call log?") },
            text = { Text("This only clears history on this device.") },
            confirmButton = {
                TextButton(onClick = { onClearAll(); confirmClear = false }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete this call?") },
            text = {
                Text("Remove ${target.peerName.ifBlank { "this call" }} from history on this device?")
            },
            confirmButton = {
                TextButton(onClick = { onDelete(target.id); deleteTarget = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }

    if (showScheduled) {
        AlertDialog(
            onDismissRequest = { showScheduled = false },
            title = { Text("Scheduled calls") },
            text = {
                Text(
                    "Scheduling calls needs backend support for reminders and invites. " +
                        "Start an instant call with the green button instead."
                )
            },
            confirmButton = { TextButton(onClick = { showScheduled = false }) { Text("Got it") } }
        )
    }
}

@Composable
private fun CallsMenuRow(title: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(title, style = MaterialTheme.typography.bodyMedium, color = WaText) },
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp)
    )
}

@Composable
private fun CallActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, contentDesc: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(WaCard)
        ) {
            Icon(icon, contentDescription = contentDesc, tint = WaText, modifier = Modifier.size(26.dp))
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(label, color = WaSub, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CallRow(entry: CallLogEntry, onCallBack: () -> Unit, onDelete: () -> Unit) {
    val missed = entry.status == CallStatus.MISSED
    val nameColor = if (missed) WaRed else WaText
    // Direction arrow under the name carries the status color.
    val (arrowIcon, arrowTint) = when {
        missed -> Icons.Default.PhoneMissed to WaRed
        entry.direction == CallDirection.OUTGOING -> Icons.AutoMirrored.Filled.CallMade to WaGreen
        else -> Icons.AutoMirrored.Filled.CallReceived to WaGreen
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onCallBack, onLongClick = onDelete)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        BrandAvatar(name = entry.peerName.ifBlank { "Unknown" }, size = 50.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.peerName.ifBlank { "Unknown" },
                color = nameColor, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(arrowIcon, contentDescription = null, tint = arrowTint, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    dateLine(entry),
                    color = WaSub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        IconButton(onClick = onCallBack) {
            Icon(
                if (entry.audioOnly == true) Icons.Default.Phone else Icons.Default.Videocam,
                contentDescription = "Call back",
                tint = WaGreen,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

private fun dateLine(entry: CallLogEntry): String {
    val whenText = if (entry.startedAt > 0L) {
        DateUtils.getRelativeTimeSpanString(
            entry.startedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        ).toString()
    } else ""
    val detail = when {
        entry.status == CallStatus.MISSED -> "Missed"
        entry.status == CallStatus.CANCELLED -> "Cancelled"
        entry.status == CallStatus.BUSY -> "Busy"
        entry.status == CallStatus.DECLINED -> "Declined"
        entry.status == CallStatus.FAILED -> "Failed"
        entry.status == CallStatus.COMPLETED && entry.durationSec > 0 -> formatDuration(entry.durationSec)
        else -> ""
    }
    return listOf(whenText, detail).filter { it.isNotBlank() }.joinToString(" • ")
}

private fun formatDuration(totalSec: Long): String {
    val m = totalSec / 60
    val s = totalSec % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}
