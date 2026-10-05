package com.ollacore.app.auth

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ollacore.app.ui.theme.GlassCard
import com.ollacore.app.ui.theme.GradientButton
import com.ollacore.app.ui.theme.HeroWash
import com.ollacore.app.ui.theme.OllacoreLogo
import kotlinx.coroutines.delay

private const val OTP_LEN = 6
private const val RESEND_SECONDS = 60L

/**
 * OTP (spec section 8): "Verify Your Number", six boxes with autofocus,
 * paste distribution, countdown + resend, loading / invalid / success
 * states, subtle digit-pop animations. Same ViewModel contract.
 */
@Composable
fun OtpVerificationScreen(
    phone: String,
    otpCode: String,
    onOtpChange: (String) -> Unit,
    onVerify: () -> Unit,
    onBack: () -> Unit,
    isLoading: Boolean,
    error: String?,
    onErrorDismiss: () -> Unit,
    onResendOtp: () -> Unit = {}
) {
    var boxes by remember { mutableStateOf(List(OTP_LEN) { "" }) }
    var submitted by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableStateOf(RESEND_SECONDS) }
    val focusers = remember { List(OTP_LEN) { FocusRequester() } }

    // Resend clears the VM code -> reset local boxes too.
    LaunchedEffect(otpCode) {
        if (otpCode.isEmpty() && boxes.any { it.isNotEmpty() }) {
            boxes = List(OTP_LEN) { "" }
            submitted = false
            focusers[0].requestFocus()
        }
    }
    // Initial autofocus.
    LaunchedEffect(Unit) {
        delay(350)
        runCatching { focusers[0].requestFocus() }
    }
    // Countdown.
    LaunchedEffect(secondsLeft) {
        if (secondsLeft > 0) {
            delay(1000)
            secondsLeft--
        }
    }
    // Invalid OTP from the backend clears the submitted flag (boxes shake via error tint).
    LaunchedEffect(error) {
        if (error != null) submitted = false
    }

    fun pushBoxes(next: List<String>) {
        boxes = next
        onOtpChange(next.joinToString(""))
    }

    fun onBoxInput(index: Int, raw: String) {
        // Strip the echo of the char already shown: IMEs (Gboard suggestion-strip
        // paste, autofill) often deliver newValue = oldChar + pastedText, so the
        // first digit was consumed as "single input" and paste started at box 2.
        val shown = boxes[index]
        val fresh = if (shown.isNotEmpty() && raw.startsWith(shown)) raw.removePrefix(shown) else raw
        val digits = fresh.filter(Char::isDigit)
        if (raw.length > 1 || digits.length > 1) {
            // Paste/autofill: distribute starting at THIS box (not box 0), keep
            // boxes outside the pasted range untouched.
            val next = boxes.toMutableList()
            digits.forEachIndexed { off, c ->
                val pos = index + off
                if (pos < OTP_LEN) next[pos] = c.toString()
            }
            pushBoxes(next)
            val focus = (index + digits.length).coerceAtMost(OTP_LEN - 1)
            runCatching { focusers[focus].requestFocus() }
            return
        }
        val next = boxes.toMutableList()
        next[index] = digits.takeLast(1)
        pushBoxes(next)
        if (digits.isNotEmpty() && index < OTP_LEN - 1) {
            runCatching { focusers[index + 1].requestFocus() }
        }
    }

    fun onBoxBackspace(index: Int): Boolean {
        if (boxes[index].isEmpty() && index > 0) {
            val next = boxes.toMutableList()
            next[index - 1] = ""
            pushBoxes(next)
            runCatching { focusers[index - 1].requestFocus() }
            return true
        }
        return false
    }

    val combined = boxes.joinToString("")
    // Auto-submit the moment the 6th digit lands (loading = verifying, success = home).
    LaunchedEffect(combined) {
        if (combined.length == OTP_LEN && !submitted && !isLoading) {
            submitted = true
            onVerify()
        }
    }

    val invalid = error != null

    Box(modifier = Modifier.fillMaxSize().background(HeroWash)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            OllacoreLogo(size = 72.dp)
            Spacer(Modifier.height(20.dp))
            Text(
                "Verify Your Number",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "We've sent a 6-digit code to your number.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Text(
                phone,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(28.dp))

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Six boxes with digit-pop animations + invalid tint.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        boxes.forEachIndexed { index, digit ->
                            val pop by animateFloatAsState(
                                targetValue = if (digit.isNotEmpty()) 1.08f else 1f,
                                animationSpec = tween(120),
                                label = "digit-pop"
                            )
                            OutlinedTextField(
                                value = digit,
                                onValueChange = { onBoxInput(index, it) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                shape = RoundedCornerShape(14.dp),
                                isError = invalid,
                                textStyle = MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = if (invalid) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .scale(pop)
                                    .focusRequester(focusers[index])
                                    .onKeyEvent { event ->
                                        if (event.key == Key.Backspace) onBoxBackspace(index)
                                        else false
                                    }
                            )
                        }
                    }

                    if (invalid && error != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextButton(onClick = {
                            pushBoxes(List(OTP_LEN) { "" })
                            onErrorDismiss()
                            runCatching { focusers[0].requestFocus() }
                        }) {
                            Text("Clear and retry")
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    GradientButton(
                        text = when {
                            isLoading -> "Verifying…"
                            submitted -> "Verified ✓"
                            else -> "Verify"
                        },
                        onClick = onVerify,
                        enabled = !isLoading && combined.length == OTP_LEN,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (isLoading) {
                        Spacer(Modifier.height(12.dp))
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                    if (submitted && !isLoading && !invalid) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Code accepted - signing you in…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                    TextButton(onClick = onBack, enabled = !isLoading, modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Use a different number")
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            if (secondsLeft > 0) {
                Text(
                    "Resend code in 0:${secondsLeft.toString().padStart(2, '0')}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                TextButton(onClick = {
                    secondsLeft = RESEND_SECONDS
                    onResendOtp()
                }, enabled = !isLoading) {
                    Text("Resend OTP")
                }
            }
        }
    }
}
