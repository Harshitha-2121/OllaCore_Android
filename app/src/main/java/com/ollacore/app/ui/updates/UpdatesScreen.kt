package com.ollacore.app.ui.updates

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ollacore.app.ui.home.WaBg
import com.ollacore.app.ui.home.WaCard
import com.ollacore.app.ui.home.WaDivider
import com.ollacore.app.ui.home.WaGreen
import com.ollacore.app.ui.home.WaSub
import com.ollacore.app.ui.home.WaText
import com.ollacore.app.ui.theme.BrandAvatar

// Real-data binding points. The Status/Channels backend services do not exist
// yet (see OLLACORE-BACKEND-SPEC.txt), so both lists are empty and the page
// renders its empty states. When the backend lands, feed these lists from the
// Status/Channels repository - no UI changes needed.
private data class StatusEntry(val name: String, val time: String, val ring: List<Color>?)
private data class ChannelEntry(
    val name: String,
    val preview: String,
    val time: String,
    val unread: Int,
    val verified: Boolean = false
)

private fun loadStatuses(): List<StatusEntry> = emptyList()
private fun loadChannels(): List<ChannelEntry> = emptyList()
private fun loadSuggestedChannels(): List<ChannelEntry> = emptyList()

private enum class UpdatesDialog { NONE, CREATE_CHANNEL, STARRED, ADS, STATUS_COMPOSER }

/**
 * Updates tab matching the WhatsApp reference: dark page, large header with
 * search + 3-dot menu, horizontal Status rail, Channels list with Explore pill,
 * Find-channels section, pencil + camera FABs. Status/Channels need backend
 * services, so rows are clearly demo content and creation flows say so.
 */
@Composable
fun UpdatesContent(
    onSearch: () -> Unit = {},
    onSettings: () -> Unit = {},
    onPrivacy: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf(UpdatesDialog.NONE) }
    // Bound to the real data source (empty until the backend services land).
    val statuses = remember { loadStatuses() }
    val channels = remember { loadChannels() }
    val suggested = remember { loadSuggestedChannels() }
    // Local-only follow toggles (active once real channels arrive).
    var following by remember { mutableStateOf(setOf<String>()) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(WaBg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Header: Updates + search + ⋮ ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 18.dp, bottom = 6.dp)
            ) {
                Text(
                    "Updates",
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
                        Icon(Icons.Default.MoreVert, contentDescription = "Updates menu", tint = WaText)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.widthIn(min = 230.dp, max = 290.dp)
                    ) {
                        UpdatesMenuRow(Icons.Default.GroupAdd, "Create channel") {
                            showMenu = false; dialog = UpdatesDialog.CREATE_CHANNEL
                        }
                        UpdatesMenuRow(Icons.Default.Lock, "Status privacy") {
                            showMenu = false; onPrivacy()
                        }
                        UpdatesMenuRow(Icons.Default.Star, "Starred") {
                            showMenu = false; dialog = UpdatesDialog.STARRED
                        }
                        UpdatesMenuRow(Icons.Default.Tune, "Ad preferences") {
                            showMenu = false; dialog = UpdatesDialog.ADS
                        }
                        UpdatesMenuRow(Icons.Default.Settings, "Settings") {
                            showMenu = false; onSettings()
                        }
                    }
                }
            }

            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // ── Status rail ──
                Text(
                    "Status",
                    color = WaText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 10.dp)
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item(key = "add") { AddStatusCard(onClick = { dialog = UpdatesDialog.STATUS_COMPOSER }) }
                    items(statuses, key = { it.name }) { entry ->
                        StatusCard(entry = entry, onClick = { dialog = UpdatesDialog.STATUS_COMPOSER })
                    }
                }

                // ── Channels header + Explore pill ──
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 16.dp, top = 22.dp, bottom = 6.dp)
                ) {
                    Text(
                        "Channels",
                        color = WaText,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = WaCard,
                        modifier = Modifier.clickable { dialog = UpdatesDialog.CREATE_CHANNEL }
                    ) {
                        Text(
                            "Explore",
                            color = WaGreen,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }

                // ── Channel rows (real data only; empty state when none) ──
                if (channels.isEmpty()) {
                    Text(
                        "No channels yet. Channels you follow will appear here.",
                        color = WaSub, fontSize = 13.sp,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp)
                    )
                } else {
                    channels.forEach { channel ->
                        ChannelRow(channel = channel, onClick = { dialog = UpdatesDialog.CREATE_CHANNEL })
                        HorizontalDivider(color = WaDivider, thickness = 0.75.dp,
                            modifier = Modifier.padding(start = 84.dp))
                    }
                }

                // ── Find channels to follow ──
                Text(
                    "Find channels to follow",
                    color = WaText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp)
                )
                if (suggested.isEmpty()) {
                    Text(
                        "Channels you can follow will appear here.",
                        color = WaSub, fontSize = 13.sp,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp)
                    )
                }
                suggested.forEach { channel ->
                    val isFollowing = channel.name in following
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                    ) {
                        BrandAvatar(name = channel.name, size = 46.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                channel.name, color = WaText, fontSize = 16.sp,
                                fontWeight = FontWeight.Bold, maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                channel.preview, color = WaSub, fontSize = 13.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(onClick = {
                            following = if (isFollowing) following - channel.name
                            else following + channel.name
                        }) {
                            Text(
                                if (isFollowing) "Following" else "Follow",
                                color = if (isFollowing) WaSub else WaGreen,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                // Clearance so scrolled-to-bottom content clears the FAB stack.
                Spacer(modifier = Modifier.height(180.dp))
            }
        }

        // ── FABs (above bottom nav): pencil tile + green camera button ──
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 18.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = WaCard,
                modifier = Modifier
                    .size(50.dp)
                    .clickable { dialog = UpdatesDialog.STATUS_COMPOSER }
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Default.Edit, contentDescription = "New text status", tint = WaText)
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Surface(
                shape = CircleShape,
                color = WaGreen,
                modifier = Modifier
                    .size(62.dp)
                    .clickable { dialog = UpdatesDialog.STATUS_COMPOSER }
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = "New photo status", tint = MaterialTheme.colorScheme.onPrimary)
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
                        Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }

    when (dialog) {
        UpdatesDialog.NONE -> {}
        UpdatesDialog.CREATE_CHANNEL -> UpdatesInfoDialog(
            title = "Create channel",
            body = "Channels need the Ollacore Channels service (one-way broadcast API), " +
                "which does not exist yet. See OLLACORE-BACKEND-SPEC.txt for the endpoint design. " +
                "Channel creation unlocks automatically once the backend lands.",
            onDismiss = { dialog = UpdatesDialog.NONE }
        )
        UpdatesDialog.STARRED -> UpdatesInfoDialog(
            title = "Starred messages",
            body = "Starred messages live inside each chat today: long-press any message " +
                "and tap Star. A cross-chat Starred view arrives with the backend.",
            onDismiss = { dialog = UpdatesDialog.NONE }
        )
        UpdatesDialog.ADS -> UpdatesInfoDialog(
            title = "Ad preferences",
            body = "Ollacore shows no ads, so there is nothing to configure here.",
            onDismiss = { dialog = UpdatesDialog.NONE }
        )
        UpdatesDialog.STATUS_COMPOSER -> UpdatesInfoDialog(
            title = "Status",
            body = "Posting status needs the Ollacore Status service (24h TTL, viewers, " +
                "privacy), which does not exist yet. The cards above are preview content.",
            onDismiss = { dialog = UpdatesDialog.NONE }
        )
    }
}

@Composable
private fun UpdatesMenuRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(icon, contentDescription = title, tint = WaText, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(14.dp))
                Text(title, style = MaterialTheme.typography.bodyMedium, color = WaText,
                    modifier = Modifier.weight(1f))
            }
        },
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp)
    )
}

/** First rail card: dashed circle with + badge. */
@Composable
private fun AddStatusCard(onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Box(contentAlignment = Alignment.BottomEnd, modifier = Modifier.size(64.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(WaCard)
            ) {
                Icon(Icons.Default.Person, contentDescription = null, tint = WaSub, modifier = Modifier.size(30.dp))
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(WaGreen)
                    .border(2.dp, WaBg, CircleShape)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add status", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text("Add status", color = WaSub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(72.dp))
    }
}

/** Contact status: avatar with colored ring (muted gray when viewed) + name + time. */
@Composable
private fun StatusCard(entry: StatusEntry, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        val ring = entry.ring
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(68.dp)
                .then(
                    if (ring != null) Modifier.border(
                        width = 3.dp,
                        brush = Brush.sweepGradient(ring + ring.first()),
                        shape = CircleShape
                    ) else Modifier.border(width = 2.dp, color = WaDivider, shape = CircleShape)
                )
                .padding(4.dp)
        ) {
            BrandAvatar(name = entry.name, size = 56.dp)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            entry.name.substringBefore(" "), color = WaText, fontSize = 12.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.width(72.dp)
        )
    }
}

/** Channel row: avatar, bold name (+verified), preview, time + green unread badge. */
@Composable
private fun ChannelRow(channel: ChannelEntry, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        BrandAvatar(name = channel.name, size = 50.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    channel.name, color = WaText, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                )
                if (channel.verified) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.Default.Verified, contentDescription = "Verified channel",
                        tint = WaSub, modifier = Modifier.size(16.dp))
                }
            }
            Text(
                channel.preview, color = WaSub, fontSize = 13.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (channel.time.isNotBlank()) Text(
                channel.time,
                color = if (channel.unread > 0) WaGreen else WaSub,
                fontSize = 12.sp, maxLines = 1
            )
            if (channel.unread > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .sizeIn(minWidth = 22.dp, minHeight = 22.dp)
                        .clip(CircleShape)
                        .background(WaGreen)
                        .padding(horizontal = 6.dp)
                ) {
                    Text(
                        if (channel.unread > 99) "99+" else channel.unread.toString(),
                        color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdatesInfoDialog(title: String, body: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } }
    )
}
