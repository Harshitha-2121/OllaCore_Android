package com.ollacore.app.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Spec 24: profile card + Account / Privacy / Notifications / Chats /
 * Storage / Linked devices / Help / About rows with chevrons.
 * Privacy toggles wait on the backend store (dedicated screen explains);
 * theme + cache + devices are real client-side controls.
 */
@Composable
fun SettingsContent(
    displayName: String?,
    phone: String?,
    onProfile: () -> Unit,
    onDevices: () -> Unit,
    onPrivacy: () -> Unit = {},
    onNotifications: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var cacheSize by remember { mutableStateOf<Long?>(null) }
    var cacheCleared by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }

    LaunchedEffect(cacheCleared) {
        cacheSize = runCatching { dirSize(context.cacheDir) }.getOrNull()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Profile header
        ListItem(
            headlineContent = { Text(displayName?.ifBlank { null } ?: "Profile", style = MaterialTheme.typography.titleMedium) },
            supportingContent = { Text(phone ?: "") },
            leadingContent = {
                com.ollacore.app.ui.theme.BrandAvatar(
                    name = displayName?.ifBlank { null } ?: phone ?: "?",
                    size = 48.dp
                )
            },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
            modifier = Modifier.clickable(onClick = onProfile)
        )
        HorizontalDivider()

        SettingsRow(
            icon = Icons.Default.Person,
            title = "Account",
            subtitle = "Profile, name, about",
            onClick = onProfile
        )
        SettingsRow(
            icon = Icons.Default.Lock,
            title = "Privacy & Security",
            subtitle = "App lock, encryption info",
            onClick = onPrivacy
        )
        SettingsRow(
            icon = Icons.Default.Notifications,
            title = "Notifications",
            subtitle = "Message, call and security alerts",
            onClick = onNotifications
        )
        SettingsRow(
            icon = Icons.Default.Palette,
            title = "Chats",
            subtitle = "App theme",
            onClick = { showTheme = true }
        )
        SettingsRow(
            icon = Icons.Default.Devices,
            title = "Linked devices",
            subtitle = "Manage where you use Ollacore",
            onClick = onDevices
        )
        SettingsRow(
            icon = Icons.Default.Folder,
            title = "Storage",
            subtitle = cacheSize?.let { "Cache: ${formatBytes(it)}" } ?: "Cache size unknown",
            onClick = { }
        )
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            FilledTonalButton(
                onClick = {
                    runCatching { context.cacheDir.deleteRecursively() }
                    runCatching { context.cacheDir.mkdirs() }
                    cacheCleared = !cacheCleared
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Clear cache")
            }
        }
        SettingsRow(
            icon = Icons.Default.Help,
            title = "Help",
            subtitle = "Support and feedback",
            onClick = { showHelp = true }
        )
        SettingsRow(
            icon = Icons.Default.Info,
            title = "About",
            subtitle = "Ollacore Android ${appVersion(context)} • WhatsApp-style, Ollacore-native",
            onClick = { showHelp = true }
        )
    }

    if (showTheme) {
        ThemePickerDialog(onDismiss = { showTheme = false })
    }
    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("Help & About") },
            text = {
                Text("Ollacore Android ${appVersion(context)}.\nReport issues at github.com/anomalyco/opencode (Meta Muse Spark).")
            },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("Close") } }
        )
    }
}

@Composable
internal fun ThemePickerDialogHost(onDismiss: () -> Unit) {
    ThemePickerDialog(onDismiss = onDismiss)
}

@Composable
private fun ThemePickerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { com.ollacore.app.data.local.ThemeStore(context.applicationContext) }
    val current by store.mode.collectAsState(initial = com.ollacore.app.data.local.ThemeMode.BLUE)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("App theme") },
        text = {
            Column {
                com.ollacore.app.data.local.ThemeMode.entries.forEach { mode ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch { runCatching { store.setMode(mode) } }
                                onDismiss()
                            }
                            .padding(vertical = 6.dp)
                    ) {
                        RadioButton(selected = current == mode, onClick = {
                            scope.launch { runCatching { store.setMode(mode) } }
                            onDismiss()
                        })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            when (mode) {
                                com.ollacore.app.data.local.ThemeMode.BLUE -> "Ollacore Blue"
                                com.ollacore.app.data.local.ThemeMode.GREEN -> "Ollacore Green"
                                com.ollacore.app.data.local.ThemeMode.PURPLE -> "Purple"
                                com.ollacore.app.data.local.ThemeMode.LIGHT -> "Light"
                                com.ollacore.app.data.local.ThemeMode.DARK -> "Dark"
                                com.ollacore.app.data.local.ThemeMode.SYSTEM -> "System Default"
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp)) },
        modifier = Modifier.clickable(onClick = onClick)
    )
    HorizontalDivider()
}

private fun dirSize(dir: java.io.File): Long {
    if (!dir.exists()) return 0L
    if (dir.isFile) return dir.length()
    return dir.listFiles()?.sumOf { dirSize(it) } ?: 0L
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    return String.format("%.1f MB", kb / 1024.0)
}

private fun appVersion(context: Context): String {
    return runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName ?: "1.0"
    }.getOrElse { "1.0" }
}
