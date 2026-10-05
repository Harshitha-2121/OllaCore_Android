@file:OptIn(ExperimentalMaterial3Api::class)

package com.ollacore.app.ui.settings

import androidx.compose.material3.ExperimentalMaterial3Api

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ollacore.app.data.local.ChatPrefsStore
import com.ollacore.app.data.local.ChatPrefsStore.SettingsKeys
import com.ollacore.app.push.ContentFreePushManager
import com.ollacore.app.ui.home.WaBg
import com.ollacore.app.ui.home.WaGreen
import com.ollacore.app.ui.home.WaSub
import com.ollacore.app.ui.home.WaText
import com.ollacore.app.ui.theme.BrandAvatar
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Complete Settings experience (WhatsApp-style structure, Ollacore-native).
 * Dark, sectioned, every row tappable. Wiring rule: real backend/API where it
 * exists (profile, devices, privacy/notifications routes, theme, cache,
 * notification channels, locale, share), CLIENT-ONLY persistence where it is
 * device-local (toggles, choices, quality, language), and an honest dialog
 * everywhere a backend does not exist yet - never a dead or faked control.
 */
@Composable
fun SettingsRoot(
    onProfile: () -> Unit,
    onDevices: () -> Unit,
    onPrivacy: () -> Unit,
    onNotifications: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var screen by remember { mutableStateOf("root") }
    var screenTitle by remember { mutableStateOf("") }
    var screenArg by remember { mutableStateOf("") }
    fun open(id: String, title: String = "", arg: String = "") {
        screen = id; screenTitle = title; screenArg = arg
    }
    BackHandler(enabled = screen != "root") { screen = "root" }

    Scaffold(
        containerColor = WaBg,
        topBar = {
            TopAppBar(
                title = { Text(if (screen == "root") "Settings" else screenTitle, color = WaText) },
                navigationIcon = {
                    IconButton(onClick = { if (screen == "root") onBack() else screen = "root" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = WaText)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = WaBg)
            )
        },
        modifier = modifier
    ) { padding ->
        when (screen) {
            "blocked" -> BlockedScreen(modifier = Modifier.padding(padding))
            "storage" -> StorageScreen(modifier = Modifier.padding(padding))
            "language" -> LanguageScreen(modifier = Modifier.padding(padding))
            "accessibility" -> AccessibilityScreen(modifier = Modifier.padding(padding))
            "notifications_adv" -> NotificationSoundScreen(modifier = Modifier.padding(padding))
            "appearance" -> com.ollacore.app.ui.appearance.AppearanceFlow(
                onBack = { screen = "root" },
                modifier = Modifier.padding(padding)
            )
            "help" -> HelpScreen(
                topic = screenArg,
                title = screenTitle,
                modifier = Modifier.padding(padding)
            )
            else -> SettingsHome(
                onProfile = onProfile,
                onDevices = onDevices,
                onPrivacy = onPrivacy,
                onNotifications = onNotifications,
                onOpen = ::open,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

// ── Prefs helpers ─────────────────────────────────────────────────────────

@Composable
private fun rememberPrefs(): ChatPrefsStore {
    val context = LocalContext.current
    return remember(context) { ChatPrefsStore(context.applicationContext) }
}

@Composable
private fun PrefSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    key: String,
    default: Boolean,
    scopeNote: String? = null
) {
    val prefs = rememberPrefs()
    val scope = rememberCoroutineScope()
    val checked by prefs.customBoolFlow(key, default).collectAsState(initial = default)
    ListItem(
        headlineContent = { Text(title, color = WaText) },
        supportingContent = {
            Column {
                Text(subtitle, color = WaSub)
                scopeNote?.let { Text(it, color = WaSub, fontSize = 12.sp) }
            }
        },
        leadingContent = { Icon(icon, contentDescription = null, tint = WaGreen) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = { scope.launch { runCatching { prefs.setCustomBool(key, it) } } }
            )
        },
        colors = ListItemDefaults.colors(containerColor = WaBg)
    )
}

@Composable
private fun ChoiceRow(
    icon: ImageVector,
    title: String,
    key: String,
    options: List<Pair<String, String>>,
    default: String,
    scopeNote: String? = null
) {
    val prefs = rememberPrefs()
    val scope = rememberCoroutineScope()
    val current by prefs.customFlow(key, default).collectAsState(initial = default)
    var open by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == current }?.second ?: current
    ListItem(
        headlineContent = { Text(title, color = WaText) },
        supportingContent = {
            Column {
                Text(label, color = WaSub)
                scopeNote?.let { Text(it, color = WaSub, fontSize = 12.sp) }
            }
        },
        leadingContent = { Icon(icon, contentDescription = null, tint = WaGreen) },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null,
                tint = WaSub, modifier = Modifier.size(18.dp))
        },
        colors = ListItemDefaults.colors(containerColor = WaBg),
        modifier = Modifier.clickable { open = true }
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column {
                    options.forEach { (value, optionLabel) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    scope.launch { runCatching { prefs.setCustom(key, value) } }
                                    open = false
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            RadioButton(selected = current == value, onClick = {
                                scope.launch { runCatching { prefs.setCustom(key, value) } }
                                open = false
                            })
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(optionLabel)
                        }
                    }
                    scopeNote?.let {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun NavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    dot: Boolean = false
) {
    ListItem(
        headlineContent = { Text(title, color = WaText) },
        supportingContent = { Text(subtitle, color = WaSub) },
        leadingContent = { Icon(icon, contentDescription = null, tint = WaGreen) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (dot) {
                    Box(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(WaGreen)
                    )
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null,
                    tint = WaSub, modifier = Modifier.size(18.dp))
            }
        },
        colors = ListItemDefaults.colors(containerColor = WaBg),
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = WaSub,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 2.dp)
    )
}

@Composable
private fun BackendNoteDialog(title: String, body: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } }
    )
}

// ── Settings home (all sections) ──────────────────────────────────────────

@Composable
private fun SettingsHome(
    onProfile: () -> Unit,
    onDevices: () -> Unit,
    onPrivacy: () -> Unit,
    onNotifications: () -> Unit,
    onOpen: (String, String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = rememberPrefs()
    val scope = rememberCoroutineScope()
    val displayName by prefsSessionDisplayName()
    val phone by prefsSessionPhone()
    var note by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showTheme by remember { mutableStateOf(false) }
    var showLists by remember { mutableStateOf(false) }
    val lists by prefs.chatLists.collectAsState(initial = emptyMap())
    val listsSubtitle =
        if (lists.isEmpty()) "Manage people and groups" else "${lists.size} lists"

    fun backend(title: String, what: String) {
        note = title to "$what needs backend support that does not exist yet. " +
            "See OLLACORE-BACKEND-SPEC.txt. Nothing here is faked."
    }

    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        // ── 1. Profile header ──
        ListItem(
            headlineContent = {
                Text(displayName?.ifBlank { null } ?: "Profile",
                    color = WaText, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            },
            supportingContent = { Text(phone ?: "", color = WaSub) },
            leadingContent = {
                BrandAvatar(name = displayName?.ifBlank { null } ?: phone ?: "?", size = 56.dp)
            },
            trailingContent = {
                Row {
                    IconButton(onClick = {
                        note = "QR code" to "QR pairing needs a backend invite/pair endpoint. " +
                            "Device linking today uses the register flow under Linked devices."
                    }) {
                        Icon(Icons.Default.QrCode2, contentDescription = "QR code", tint = WaGreen)
                    }
                    IconButton(onClick = onProfile) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit profile", tint = WaGreen)
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = WaBg),
            modifier = Modifier.clickable(onClick = onProfile)
        )
        HorizontalDivider(color = com.ollacore.app.ui.home.WaDivider, thickness = 0.75.dp)

        // ── 2. Account ──
        SectionLabel("Account")
        NavRow(Icons.Default.Payments, "Payments", "Not available yet",
            onClick = { backend("Payments", "In-chat payments") })
        NavRow(Icons.Default.CardMembership, "Subscriptions", "Explore premium benefits",
            onClick = { backend("Subscriptions", "Subscriptions") }, dot = true)
        NavRow(Icons.Default.Devices, "Linked devices", "Manage where you use Ollacore",
            onClick = onDevices)
        NavRow(Icons.Default.Person, "Account", "Security notifications, change number",
            onClick = onPrivacy)
        NavRow(Icons.Default.Security, "Security notifications", "Show security notices",
            onClick = onPrivacy)
        NavRow(Icons.Default.PhoneAndroid, "Change number", "Move account to a new number",
            onClick = {
                note = "Change number" to "Number change needs a backend migration " +
                    "(re-issue identity + move rooms). Contact support until it lands."
            })

        // ── 3. Privacy ──
        SectionLabel("Privacy")
        NavRow(Icons.Default.Lock, "Privacy", "Checks, app lock, encryption",
            onClick = onPrivacy)
        NavRow(Icons.Default.Block, "Blocked accounts", "Local list until server filter lands",
            onClick = { onOpen("blocked", "Blocked accounts", "") })
        val audience = listOf("everyone" to "Everyone", "contacts" to "My contacts", "nobody" to "Nobody")
        ChoiceRow(Icons.Default.Schedule, "Disappearing messages", SettingsKeys.DISAPPEAR_DEFAULT,
            listOf("0" to "Off", "86400" to "24 hours", "604800" to "7 days", "7776000" to "90 days"),
            "0", scopeNote = "Saved as the default new chats start from. Server auto-delete still needs backend TTL.")
        ChoiceRow(Icons.Default.Visibility, "Last seen & online", SettingsKeys.PRIV_LASTSEEN,
            audience, "everyone", scopeNote = "Stored on this device. Server enforcement needs the privacy store.")
        ChoiceRow(Icons.Default.Photo, "Profile photo visibility", SettingsKeys.PRIV_PHOTO,
            audience, "everyone", scopeNote = "Stored on this device until the privacy store lands.")
        ChoiceRow(Icons.Default.Info, "About visibility", SettingsKeys.PRIV_ABOUT,
            audience, "everyone", scopeNote = "Stored on this device until the privacy store lands.")
        ChoiceRow(Icons.Default.History, "Status privacy", SettingsKeys.PRIV_STATUS,
            audience + ("allow_list" to "Selected contacts"), "everyone",
            scopeNote = "Applies once the Status service lands.")
        ChoiceRow(Icons.Default.DoneAll, "Read receipts", SettingsKeys.PRIV_RECEIPTS,
            listOf("on" to "On", "off" to "Off"), "on",
            scopeNote = "Stored on this device until the privacy store lands.")
        ChoiceRow(Icons.Default.Group, "Groups privacy", SettingsKeys.PRIV_GROUPS,
            audience, "everyone", scopeNote = "Stored on this device until the privacy store lands.")
        ChoiceRow(Icons.Default.Call, "Calls privacy", SettingsKeys.PRIV_CALLS,
            audience, "everyone", scopeNote = "Stored on this device until the privacy store lands.")
        NavRow(Icons.Default.LocationOn, "Live location", "Not available yet",
            onClick = { backend("Live location", "Live location sharing") })

        // ── 3b. Lists (reference parity): real lists from the local store.
        SectionLabel("Lists")
        NavRow(Icons.Default.Groups, "Manage lists", listsSubtitle,
            onClick = { showLists = true })
        if (showLists) {
            AlertDialog(
                onDismissRequest = { showLists = false },
                title = { Text("Lists") },
                text = {
                    Text(
                        if (lists.isEmpty()) {
                            "No lists yet. Create one from any chat: open the chat menu " +
                                "(⋮) > Lists > New list, then tick chats into it."
                        } else {
                            lists.keys.sorted().joinToString("\n") { name ->
                                "• $name (${lists[name]?.size ?: 0})"
                            } + "\n\nAdd or remove chats from any chat menu (⋮) > Lists."
                        }
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showLists = false }) { Text("Got it") }
                }
            )
        }

        // ── 4. Chats ──
        SectionLabel("Chats")
        NavRow(Icons.Default.Backup, "Chat history / backup", "Export info",
            onClick = {
                note = "Chat history / backup" to "History lives on the Ollacore server. " +
                    "Server export does not exist yet (see OLLACORE-BACKEND-SPEC.txt); " +
                    "a local encrypted export is the fallback."
            })
        NavRow(Icons.Default.Chat, "Chat settings", "Enter key, archived, media",
            onClick = onPrivacy)
        PrefSwitchRow(Icons.Default.KeyboardReturn, "Enter key to send",
            "Enter sends, Shift+Enter makes a new line", SettingsKeys.ENTER_SEND, true)
        PrefSwitchRow(Icons.Default.Visibility, "Media visibility",
            "Show new media in this device's gallery apps", SettingsKeys.MEDIA_VISIBILITY, true,
            scopeNote = "Gallery scan integration pending; preference is kept.")
        PrefSwitchRow(Icons.Default.Archive, "Keep chats archived",
            "Opening an archived chat no longer unarchives it", SettingsKeys.KEEP_ARCHIVED, false)

        // ── 5. Appearance ──
        SectionLabel("Appearance")
        NavRow(Icons.Default.Palette, "Appearance", "Chat themes, bubbles, wallpaper, app icon",
            onClick = { onOpen("appearance", "Appearance", "") })
        NavRow(Icons.Default.Palette, "Chat theme", "Green theme, Light / Dark / System",
            onClick = { showTheme = true })
        NavRow(Icons.Default.Apps, "App icon", "Default icon",
            onClick = {
                note = "App icon" to "Alternate icons are not packaged yet, so the " +
                    "default Ollacore icon stays."
            })
        NavRow(Icons.Default.DarkMode, "App theme", "Light / Dark / System",
            onClick = { showTheme = true })

        // ── 6. Broadcasts ──
        SectionLabel("Broadcasts")
        NavRow(Icons.Default.Campaign, "Manage broadcast lists", "Not available yet",
            onClick = { backend("Broadcast lists", "One-to-many broadcast messaging") })
        NavRow(Icons.Default.Send, "Send broadcast", "Not available yet",
            onClick = { backend("Send broadcast", "One-to-many broadcast messaging") })

        // ── 7. Notifications ──
        SectionLabel("Notifications")
        NavRow(Icons.Default.Notifications, "Message notifications", "Categories and alerts",
            onClick = onNotifications)
        NavRow(Icons.Default.Group, "Group notifications", "Categories and alerts",
            onClick = onNotifications)
        NavRow(Icons.Default.Call, "Call notifications", "Ringtone and ringing",
            onClick = onNotifications)
        NavRow(Icons.Default.MusicNote, "Notification tone", "Sound for message alerts",
            onClick = { onOpen("notifications_adv", "Notification sound", "") })
        NavRow(Icons.Default.Vibration, "Vibration", "Buzz pattern for alerts",
            onClick = { onOpen("notifications_adv", "Notification sound", "") })
        PrefSwitchRow(Icons.Default.NotificationImportant, "Popup notifications",
            "Heads-up banners for new messages", SettingsKeys.NOTIF_POPUP, true,
            scopeNote = "Applied to Ollacore channels (system settings override).")

        // ── 8. Storage and data ──
        SectionLabel("Storage and data")
        NavRow(Icons.Default.Storage, "Storage usage", "Cache tools",
            onClick = { onOpen("storage", "Storage usage", "") })
        NavRow(Icons.Default.NetworkCheck, "Network usage", "Not available yet",
            onClick = { backend("Network usage", "Per-chat data accounting") })
        PrefSwitchRow(Icons.Default.Downloading, "Media auto-download",
            "Prefetch media in the background", SettingsKeys.AUTO_DOWNLOAD, true,
            scopeNote = "Tapping a bubble to view always downloads on demand.")
        ChoiceRow(Icons.Default.HighQuality, "Media upload quality", SettingsKeys.UPLOAD_QUALITY,
            listOf("high" to "High", "balanced" to "Balanced", "saver" to "Data saver"),
            "balanced", scopeNote = "Applies to camera captures (JPEG quality).")
        PrefSwitchRow(Icons.Default.DataSaverOn, "Use less data for calls",
            "Lower call bandwidth", SettingsKeys.LESS_DATA_CALLS, false,
            scopeNote = "Kept for the call engine; SFU negotiation unchanged.")

        // ── 9. Parental controls ──
        SectionLabel("Parental controls")
        NavRow(Icons.Default.FamilyRestroom, "Family settings", "Not available yet",
            onClick = { backend("Family settings", "Supervision and family controls") })
        NavRow(Icons.Default.SupervisedUserCircle, "Supervision controls", "Not available yet",
            onClick = { backend("Supervision controls", "Supervision and family controls") })

        // ── 10. Accessibility ──
        SectionLabel("Accessibility")
        NavRow(Icons.Default.Accessibility, "Accessibility options", "Text size, display",
            onClick = { onOpen("accessibility", "Accessibility", "") })

        // ── 11. App language ──
        SectionLabel("App language")
        NavRow(Icons.Default.Language, "Language", "Device language and selection",
            onClick = { onOpen("language", "App language", "") })

        // ── 12. Help and feedback ──
        SectionLabel("Help and feedback")
        NavRow(Icons.Default.Help, "Help Centre", "Docs and troubleshooting",
            onClick = { openDocs(context) })
        NavRow(Icons.Default.SupportAgent, "Contact us", "Report an issue",
            onClick = { onOpen("help", "Contact us", "contact") })
        NavRow(Icons.Default.Policy, "Privacy policy", "In-app summary",
            onClick = { onOpen("help", "Privacy policy", "privacy") })
        NavRow(Icons.Default.Description, "Terms", "In-app summary",
            onClick = { onOpen("help", "Terms", "terms") })

        // ── 13. Invite ──
        SectionLabel("Invite")
        NavRow(Icons.Default.PersonAdd, "Invite a friend", "Share Ollacore",
            onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "Connect. Chat. Share. Try Ollacore with me!")
                }
                context.startActivity(Intent.createChooser(send, "Invite a friend"))
            })

        // ── 14. App updates ──
        SectionLabel("App updates")
        NavRow(Icons.Default.SystemUpdate, "Current version", appVersionName(context),
            onClick = {
                note = "App updates" to "You are on ${appVersionName(context)} (debug build). " +
                    "Updates arrive as new APK installs; there is no in-app updater yet."
            })

        // ── 15. Accounts Centre (honest: no integrations exist) ──
        SectionLabel("Accounts Centre")
        NavRow(Icons.Default.SwitchAccount, "Accounts Centre", "No connected services",
            onClick = {
                note = "Accounts Centre" to "No connected services exist in this project " +
                    "(no Instagram, Facebook, Threads or Meta AI integrations). " +
                    "Connected experiences will appear here if they are ever added."
            })
        Spacer(modifier = Modifier.height(24.dp))
    }

    note?.let { (title, body) ->
        BackendNoteDialog(title = title, body = body, onDismiss = { note = null })
    }
    if (showTheme) {
        com.ollacore.app.ui.settings.ThemePickerDialogHost(onDismiss = { showTheme = false })
    }

    // Apply popup/vibration prefs to channels when changed here.
    val popup by prefs.customBoolFlow(SettingsKeys.NOTIF_POPUP, true).collectAsState(initial = true)
    LaunchedEffect(popup) { applyPopupImportance(context, popup) }
}

@Composable
private fun prefsSessionDisplayName(): State<String?> {
    val context = LocalContext.current
    val store = remember(context) {
        com.ollacore.app.data.local.SessionStore(context.applicationContext)
    }
    return store.displayName.collectAsState(initial = null)
}

@Composable
private fun prefsSessionPhone(): State<String?> {
    val context = LocalContext.current
    val store = remember(context) {
        com.ollacore.app.data.local.SessionStore(context.applicationContext)
    }
    return store.displayName.collectAsState(initial = null).let {
        store.phone.collectAsState(initial = null)
    }
}

private fun openDocs(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.ollacore.com/docs"))
        )
    }
}

private fun appVersionName(context: Context): String {
    return runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "Ollacore Android ${info.versionName ?: "1.0"}"
    }.getOrElse { "Ollacore Android" }
}

/** Heads-up on/off: mapped onto Ollacore channel importance (system may override). */
private fun applyPopupImportance(context: Context, headsUp: Boolean) {
    runCatching {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val importance = if (headsUp) NotificationManager.IMPORTANCE_HIGH
        else NotificationManager.IMPORTANCE_DEFAULT
        listOf(
            ContentFreePushManager.CHANNEL_MESSAGES,
            ContentFreePushManager.CHANNEL_MESSAGES_E2EE,
            ContentFreePushManager.CHANNEL_CALLS,
            ContentFreePushManager.CHANNEL_CALLS_E2EE
        ).forEach { id ->
            nm.getNotificationChannel(id)?.let { ch ->
                val updated = android.app.NotificationChannel(ch.id, ch.name, importance).apply {
                    description = ch.description
                    enableVibration(ch.shouldVibrate())
                    setSound(ch.sound, ch.audioAttributes)
                }
                nm.createNotificationChannel(updated)
            }
        }
    }
}

// ── Sub-screens ───────────────────────────────────────────────────────────

@Composable
private fun BlockedScreen(modifier: Modifier = Modifier) {
    val prefs = rememberPrefs()
    val scope = rememberCoroutineScope()
    val blocked by prefs.blockedUsers.collectAsState(initial = emptySet())
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            "Blocked contacts can't message you. Their messages stop appearing. " +
                "Unblock anytime here. (Server-side filtering needs the block-list backend.)",
            color = WaSub,
            fontSize = 13.sp,
            modifier = Modifier.padding(20.dp)
        )
        if (blocked.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No blocked accounts", color = WaSub)
            }
        } else {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                blocked.sorted().forEach { uid ->
                    ListItem(
                        headlineContent = { Text(shortId(uid), color = WaText) },
                        supportingContent = { Text("Tap Unblock to allow messages again", color = WaSub) },
                        leadingContent = {
                            com.ollacore.app.ui.theme.BrandAvatar(name = uid, size = 44.dp)
                        },
                        trailingContent = {
                            TextButton(onClick = {
                                scope.launch { runCatching { prefs.setUserBlocked(uid, false) } }
                            }) { Text("Unblock", color = WaGreen) }
                        },
                        colors = ListItemDefaults.colors(containerColor = WaBg)
                    )
                }
            }
        }
    }
}

private fun shortId(uid: String): String =
    if (uid.length > 12) uid.take(8) + "…" else uid.ifBlank { "Unknown" }

@Composable
private fun StorageScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var cleared by remember { mutableStateOf(false) }
    var cacheSize by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(cleared) {
        cacheSize = runCatching {
            fun dirSize(f: java.io.File): Long =
                if (!f.exists()) 0L else if (f.isFile) f.length()
                else f.listFiles()?.sumOf { dirSize(it) } ?: 0L
            dirSize(context.cacheDir)
        }.getOrNull()
    }
    fun fmt(b: Long): String = when {
        b < 1024 -> "$b B"
        b / 1024.0 < 1024 -> String.format("%.1f KB", b / 1024.0)
        else -> String.format("%.1f MB", b / 1024.0 / 1024.0)
    }
    Column(modifier = modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(20.dp)) {
        Text("Cache", color = WaText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            cacheSize?.let { "App cache is using ${fmt(it)}." } ?: "Measuring cache…",
            color = WaSub
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = {
            runCatching { context.cacheDir.deleteRecursively() }
            runCatching { context.cacheDir.mkdirs() }
            cleared = !cleared
        }) { Text("Clear cache") }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "History lives on the Ollacore server; clearing cache only removes " +
                "temporary files and image previews on this device.",
            color = WaSub, fontSize = 13.sp
        )
    }
}

@Composable
private fun LanguageScreen(modifier: Modifier = Modifier) {
    val prefs = rememberPrefs()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val current by prefs.customFlow(SettingsKeys.APP_LANG, "").collectAsState(initial = "")
    val options = listOf(
        "" to "Device language", "en" to "English", "hi" to "Hindi (हिन्दी)",
        "es" to "Spanish (Español)", "fr" to "French (Français)",
        "de" to "German (Deutsch)", "ar" to "Arabic (العربية)"
    )
    Column(modifier = modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())) {
        Text(
            "Applies immediately and persists across restarts.",
            color = WaSub, fontSize = 13.sp,
            modifier = Modifier.padding(20.dp)
        )
        options.forEach { (value, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        scope.launch {
                            runCatching { prefs.setCustom(SettingsKeys.APP_LANG, value) }
                            applyLocale(context, value)
                        }
                    }
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                RadioButton(selected = current == value, onClick = {
                    scope.launch {
                        runCatching { prefs.setCustom(SettingsKeys.APP_LANG, value) }
                        applyLocale(context, value)
                    }
                })
                Spacer(modifier = Modifier.width(10.dp))
                Text(label, color = WaText)
            }
        }
    }
}

private fun applyLocale(context: Context, tag: String) {
    runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(android.app.LocaleManager::class.java)
                ?.applicationLocales = LocaleList.forLanguageTags(tag.ifBlank { "" })
            return
        }
        @Suppress("DEPRECATION")
        val locale = if (tag.isBlank()) Locale.getDefault() else Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        @Suppress("DEPRECATION")
        context.resources.configuration.setLocale(locale)
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(
            context.resources.configuration, context.resources.displayMetrics
        )
        (context as? Activity)?.recreate()
    }
}

@Composable
private fun AccessibilityScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier = modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())) {
        Text("Text size", color = WaText, fontWeight = FontWeight.Bold, fontSize = 16.sp,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp))
        Text("Applies across the whole app immediately.",
            color = WaSub, fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp))
        Spacer(modifier = Modifier.height(4.dp))
        val prefs = rememberPrefs()
        val scope = rememberCoroutineScope()
        val current by prefs.customFlow(SettingsKeys.TEXT_SCALE, "1.0").collectAsState(initial = "1.0")
        listOf("0.85" to "Small", "1.0" to "Default", "1.15" to "Large", "1.3" to "Extra large").forEach { (v, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        scope.launch { runCatching { prefs.setCustom(SettingsKeys.TEXT_SCALE, v) } }
                    }
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                RadioButton(selected = current == v, onClick = {
                    scope.launch { runCatching { prefs.setCustom(SettingsKeys.TEXT_SCALE, v) } }
                })
                Spacer(modifier = Modifier.width(10.dp))
                Text(label, color = WaText)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        NavRow(Icons.Default.Contrast, "Increase contrast", "System display settings",
            onClick = {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS))
                }
            })
        NavRow(Icons.Default.Animation, "Animation settings", "System accessibility settings",
            onClick = {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            })
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun NotificationSoundScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = rememberPrefs()
    val scope = rememberCoroutineScope()
    val tone by prefs.customFlow(SettingsKeys.NOTIF_TONE, "").collectAsState(initial = "")
    val vib by prefs.customFlow(SettingsKeys.NOTIF_VIB, "default").collectAsState(initial = "default")
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val uri: Uri? = res.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        scope.launch {
            runCatching { prefs.setCustom(SettingsKeys.NOTIF_TONE, uri?.toString() ?: "") }
            applyToneToChannels(context, uri)
        }
    }
    Column(modifier = modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(20.dp)) {
        Text("Message tone", color = WaText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(tone.ifBlank { "System default" }, color = WaSub, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Message tone")
                if (tone.isNotBlank()) putExtra(
                    RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(tone)
                )
            }
            picker.launch(intent)
        }) { Text("Choose tone") }
        Spacer(modifier = Modifier.height(20.dp))
        Text("Vibration", color = WaText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        listOf(
            "default" to "Default", "off" to "Off",
            "short" to "Short", "long" to "Long"
        ).forEach { (v, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        scope.launch {
                            runCatching { prefs.setCustom(SettingsKeys.NOTIF_VIB, v) }
                            applyVibrationToChannels(context, v)
                        }
                    }
                    .padding(vertical = 8.dp)
            ) {
                RadioButton(selected = vib == v, onClick = {
                    scope.launch {
                        runCatching { prefs.setCustom(SettingsKeys.NOTIF_VIB, v) }
                        applyVibrationToChannels(context, v)
                    }
                })
                Spacer(modifier = Modifier.width(8.dp))
                Text(label, color = WaText)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Applied to Ollacore message and call channels. Choices you already made " +
                "in system Settings take precedence.",
            color = WaSub, fontSize = 13.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                })
            }
        }) { Text("Open system notification settings", color = WaGreen) }
    }
}

private fun channelIds() = listOf(
    ContentFreePushManager.CHANNEL_MESSAGES,
    ContentFreePushManager.CHANNEL_MESSAGES_E2EE,
    ContentFreePushManager.CHANNEL_CALLS,
    ContentFreePushManager.CHANNEL_CALLS_E2EE
)

private fun applyToneToChannels(context: Context, uri: Uri?) {
    runCatching {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        channelIds().forEach { id ->
            nm.getNotificationChannel(id)?.let { ch ->
                val updated = android.app.NotificationChannel(ch.id, ch.name, ch.importance).apply {
                    description = ch.description
                    enableVibration(ch.shouldVibrate())
                    vibrationPattern = ch.vibrationPattern
                    setSound(
                        uri,
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                            .build()
                    )
                }
                nm.createNotificationChannel(updated)
            }
        }
    }
}

private fun applyVibrationToChannels(context: Context, mode: String) {
    runCatching {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        channelIds().forEach { id ->
            nm.getNotificationChannel(id)?.let { ch ->
                val updated = android.app.NotificationChannel(ch.id, ch.name, ch.importance).apply {
                    description = ch.description
                    setSound(ch.sound, ch.audioAttributes)
                    when (mode) {
                        "off" -> enableVibration(false)
                        "short" -> {
                            enableVibration(true)
                            vibrationPattern = longArrayOf(0, 200)
                        }
                        "long" -> {
                            enableVibration(true)
                            vibrationPattern = longArrayOf(0, 500, 200, 500)
                        }
                        else -> {
                            enableVibration(true)
                            vibrationPattern = ch.vibrationPattern
                        }
                    }
                }
                nm.createNotificationChannel(updated)
            }
        }
    }
}

@Composable
private fun HelpScreen(topic: String, title: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val body = when (topic) {
        "contact" -> "Report an issue at github.com/anomalyco/opencode " +
            "(mention Meta Muse Spark). Include your app version, device model, " +
            "and steps to reproduce. There is no in-app ticket system yet."
        "privacy" -> "Summary: Ollacore encrypts message content end-to-end (MLS). " +
            "Push previews are content-free. This is an in-app summary, not legal text."
        "terms" -> "Summary: use Ollacore lawfully; media you send must be yours to " +
            "share. This is an in-app summary, not legal text."
        else -> "Help topics live in the docs."
    }
    Column(modifier = modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(20.dp)) {
        Text(title, color = WaText, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(body, color = WaSub)
        if (topic == "contact") {
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.ollacore.com/docs"))
                    )
                }
            }) { Text("Open docs") }
        }
    }
}
