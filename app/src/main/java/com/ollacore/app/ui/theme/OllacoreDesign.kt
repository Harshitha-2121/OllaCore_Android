package com.ollacore.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Ollacore premium design tokens (additive - no existing API touched).
 * Direction: clean white surfaces, dark navy/blue accents, green comms
 * accents, blue/purple gradient highlights, selective glass, big radii.
 */

// ── Official Ollacore brand palette ───────────────────────────────────
// "Connect. Chat. Share." - deep navy, royal blue, electric blue,
// soft purple, teal/green, white, very light blue/gray backgrounds.

val OllaPrimaryBlue = Color(0xFF5366FF)
val OllaPurple = Color(0xFF8B5CF6)
val OllaPink = Color(0xFFEC4899)
val OllaGreen = Color(0xFF10B981)
val OllaCallGreen = Color(0xFF22C55E)
val OllaDanger = Color(0xFFEF4444)
val OllaNavy900 = Color(0xFF0F172A)
val OllaDarkSurface = Color(0xFF111827)
val OllaMediumText = Color(0xFF334155)
val OllaSecondaryText = Color(0xFF64748B)
val OllaLightBg = Color(0xFFF8FAFC)
val OllaCardBg = Color(0xFFFFFFFF)
val OllaBorder = Color(0xFFE2E8F0)
val ReadBlue = Color(0xFF53BDEB)

/** Seen ticks: pink double-check. */
val ReadPink = Color(0xFFF06292)

// Back-compat aliases for earlier token names.
val OllaNavy700 = Color(0xFF1E2A4A)
val OllaBlue = OllaPrimaryBlue
val OllaSky = Color(0xFF7D8DFF)
val OllaViolet = OllaPurple
val OllaGreenBright = Color(0xFF34D399)
val OllaMist = OllaLightBg

// ── Gradients: RESTRICTED surfaces only ───────────────────────────────
// Allowed: splash, onboarding, selected buttons, important highlights,
// empty states, call screens, promotional cards. Everywhere else: solids.

val BrandGradient: Brush
    get() = Brush.linearGradient(listOf(Color(0xFFA855F7), Color(0xFFEC4899)))

val NavyGradient: Brush
    get() = Brush.linearGradient(listOf(OllaNavy700, OllaNavy900))

val GreenGradient: Brush
    get() = Brush.linearGradient(listOf(OllaGreen, OllaCallGreen))

val HeroWash: Brush
    get() = Brush.verticalGradient(
        listOf(
            OllaPrimaryBlue.copy(alpha = 0.12f),
            OllaPurple.copy(alpha = 0.06f),
            Color.Transparent
        )
    )

val SplashGradient: Brush
    get() = Brush.linearGradient(
        listOf(Color(0xFF1E0716), Color(0xFF6D28D9), Color(0xFFA855F7))
    )

/** Stable solid avatar colors: purple/magenta/plum family (one identity, still distinct per contact). */
private val AvatarSolids = listOf(
    Color(0xFFA855F7),
    Color(0xFFEC4899),
    Color(0xFF7C3AED),
    Color(0xFFD946EF),
    Color(0xFF9333EA)
)

/** Deterministic solid color per name - stable avatar identity without photos. */
@Composable
fun avatarColorFor(name: String): Color {
    return remember(name) {
        if (name.isBlank()) AvatarSolids[0]
        else AvatarSolids[kotlin.math.abs(name.hashCode()) % AvatarSolids.size]
    }
}

@Composable
fun initialsFor(name: String): String {
    return remember(name) {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        when {
            parts.isEmpty() -> "?"
            parts.size == 1 -> parts[0].take(2).uppercase()
            else -> "${parts[0].first()}${parts[1].first()}".uppercase()
        }
    }
}

// ── Signature components ──────────────────────────────────────────────

/** Brand tagline. */
const val OLLACORE_TAGLINE = "Connect. Chat. Share."

/** Consistent Ollacore logo mark: gradient rounded square with "O". */
@Composable
fun OllacoreLogo(
    modifier: Modifier = Modifier,
    size: Dp = 88.dp,
    glyphSize: Dp = 44.dp
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(28.dp))
            .background(BrandGradient)
    ) {
        Text(
            "O",
            color = Color.White,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = glyphSize * 0.05f)
        )
    }
}

/** Solid-color avatar with initials + optional presence dot. */
@Composable
fun BrandAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    showPresence: Boolean = false,
    isOnline: Boolean = false
) {
    Box(modifier = modifier.size(size)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(avatarColorFor(name))
        ) {
            Text(
                initialsFor(name),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        if (showPresence) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.32f)
                    .clip(CircleShape)
                    .background(if (isOnline) OllaGreenBright else MaterialTheme.colorScheme.outline)
                    .padding(size * 0.06f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                )
            }
        }
    }
}

/** Frosted glass card (translucency + hairline border, used selectively). */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    val border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f))
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
    val elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors, border = border, elevation = elevation) {
            Box(modifier = Modifier.padding(PaddingValues(horizontal = 16.dp, vertical = 12.dp))) {
                content()
            }
        }
    } else {
        Card(modifier = modifier, shape = shape, colors = colors, border = border, elevation = elevation) {
            Box(modifier = Modifier.padding(PaddingValues(horizontal = 16.dp, vertical = 12.dp))) {
                content()
            }
        }
    }
}

/** Primary CTA: gradient pill with white label. */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    brush: Brush = BrandGradient,
    contentPadding: PaddingValues = PaddingValues(vertical = 14.dp)
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        shadowElevation = if (enabled) 6.dp else 0.dp
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (enabled) brush
                    else Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                )
                .padding(contentPadding)
        ) {
            Text(
                text,
                color = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** Soft section label used across lists. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 8.dp)
    )
}

/** Corner radii shared across the app (spec: chat bubbles 16-20dp). */
val CardRadius = 24.dp
val BubbleRadiusOwn = RoundedCornerShape(
    topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 6.dp
)
val BubbleRadiusPeer = RoundedCornerShape(
    topStart = 18.dp, topEnd = 18.dp, bottomStart = 6.dp, bottomEnd = 18.dp
)
val PillRadius = RoundedCornerShape(28.dp)

/**
 * Outgoing bubble wash (spec section 10: soft green/blue, never bright).
 * De-saturated pastels in light mode, deep muted tones in dark mode.
 */
val OwnBubbleLight: Brush
    get() = Brush.linearGradient(
        listOf(Color(0xFFD4F2DC), Color(0xFFDCE7FD))
    )
val OwnBubbleDark: Brush
    get() = Brush.linearGradient(
        listOf(Color(0xFF144A36), Color(0xFF22345E))
    )
