@file:OptIn(ExperimentalMaterial3Api::class)

package com.ollacore.app.ui.settings

import androidx.compose.material3.ExperimentalMaterial3Api

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
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
import com.ollacore.app.ui.home.WaBg
import com.ollacore.app.ui.home.WaGreen
import com.ollacore.app.ui.home.WaSub
import com.ollacore.app.ui.home.WaText
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.semantics.Role
import com.ollacore.app.ui.common.IconSize
import java.util.Locale

/** Existing Settings destination, with saveable category pages and app-owned navigation callbacks. */
@Composable
fun SettingsRoot(
    onProfile: () -> Unit,
    onDevices: () -> Unit,
    onPrivacy: () -> Unit,
    onNotifications: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    displayName: String? = null,
    about: String? = null,
    avatarUrl: String? = null,
    profileLoading: Boolean = false,
    profileError: String? = null
) {
    var pages by rememberSaveable { mutableStateOf(listOf("root")) }
    val screen = pages.last()
    val screenTitle = when (screen) {
        "account" -> "Account"
        "chats" -> "Chats"
        "storage" -> "Storage and data"
        "help" -> "Help and support"
        "language" -> "App language"
        "accessibility" -> "Accessibility"
        "blocked" -> "Blocked accounts"
        else -> "Settings"
    }
    fun open(id: String) {
        if (pages.last() != id) pages = pages + id
    }
    fun back() {
        if (pages.size > 1) pages = pages.dropLast(1) else onBack()
    }
    BackHandler(enabled = pages.size > 1) { back() }
    val stateHolder = rememberSaveableStateHolder()
    // Appearance owns its toolbar and insets; don't wrap it in a second app bar.
    if (screen == "appearance") {
        stateHolder.SaveableStateProvider(screen) {
            com.ollacore.app.ui.appearance.AppearanceFlow(onBack = ::back, modifier = modifier)
        }
        return
    }

    Scaffold(
        containerColor = WaBg,
        topBar = {
            TopAppBar(
                title = { Text(if (screen == "root") "Settings" else screenTitle, color = WaText) },
                navigationIcon = {
                    IconButton(onClick = ::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = WaText)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = WaBg)
            )
        },
        modifier = modifier
    ) { padding ->
        stateHolder.SaveableStateProvider(screen) {
        when (screen) {
            "account" -> AccountSettings(onProfile, onDevices, onPrivacy, ::open, Modifier.padding(padding).consumeWindowInsets(padding))
            "chats" -> ChatSettings(::open, Modifier.padding(padding).consumeWindowInsets(padding))
            "blocked" -> BlockedScreen(modifier = Modifier.padding(padding).consumeWindowInsets(padding))
            "storage" -> StorageScreen(modifier = Modifier.padding(padding).consumeWindowInsets(padding))
            "language" -> LanguageScreen(modifier = Modifier.padding(padding).consumeWindowInsets(padding))
            "accessibility" -> AccessibilityScreen(modifier = Modifier.padding(padding).consumeWindowInsets(padding))
            "help" -> HelpSettings(Modifier.padding(padding).consumeWindowInsets(padding))
            else -> SettingsHome(
                onProfile = onProfile,
                onPrivacy = onPrivacy,
                onNotifications = onNotifications,
                onOpen = ::open,
                modifier = Modifier.padding(padding).consumeWindowInsets(padding),
                displayName = displayName,
                about = about,
                avatarUrl = avatarUrl,
                profileLoading = profileLoading,
                profileError = profileError
            )
        }
        }
    }
}

// ── Prefs helpers ─────────────────────────────────────────────────────────

@Composable
internal fun rememberPrefs(): ChatPrefsStore {
    val context = LocalContext.current
    return remember(context) {
        (context.applicationContext as com.ollacore.app.OllacoreApp).container.chatPrefsStore
    }
}

@Composable
internal fun PrefSwitchRow(
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
    var saveFailed by rememberSaveable { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title, color = WaText) },
        supportingContent = {
            Column {
                Text(subtitle, color = WaSub)
                scopeNote?.let { Text(it, color = WaSub, fontSize = 12.sp) }
            }
        },
        leadingContent = { Icon(icon, contentDescription = null, tint = WaGreen) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = WaBg),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = { value ->
                scope.launch {
                    saveFailed = runCatching { prefs.setCustomBool(key, value) }.isFailure
                }
            })
    )
    if (saveFailed) {
        AlertDialog(onDismissRequest = { saveFailed = false },
            title = { Text("Unable to save preference") },
            text = { Text("The change was not saved. Try again later.") },
            confirmButton = { TextButton(onClick = { saveFailed = false }) { Text("Close") } })
    }
}

@Composable
internal fun ChoiceRow(
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
    var open by rememberSaveable { mutableStateOf(false) }
    var saveFailed by rememberSaveable { mutableStateOf(false) }
    fun save(value: String) {
        scope.launch {
            saveFailed = runCatching { prefs.setCustom(key, value) }.isFailure
            if (!saveFailed) open = false
        }
    }
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
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (saveFailed) Text("Unable to save. Try again later.", color = MaterialTheme.colorScheme.error)
                    options.forEach { (value, optionLabel) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { save(value) }
                                .padding(vertical = 8.dp)
                        ) {
                            RadioButton(selected = current == value, onClick = { save(value) })
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
internal fun NavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    dot: Boolean = false
) {
    ListItem(
        headlineContent = { Text(title, color = WaText, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = { Text(subtitle, color = WaSub, style = MaterialTheme.typography.bodyMedium) },
        leadingContent = { Icon(icon, contentDescription = null, tint = WaGreen, modifier = Modifier.size(IconSize.M)) },
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
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .clickable(role = Role.Button, onClick = onClick)
    )
}

// ── Sub-screens ───────────────────────────────────────────────────────────

@Composable
private fun BlockedScreen(modifier: Modifier = Modifier) {
    val prefs = rememberPrefs()
    val scope = rememberCoroutineScope()
    val blocked by prefs.blockedUsers.collectAsState(initial = emptySet())
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            "Messages from blocked accounts are filtered on this device. " +
                "This does not stop delivery on the server or other devices. Unblock anytime here.",
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
    val scope = rememberCoroutineScope()
    var cacheSize by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(true) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(context) {
        val result = withContext(Dispatchers.IO) { runCatching { settingsCacheSize(context.cacheDir) } }
        cacheSize = result.getOrNull()
        if (result.isFailure) status = "Unable to measure cache. Try again later."
        busy = false
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
            cacheSize?.let { "App cache is using ${fmt(it)}." }
                ?: if (busy) "Measuring cache…" else "Cache size unavailable.",
            color = WaSub
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        clearSettingsCache(context.cacheDir)
                        settingsCacheSize(context.cacheDir)
                    }
                }
                cacheSize = result.getOrNull()
                status = if (result.isSuccess) "Temporary cache cleared. Files in use may be recreated."
                    else "Some cache files could not be cleared. Try again later."
                busy = false
            }
        }) { Text(if (busy) "Please wait…" else "Clear cache") }
        status?.let { Text(it, color = WaSub, style = MaterialTheme.typography.bodyMedium) }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Removes temporary files and image previews, not your account or message database. Avoid clearing during uploads or downloads.",
            color = WaSub, style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(16.dp))
        PrefSwitchRow(Icons.Default.Download, "Media auto-download",
            "Automatically download supported incoming media on this device",
            SettingsKeys.AUTO_DOWNLOAD, true)
        ChoiceRow(Icons.Default.HighQuality, "Photo upload quality", SettingsKeys.UPLOAD_QUALITY,
            listOf("high" to "High", "balanced" to "Balanced", "saver" to "Data saver"), "balanced",
            "Applies to photos prepared by the chat camera.")
        Spacer(Modifier.height(16.dp))
        Text("Network usage totals and per-chat storage management are not available in this version.",
            color = WaSub, style = MaterialTheme.typography.bodyMedium)
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
            "Language preference is saved on this device. Untranslated text remains in English.",
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
