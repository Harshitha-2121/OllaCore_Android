package com.ollacore.app.ui.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// Theme-derived form tokens: identical to the light reference in Light mode
// (surface white, slate text, grey hints), automatically dark in Dark mode.
private val NcBackground: Color @Composable get() = MaterialTheme.colorScheme.surface
private val NcOnBackground: Color @Composable get() = MaterialTheme.colorScheme.onSurface
private val NcHint: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val NcBorder: Color @Composable get() = MaterialTheme.colorScheme.outline
private val NcIcon: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val NcDivider: Color @Composable get() = MaterialTheme.colorScheme.outline
private val NcSaveDisabledBg: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
private val NcSaveDisabledText: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val NcFieldShape = RoundedCornerShape(12.dp)

private data class CountryEntry(val iso: String, val dial: String, val name: String)

private val COUNTRIES = listOf(
    CountryEntry("IN", "91", "India"),
    CountryEntry("US", "1", "United States"),
    CountryEntry("GB", "44", "United Kingdom"),
    CountryEntry("AE", "971", "UAE"),
    CountryEntry("SA", "966", "Saudi Arabia"),
    CountryEntry("QA", "974", "Qatar"),
    CountryEntry("KW", "965", "Kuwait"),
    CountryEntry("OM", "968", "Oman"),
    CountryEntry("BH", "973", "Bahrain"),
    CountryEntry("SG", "65", "Singapore"),
    CountryEntry("MY", "60", "Malaysia"),
    CountryEntry("PK", "92", "Pakistan"),
    CountryEntry("BD", "880", "Bangladesh"),
    CountryEntry("LK", "94", "Sri Lanka"),
    CountryEntry("NP", "977", "Nepal"),
    CountryEntry("PH", "63", "Philippines"),
    CountryEntry("ID", "62", "Indonesia"),
    CountryEntry("AU", "61", "Australia"),
    CountryEntry("DE", "49", "Germany"),
    CountryEntry("FR", "33", "France"),
    CountryEntry("CA", "1", "Canada"),
    CountryEntry("ZA", "27", "South Africa"),
    CountryEntry("NG", "234", "Nigeria"),
    CountryEntry("BR", "55", "Brazil"),
    CountryEntry("EG", "20", "Egypt")
)

private val USERNAME_RULE = Regex("^[A-Za-z0-9._]{3,30}$")

/**
 * "New contact" form reproducing the reference screen: back + title + QR app
 * bar, icon-gutter outlined fields (First/Last/Username with @ icon,
 * Country "IN +91" + Phone), sync row with switch, bottom grey Save pill.
 * Saving writes through [ContactsViewModel.addContact] into the same
 * DataStore-backed contacts the rest of the app reads.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewContactScreen(
    viewModel: ContactsViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current

    var first by remember { mutableStateOf("") }
    var last by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var country by remember { mutableStateOf(COUNTRIES.first { it.iso == "US" }) }
    var countryMenu by remember { mutableStateOf(false) }
    var number by remember { mutableStateOf("") }
    var sync by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<ContactFormError?>(null) }

    val cleanUsername = username.trim().removePrefix("@").replace("\\s".toRegex(), "")
    val usernameOk = cleanUsername.isEmpty() || USERNAME_RULE.matches(cleanUsername)
    val fullPhone = ContactBook.fullPhoneNumber(country.dial, number)
    val canSave = first.trim().isNotEmpty() &&
        ContactBook.isValidPhone(fullPhone) &&
        usernameOk && !saving

    fun submit() {
        if (!canSave) return
        scope.launch {
            saving = true
            saveError = viewModel.addContact(
                firstName = first.trim(),
                lastName = last.trim(),
                phone = fullPhone,
                photoUri = null,
                username = cleanUsername.ifEmpty { null },
                syncToPhone = sync
            )
            saving = false
            if (saveError == null) {
                onSaved()
            } else if (saveError == ContactFormError.DUPLICATE) {
                snackbar.showSnackbar(saveError!!.message())
            }
        }
    }

    Scaffold(
        containerColor = NcBackground,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text("New contact")
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            scope.launch {
                                snackbar.showSnackbar("QR contact sharing isn't available yet.")
                            }
                        }) {
                            Icon(
                                Icons.Outlined.QrCode2,
                                contentDescription = "Share QR code"
                            )
                        }
                    }
                )
                HorizontalDivider(color = NcDivider, thickness = 1.dp)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // First name (person icon gutter).
                FormRow(icon = Icons.Outlined.Person, description = "First name icon") {
                    OutlinedTextField(
                        value = first,
                        onValueChange = { first = it; saveError = null },
                        label = { Text("First name") },
                        singleLine = true,
                        isError = saveError == ContactFormError.FIRST_REQUIRED,
                        supportingText = {
                            if (saveError == ContactFormError.FIRST_REQUIRED) {
                                Text(ContactFormError.FIRST_REQUIRED.message())
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        ),
                        shape = NcFieldShape,
                        colors = ncFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                // Last name (aligned with the field above, no icon).
                FormRow(icon = null, description = null) {
                    OutlinedTextField(
                        value = last,
                        onValueChange = { last = it },
                        label = { Text("Last name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        ),
                        shape = NcFieldShape,
                        colors = ncFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                // Username (@ icon gutter).
                FormRow(icon = Icons.Outlined.AlternateEmail, description = "Username icon") {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it; saveError = null },
                        label = { Text("Username") },
                        singleLine = true,
                        isError = !usernameOk,
                        supportingText = {
                            if (!usernameOk) {
                                Text("Usernames use 3-30 letters, numbers, . or _")
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        ),
                        shape = NcFieldShape,
                        colors = ncFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                // Country + Phone (phone icon gutter).
                FormRow(icon = Icons.Outlined.Phone, description = "Phone icon") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(modifier = Modifier.width(148.dp)) {
                            // Read-only outlined field so the "Country" label
                            // notches the border exactly like the reference.
                            OutlinedTextField(
                                value = "${country.iso} +${country.dial}",
                                onValueChange = {},
                                readOnly = true,
                                enabled = false,
                                label = { Text("Country") },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.ArrowDropDown,
                                        contentDescription = "Choose country",
                                        tint = NcIcon
                                    )
                                },
                                singleLine = true,
                                shape = NcFieldShape,
                                colors = ncFieldColors(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            // Invisible tap layer opening the country menu.
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { countryMenu = true }
                            )
                            DropdownMenu(
                                expanded = countryMenu,
                                onDismissRequest = { countryMenu = false }
                            ) {
                                COUNTRIES.forEach { entry ->
                                    DropdownMenuItem(
                                        text = { Text("${entry.name} (+${entry.dial})") },
                                        onClick = {
                                            country = entry
                                            countryMenu = false
                                        }
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = number,
                            onValueChange = { number = it; saveError = null },
                            label = { Text("Phone") },
                            singleLine = true,
                            isError = saveError == ContactFormError.PHONE_REQUIRED ||
                                saveError == ContactFormError.PHONE_INVALID ||
                                saveError == ContactFormError.DUPLICATE,
                            supportingText = {
                                val err = saveError
                                if (err == ContactFormError.PHONE_REQUIRED ||
                                    err == ContactFormError.PHONE_INVALID ||
                                    err == ContactFormError.DUPLICATE
                                ) {
                                    Text(err.message())
                                }
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Phone,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = {
                                focusManager.clearFocus()
                                submit()
                            }),
                            shape = NcFieldShape,
                            colors = ncFieldColors(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                // Sync row (sync icon gutter + switch).
                FormRow(icon = Icons.Outlined.Sync, description = "Sync icon") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Sync contact to phone",
                                style = MaterialTheme.typography.titleMedium,
                                color = NcOnBackground
                            )
                            Text(
                                "Only contacts with a phone number can be synced",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NcHint
                            )
                        }
                        Switch(
                            checked = sync,
                            onCheckedChange = { sync = it },
                            thumbContent = {
                                Icon(
                                    imageVector = if (sync) Icons.Default.Check else Icons.Default.Remove,
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedThumbColor = Color.White,
                                checkedIconColor = MaterialTheme.colorScheme.primary,
                                uncheckedTrackColor = Color(0xFFE4E4E4),
                                uncheckedBorderColor = NcBorder,
                                uncheckedThumbColor = NcIcon,
                                uncheckedIconColor = Color.White
                            )
                        )
                    }
                }
            }
            // Bottom Save pill (grey until the form is submittable).
            Button(
                onClick = ::submit,
                enabled = canSave,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                    disabledContainerColor = NcSaveDisabledBg,
                    disabledContentColor = NcSaveDisabledText
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp)
                    .height(56.dp)
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                } else {
                    Text("Save", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** Icon gutter + field, matching the reference row alignment. */
@Composable
private fun FormRow(
    icon: ImageVector?,
    description: String?,
    content: @Composable () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp)
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = description,
                    tint = NcIcon,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun ncFieldColors(): TextFieldColors {
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = NcOnBackground,
        unfocusedTextColor = NcOnBackground,
        disabledTextColor = NcOnBackground,
        errorTextColor = NcOnBackground,
        focusedContainerColor = NcBackground,
        unfocusedContainerColor = NcBackground,
        disabledContainerColor = NcBackground,
        errorContainerColor = NcBackground,
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = NcBorder,
        disabledBorderColor = NcBorder,
        errorBorderColor = MaterialTheme.colorScheme.error,
        focusedLabelColor = NcHint,
        unfocusedLabelColor = NcHint,
        disabledLabelColor = NcHint,
        errorLabelColor = MaterialTheme.colorScheme.error,
        disabledTrailingIconColor = NcIcon,
        cursorColor = NcOnBackground,
        focusedPlaceholderColor = NcHint,
        unfocusedPlaceholderColor = NcHint
    )
}
