package com.ollacore.app.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ollacore.app.data.local.ConnectivityObserver
import com.ollacore.app.ui.theme.BrandGradient

/**
 * Specs 33/34/35/40 shared kit: skeleton shimmer, friendly errors,
 * offline banner, icon-size tokens. Presentation only - ViewModels keep
 * owning state (spec 41: logic stays out of composables).
 */

// ── Spec 38/40: one icon family (Material), fixed sizes, 48dp touch ──

object IconSize {
    val S: Dp = 20.dp
    val M: Dp = 24.dp
    val L: Dp = 28.dp
}

// ── Spec 33: skeleton shimmer (never a blank screen) ─────────────────

@Composable
private fun shimmerAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmer-alpha"
    )
    return alpha
}

@Composable
fun ShimmerRow(
    modifier: Modifier = Modifier,
    avatarSize: Dp = 50.dp,
    lines: Int = 2
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(avatarSize)
                .clip(CircleShape)
                .alpha(shimmerAlpha())
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            repeat(lines) { index ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth(if (index == 0) 0.6f else 0.9f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .alpha(shimmerAlpha())
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                if (index < lines - 1) Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun SkeletonList(
    rows: Int = 6,
    modifier: Modifier = Modifier,
    avatarSize: Dp = 50.dp
) {
    Column(modifier = modifier.fillMaxWidth()) {
        repeat(rows) {
            ShimmerRow(avatarSize = avatarSize)
        }
    }
}

// ── Spec 34: friendly errors (never raw exceptions) ───────────────────

/** Maps technical failures to human copy (logic lives in data.util, tested). */
fun friendlyError(raw: String?): String =
    com.ollacore.app.data.util.friendlyError(raw)

@Composable
fun ErrorState(
    message: String?,
    onRetry: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onLoginExpired: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(BrandGradient)
        ) {
            Icon(
                Icons.Default.CloudOff,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(40.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("Something went wrong", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            friendlyError(message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onBack != null) {
                OutlinedButton(onClick = onBack) { Text("Go Back") }
            }
            // Expired sessions can't be retried into existence: offer log-in instead.
            val authExpired = com.ollacore.app.data.util.isAuthError(message)
            if (authExpired && onLoginExpired != null) {
                Button(onClick = onLoginExpired) {
                    Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Log in again")
                }
            } else if (onRetry != null) {
                Button(onClick = onRetry) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Retry")
                }
            }
        }
    }
}

/** Collects connectivity + remembers whether we have been offline (for "Back online"). */
@Composable
fun rememberConnectivity(): Pair<Boolean?, Boolean> {
    val context = LocalContext.current
    val observer = remember { ConnectivityObserver(context.applicationContext) }
    val online by observer.isOnline.collectAsState(initial = null)
    var everOffline by remember { mutableStateOf(false) }
    LaunchedEffect(online) {
        if (online == false) everOffline = true
    }
    return online to everOffline
}

// ── Spec 35: subtle offline banner (never blocks UI) ──────────────────

@Composable
fun OfflineBanner(
    isOnline: Boolean?,
    wasOffline: Boolean,
    modifier: Modifier = Modifier
) {
    when {
        isOnline == false -> {
            Surface(
                modifier = modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("No internet connection", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        isOnline == true && wasOffline -> {
            Surface(
                modifier = modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Back online", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
