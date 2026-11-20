package com.ollacore.app.ui.settings

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ollacore.app.data.local.ChatPrefsStore.SettingsKeys
import com.ollacore.app.ui.home.WaBg
import com.ollacore.app.ui.home.WaDivider
import com.ollacore.app.ui.home.WaGreen
import com.ollacore.app.ui.home.WaSub
import com.ollacore.app.ui.home.WaText
import com.ollacore.app.ui.theme.BrandAvatar

/** Presentation only: profile state and navigation are supplied by the existing app host. */
@Composable
internal fun SettingsHome(
    onProfile: () -> Unit,
    onPrivacy: () -> Unit,
    onNotifications: () -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    displayName: String? = null,
    about: String? = null,
    avatarUrl: String? = null,
    profileLoading: Boolean = false,
    profileError: String? = null
) {
    val context = LocalContext.current
    var shareFailed by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxSize().testTag("settings_list")
            .verticalScroll(rememberScrollState()).padding(bottom = 24.dp)
    ) {
        SettingsProfileHeader(displayName, about, avatarUrl, profileLoading, profileError, onProfile)
        HorizontalDivider(color = WaDivider, thickness = 0.75.dp)
        Spacer(Modifier.height(8.dp))
        NavRow(Icons.Default.Key, "Account", "Security and account information.",
            onClick = { onOpen("account") })
        NavRow(Icons.Default.Lock, "Privacy", "Last seen, profile visibility, and read receipts.",
            onClick = onPrivacy)
        NavRow(Icons.AutoMirrored.Filled.Chat, "Chats", "Theme, wallpaper, and chat preferences.",
            onClick = { onOpen("chats") })
        NavRow(Icons.Default.Notifications, "Notifications", "Message, group, and call notifications.",
            onClick = onNotifications)
        NavRow(Icons.Default.Storage, "Storage and data", "Storage usage, network usage, and media downloads.",
            onClick = { onOpen("storage") })
        NavRow(Icons.AutoMirrored.Filled.Help, "Help and support", "Help center and contact support.",
            onClick = { onOpen("help") })
        NavRow(Icons.Default.PersonAdd, "Invite a friend", "Share OllaChat with friends.", onClick = {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Join me on OllaChat. Connect. Chat. Share.")
            }
            // There is no verified public install/deep link configured in this app.
            shareFailed = runCatching {
                context.startActivity(Intent.createChooser(send, "Invite a friend"))
            }.isFailure
        })
    }
    if (shareFailed) {
        AlertDialog(
            onDismissRequest = { shareFailed = false },
            title = { Text("Unable to share") },
            text = { Text("No sharing app could be opened. Try again after installing a compatible app.") },
            confirmButton = { TextButton(onClick = { shareFailed = false }) { Text("Close") } }
        )
    }
}

@Composable
internal fun SettingsProfileHeader(
    displayName: String?,
    about: String?,
    avatarUrl: String?,
    loading: Boolean,
    error: String?,
    onProfile: () -> Unit
) {
    val name = displayName?.takeIf { it.isNotBlank() } ?: "Profile"
    val subtitle = when {
        loading -> "Loading profile…"
        !error.isNullOrBlank() -> "Profile update unavailable. Tap to view profile."
        else -> about?.takeIf { it.isNotBlank() } ?: "Add an About in your profile"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp)
            .testTag("settings_profile").clickable(role = Role.Button, onClickLabel = "Open profile", onClick = onProfile)
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        // The existing Coil loader handles image caching. Initials stay visible while
        // loading and after an error, rather than leaving a broken/empty photo.
        Box(Modifier.size(64.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
            BrandAvatar(name = displayName?.takeIf { it.isNotBlank() } ?: "?", size = 64.dp)
            if (!avatarUrl.isNullOrBlank()) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = null, // Decorative within the labelled profile action.
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleLarge, color = WaText,
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = WaSub,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Icon(Icons.Default.Edit, contentDescription = null, tint = WaGreen, modifier = Modifier.size(24.dp))
    }
}

@Composable
internal fun AccountSettings(
    onProfile: () -> Unit,
    onDevices: () -> Unit,
    onPrivacy: () -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        NavRow(Icons.Default.Person, "Profile", "Edit your name, photo, and About", onProfile)
        NavRow(Icons.Default.Devices, "Linked devices", "Manage where you use OllaChat", onDevices)
        NavRow(Icons.Default.Security, "Security", "App lock and security notifications", onPrivacy)
        NavRow(Icons.Default.Block, "Blocked accounts", "Manage contacts blocked on this device",
            onClick = { onOpen("blocked") })
        HorizontalDivider(color = WaDivider)
        Text("Phone number changes and account deletion are not available in this version.",
            style = MaterialTheme.typography.bodyMedium, color = WaSub, modifier = Modifier.padding(20.dp))
    }
}

@Composable
internal fun ChatSettings(onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    var showTheme by rememberSaveable { mutableStateOf(false) }
    val prefs = rememberPrefs()
    val lists by prefs.chatLists.collectAsState(initial = emptyMap())
    var showLists by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        NavRow(Icons.Default.Palette, "Appearance", "Chat themes, wallpaper, and bubbles",
            onClick = { onOpen("appearance") })
        NavRow(Icons.Default.DarkMode, "App theme", "Light, dark, or system default", onClick = { showTheme = true })
        PrefSwitchRow(Icons.AutoMirrored.Filled.KeyboardReturn, "Enter key to send",
            "Enter sends, Shift+Enter makes a new line", SettingsKeys.ENTER_SEND, true)
        PrefSwitchRow(Icons.Default.Archive, "Keep chats archived",
            "Opening a chat keeps it in the archive", SettingsKeys.KEEP_ARCHIVED, false)
        ChoiceRow(Icons.Default.Schedule, "Disappearing message timer suggestion", SettingsKeys.DISAPPEAR_DEFAULT,
            listOf("0" to "Off", "86400" to "24 hours", "604800" to "7 days", "7776000" to "90 days"), "0",
            "Prefills the timer picker in a chat. It does not enable a timer until you confirm it there.")
        NavRow(Icons.Default.Groups, "Chat lists", "View your saved chat lists", onClick = { showLists = true })
        HorizontalDivider(color = WaDivider)
        NavRow(Icons.Default.Accessibility, "Accessibility", "App text size and system display options",
            onClick = { onOpen("accessibility") })
        NavRow(Icons.Default.Language, "App language", "Device language and language selection",
            onClick = { onOpen("language") })
        Text("Chat backup and export are not available in this version.", color = WaSub,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp))
    }
    if (showTheme) ThemePickerDialogHost(onDismiss = { showTheme = false })
    if (showLists) {
        AlertDialog(
            onDismissRequest = { showLists = false }, title = { Text("Chat lists") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(if (lists.isEmpty()) "No lists yet." else lists.keys.sorted().joinToString("\n") { "$it (${lists[it]?.size ?: 0})" })
                    Spacer(Modifier.height(12.dp))
                    Text("Create lists and manage their chats from a chat's menu under Lists.")
                }
            },
            confirmButton = { TextButton(onClick = { showLists = false }) { Text("Close") } }
        )
    }
}

@Composable
internal fun HelpSettings(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val version = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "Unknown"
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Help and support", style = MaterialTheme.typography.titleMedium, color = WaText)
        Spacer(Modifier.height(8.dp))
        Text("A verified help center and support contact have not been configured for this build. In-app support tickets are not available yet.",
            style = MaterialTheme.typography.bodyMedium, color = WaSub)
        Spacer(Modifier.height(16.dp))
        Text("When reporting a problem through your app distributor, include your device model, app version, and steps to reproduce. Never share verification codes, passwords, or session credentials.",
            style = MaterialTheme.typography.bodyMedium, color = WaSub)
        Spacer(Modifier.height(16.dp))
        Text("App version: $version", style = MaterialTheme.typography.bodyMedium, color = WaText)
        Spacer(Modifier.height(16.dp))
        Text("Updates are supplied by your app distributor. There is no in-app updater.",
            style = MaterialTheme.typography.bodyMedium, color = WaSub)
    }
}
