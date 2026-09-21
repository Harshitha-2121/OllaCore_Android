package com.ollacore.app.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ollacore.app.ui.theme.GlassCard
import com.ollacore.app.ui.theme.GradientButton
import com.ollacore.app.ui.theme.HeroWash
import com.ollacore.app.ui.theme.OLLACORE_TAGLINE
import com.ollacore.app.ui.theme.OllacoreLogo

private data class Country(val name: String, val code: String)

private val Countries = listOf(
    Country("India", "+91"),
    Country("United States", "+1"),
    Country("United Kingdom", "+44"),
    Country("United Arab Emirates", "+971"),
    Country("Saudi Arabia", "+966"),
    Country("Singapore", "+65"),
    Country("Australia", "+61"),
    Country("Canada", "+1"),
    Country("Germany", "+49"),
    Country("France", "+33"),
    Country("Brazil", "+55"),
    Country("South Africa", "+27"),
    Country("Philippines", "+63"),
    Country("Nigeria", "+234"),
    Country("Pakistan", "+92")
)

/**
 * Login (spec section 7): logo header, "Welcome to Ollacore",
 * country selector + phone input, Continue CTA, QR path, terms footer.
 * Same AuthViewModel contract - presentation only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneInputScreen(
    phone: String,
    onPhoneChange: (String) -> Unit,
    onSendOtp: () -> Unit,
    isLoading: Boolean,
    error: String?,
    onErrorDismiss: () -> Unit
) {
    var countryExpanded by remember { mutableStateOf(false) }
    var country by rememberSaveable { mutableStateOf(Countries[0]) }
    var national by rememberSaveable { mutableStateOf("") }
    var showQrInfo by remember { mutableStateOf(false) }

    // Keep the ViewModel's full international number in sync with the split fields.
    LaunchedEffect(country, national) {
        val full = "${country.code} ${national.trim()}".trim()
        if (full != phone) onPhoneChange(full)
    }

    Box(modifier = Modifier.fillMaxSize().background(HeroWash)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Header: logo + title + subtitle.
            OllacoreLogo(size = 88.dp)
            Spacer(Modifier.height(20.dp))
            Text(
                "Welcome to Ollacore",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "Sign in to continue",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                OLLACORE_TAGLINE,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(28.dp))

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    // Country selector.
                    ExposedDropdownMenuBox(
                        expanded = countryExpanded,
                        onExpandedChange = { countryExpanded = !countryExpanded }
                    ) {
                        OutlinedTextField(
                            value = "${country.name} (${country.code})",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Country") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = countryExpanded) },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = countryExpanded,
                            onDismissRequest = { countryExpanded = false }
                        ) {
                            Countries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text("${option.name} (${option.code})") },
                                    onClick = {
                                        country = option
                                        countryExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    // Phone number.
                    OutlinedTextField(
                        value = national,
                        onValueChange = { national = it.filter { ch -> ch.isDigit() || ch == ' ' || ch == '-' } },
                        label = { Text("Phone number") },
                        placeholder = { Text("98765 43210") },
                        prefix = { Text("${country.code}  ") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    GradientButton(
                        text = if (isLoading) "Sending code…" else "Continue",
                        onClick = onSendOtp,
                        enabled = !isLoading && national.filter(Char::isDigit).length >= 7,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (error != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onErrorDismiss) { Text("Dismiss") }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { showQrInfo = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp)
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Scan QR Code")
            }

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "Protected with end-to-end encryption",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "By tapping Continue, you agree to our Terms of Service and Privacy Policy.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }

    if (showQrInfo) {
        AlertDialog(
            onDismissRequest = { showQrInfo = false },
            title = { Text("Scan QR Code") },
            text = {
                Text("QR sign-in needs a companion-pairing endpoint on the Ollacore backend, which does not exist yet. Phone + OTP sign-in works today.")
            },
            confirmButton = {
                TextButton(onClick = { showQrInfo = false }) { Text("Got it") }
            }
        )
    }
}
