package com.ollacore.app.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Spec 28: message / group / call / mention / security categories.
 * Groups follow the Messages toggle until pushes carry a room kind
 * (backend); mentions-only is a best-effort "@" match on the payload.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsSettingsScreen(
    uiState: NotifPrefsUiState,
    onSet: (String, Boolean) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notifications") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            NotifToggle(
                title = "Message notifications",
                subtitle = "1-to-1 and group chats (muted chats stay silent)",
                checked = uiState.messages,
                onChecked = { onSet("messages", it) }
            )
            NotifToggle(
                title = "Call notifications",
                subtitle = "Incoming voice and video calls",
                checked = uiState.calls,
                onChecked = { onSet("calls", it) }
            )
            NotifToggle(
                title = "Mentions only",
                subtitle = "Only notify messages containing @ (best-effort)",
                checked = uiState.mentionsOnly,
                onChecked = { onSet("mentions_only", it) }
            )
            NotifToggle(
                title = "Security notifications",
                subtitle = "Encryption and safety alerts when available",
                checked = uiState.security,
                onChecked = { onSet("security", it) }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Toggles apply on this device immediately. System-level sound, vibration and lock-screen visibility live in Android settings.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
            FilledTonalButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            }
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Text("System notification settings")
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun NotifToggle(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChecked) }
    )
    HorizontalDivider()
}
