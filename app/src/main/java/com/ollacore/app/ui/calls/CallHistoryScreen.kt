package com.ollacore.app.ui.calls

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhoneMissed
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ollacore.app.data.local.CallDirection
import com.ollacore.app.data.local.CallLogEntry
import com.ollacore.app.data.local.CallStatus

/**
 * Calls tab content: CLIENT-ONLY history (no backend).
 * Tap = call back (voice for audio entries, video otherwise).
 */
@Composable
fun CallHistoryContent(
    log: List<CallLogEntry>,
    onCallBack: (CallLogEntry) -> Unit,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmClear by remember { mutableStateOf(false) }
    var callTab by remember { mutableStateOf(0) } // Spec 20: All | Missed | Outgoing | Incoming
    val filtered = remember(log, callTab) {
        when (callTab) {
            1 -> log.filter { it.status == CallStatus.MISSED }
            2 -> log.filter { it.direction == CallDirection.OUTGOING }
            3 -> log.filter { it.direction == CallDirection.INCOMING }
            else -> log
        }
    }

    if (log.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Empty state: gradient highlight (allowed surface).
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(
                            androidx.compose.ui.graphics.Brush.linearGradient(
                                listOf(
                                    com.ollacore.app.ui.theme.OllaPrimaryBlue,
                                    com.ollacore.app.ui.theme.OllaPurple
                                )
                            )
                        )
                ) {
                    Icon(
                        Icons.Default.Phone,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                        tint = androidx.compose.ui.graphics.Color.White
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text("No calls yet.", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Calls you make or receive will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = callTab) {
            listOf("All", "Missed", "Outgoing", "Incoming").forEachIndexed { index, title ->
                Tab(
                    selected = callTab == index,
                    onClick = { callTab = index },
                    text = { Text(title) }
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${filtered.size} recent",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { confirmClear = true }) { Text("Clear all") }
        }
        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing here yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { it.id }) { entry ->
                    CallLogRow(entry = entry, onCallBack = { onCallBack(entry) }, onDelete = { onDelete(entry.id) })
                    HorizontalDivider()
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear call history?") },
            text = { Text("This only clears history on this device.") },
            confirmButton = {
                TextButton(onClick = { onClearAll(); confirmClear = false }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun CallLogRow(entry: CallLogEntry, onCallBack: () -> Unit, onDelete: () -> Unit) {
    val missed = entry.status == CallStatus.MISSED
    val nameColor = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = {
            Text(
                entry.peerName.ifBlank { "Unknown" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = nameColor
            )
        },
        supportingContent = {
            Text(
                "${relativeTime(entry.startedAt)}${if (entry.status == CallStatus.COMPLETED && entry.durationSec > 0) " • ${formatDuration(entry.durationSec)}" else ""}${statusSuffix(entry)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingContent = {
            Icon(
                when {
                    missed -> Icons.Default.PhoneMissed
                    entry.direction == CallDirection.OUTGOING -> Icons.AutoMirrored.Filled.CallMade
                    else -> Icons.AutoMirrored.Filled.CallReceived
                },
                contentDescription = null,
                tint = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Row {
                IconButton(onClick = onCallBack) {
                    Icon(
                        if (entry.audioOnly == true) Icons.Default.Phone else Icons.Default.Videocam,
                        contentDescription = "Call back"
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            }
        },
        modifier = Modifier.clickable(onClick = onCallBack)
    )
}

private fun statusSuffix(entry: CallLogEntry): String {
    return when (entry.status) {
        CallStatus.MISSED -> " • Missed"
        CallStatus.CANCELLED -> " • Cancelled"
        else -> ""
    } + if (entry.audioOnly == true) " • Voice" else " • Video"
}

private fun relativeTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    return DateUtils.getRelativeTimeSpanString(epochMillis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
}

private fun formatDuration(totalSec: Long): String {
    val m = totalSec / 60
    val s = totalSec % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}
