package com.ollacore.app.ui.payments

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Clean integration point for in-chat payments (reference overflow
 * "Payments"). There is no Ollacore payments service yet, so this screen
 * exposes exactly what a provider must implement ([PaymentsService]) and
 * performs NO transaction of any kind - nothing is faked.
 */
interface PaymentsService {
    /** Human-readable provider name shown once a backend is wired. */
    val providerName: String

    /** Whether the current session could pay (account linked, region ok). */
    suspend fun isAvailable(): Result<Boolean>

    /**
     * Starts a payment. The default backend has no implementation: providers
     * override this. NEVER report success without provider confirmation.
     */
    suspend fun initiatePayment(roomId: String, amountMinor: Long, currency: String): Result<String>
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Payments") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        modifier = modifier
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Payments,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("Payments aren't available yet", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "In-chat payments need the Ollacore payments service. " +
                        "The integration point (PaymentsService) is ready - " +
                        "wiring a provider lights up this screen with no UI changes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
