package com.ollacore.app.ui.privacy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ollacore.app.ui.theme.OllacoreLogo

/**
 * Spec 25: dedicated Privacy & Security. Toggles are real (app lock gate,
 * security notifications store); server-owned rows are marked as needing
 * the backend privacy store instead of pretending to work.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacySecurityScreen(
    uiState: PrivacySecurityUiState,
    onSetAppLock: (Boolean) -> Unit,
    onSetSecurityNotifications: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy & Security") },
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
            // E2EE information (real, local).
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.VerifiedUser,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("End-to-end encrypted", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Messages use MLS encryption with forward secrecy - removed members lose future access.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            ListItem(
                headlineContent = { Text("App lock") },
                supportingContent = {
                    Text(
                        if (uiState.deviceSecure) "Require device PIN / biometrics on launch"
                        else "Set a device screen lock first"
                    )
                },
                leadingContent = { Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                trailingContent = {
                    Switch(
                        checked = uiState.appLock,
                        enabled = uiState.deviceSecure,
                        onCheckedChange = onSetAppLock
                    )
                }
            )
            HorizontalDivider()

            ListItem(
                headlineContent = { Text("Security notifications") },
                supportingContent = { Text("Encryption and safety alerts when available") },
                leadingContent = { Icon(Icons.Default.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                trailingContent = {
                    Switch(checked = uiState.securityNotifications, onCheckedChange = onSetSecurityNotifications)
                }
            )
            HorizontalDivider()

            Text(
                "Needs backend privacy store",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            listOf(
                "Last seen & online" to "Who sees your activity",
                "Profile photo" to "Who sees your photo",
                "About" to "Who sees your about text",
                "Read receipts" to "Send blue ticks",
                "Blocked contacts" to "Manage blocked users",
                "Disappearing messages" to "Needs server TTL to sync"
            ).forEach { (title, sub) ->
                ListItem(
                    headlineContent = { Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    supportingContent = { Text("$sub - backend pending", style = MaterialTheme.typography.labelSmall) },
                    trailingContent = {
                        Switch(checked = false, enabled = false, onCheckedChange = {})
                    }
                )
                HorizontalDivider()
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** Full-screen app-lock gate (spec 25): device credential check before content. */
@Composable
fun AppLockGate(onUnlock: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            OllacoreLogo(size = 88.dp)
            Spacer(modifier = Modifier.height(20.dp))
            Text("Ollacore is locked", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Confirm your device PIN or biometrics to continue.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onUnlock) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Unlock")
            }
        }
    }
}
