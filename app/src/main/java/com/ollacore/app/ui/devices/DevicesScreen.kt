package com.ollacore.app.ui.devices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ollacore.app.data.model.DeviceResponse

/**
 * Settings -> Linked Devices: this device + linked list + log-out per device.
 * WhatsApp-style UX over Ollacore's own device registry (no proprietary copy).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    uiState: DevicesUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRegisterThis: () -> Unit,
    onLogoutDevice: (String) -> Unit,
    onClearTransient: () -> Unit
) {
    var confirmLogout by remember { mutableStateOf<DeviceResponse?>(null) }

    val thisDevice = uiState.thisPushToken?.let { self ->
        uiState.devices.find { it.pushToken == self }
    }
    val others = uiState.devices.filter { it.pushToken != uiState.thisPushToken }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Linked devices") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading && uiState.devices.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Spec 26 illustration: phone + laptop + tablet (original Compose art).
            item {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 4.dp)
                ) {
                    DeviceArtBadge(
                        icon = Icons.Default.Smartphone,
                        size = 72.dp,
                        container = 104.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    DeviceArtBadge(
                        icon = Icons.Default.Computer,
                        size = 64.dp,
                        container = 128.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    DeviceArtBadge(
                        icon = Icons.Default.Tablet,
                        size = 56.dp,
                        container = 96.dp
                    )
                }
            }
            // This device
            item {
                Text("This device", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                if (thisDevice != null) {
                    DeviceRow(device = thisDevice, isSelf = true, onLogout = { confirmLogout = thisDevice })
                } else {
                    ListItem(
                        headlineContent = { Text("This phone is not registered for push") },
                        supportingContent = { Text("Register to receive calls and messages in background") },
                        trailingContent = {
                            FilledTonalButton(onClick = onRegisterThis, enabled = !uiState.isWorking) {
                                Text("Link this device")
                            }
                        }
                    )
                }
                HorizontalDivider()
            }

            // Linked devices
            item {
                Text(
                    "Linked devices (${others.size})",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            if (others.isEmpty()) {
                item {
                    Text(
                        "Connect Ollacore on another device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
            items(others, key = { it.pushToken }) { device ->
                DeviceRow(device = device, isSelf = false, onLogout = { confirmLogout = device })
                HorizontalDivider()
            }

            // Link explainer + transient states
            item {
                Spacer(modifier = Modifier.height(12.dp))
                if (uiState.isWorking) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                uiState.error?.let { err ->
                    Surface(modifier = Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.errorContainer) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(err, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = onClearTransient) { Text("Dismiss") }
                        }
                    }
                }
                uiState.note?.let { note ->
                    Surface(modifier = Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(note, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = onClearTransient) { Text("OK") }
                        }
                    }
                }
                Text(
                    "Link a device: install Ollacore on the companion device, log in with the same account, and it registers here. QR pairing is not offered by the Ollacore device API (backend-check if wanted).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }

    confirmLogout?.let { device ->
        AlertDialog(
            onDismissRequest = { confirmLogout = null },
            title = { Text("Log out device?") },
            text = { Text("This device will stop receiving Ollacore calls and messages.") },
            confirmButton = {
                TextButton(onClick = {
                    onLogoutDevice(device.pushToken)
                    confirmLogout = null
                }) { Text("Log out") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun DeviceArtBadge(icon: ImageVector, size: Dp, container: Dp) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
        modifier = Modifier.size(container)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(size)
            )
        }
    }
}

@Composable
private fun DeviceRow(device: DeviceResponse, isSelf: Boolean, onLogout: () -> Unit) {
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    device.platform.replaceFirstChar { it.uppercase() },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isSelf) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer) {
                        Text("this device", modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
        supportingContent = {
            Text(
                "…${device.pushToken.takeLast(10)} • updated ${device.updatedAt.take(10)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingContent = {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        if (device.platform.contains("android", ignoreCase = true) || device.platform.contains("ios", ignoreCase = true)) Icons.Default.Smartphone else Icons.Default.Computer,
                        contentDescription = null
                    )
                }
            }
        },
        trailingContent = {
            IconButton(onClick = onLogout) {
                Icon(Icons.Default.Logout, contentDescription = "Log out device")
            }
        },
        modifier = Modifier.clickable(onClick = onLogout)
    )
}
