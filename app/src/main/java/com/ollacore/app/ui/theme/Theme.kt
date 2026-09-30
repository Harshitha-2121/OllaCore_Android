package com.ollacore.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.ollacore.app.data.local.ThemeMode

// Ollacore teal-green identity (WhatsApp-Web reference). Dynamic wallpaper
// tinting stays OFF so the identity holds on every device.
//
// Dark: near-black teal background (#0B141A), panel surfaces (#111B21),
// elevated surfaces (#202C33), hover/selection (#2A3942), teal-green
// primary (#00A884), light secondary text (#8696A0), blue links/ticks
// (#53BDEB). Light: gray panel background (#F0F2F5), white surfaces,
// deep-teal primary (#008069), green-tinted containers (#D9FDD3).
private val LightBrandScheme = lightColorScheme(
    primary = Color(0xFF008069),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9FDD3),
    onPrimaryContainer = Color(0xFF0B3B2E),
    secondary = Color(0xFF00796B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0F2F5),
    onSecondaryContainer = Color(0xFF111B21),
    tertiary = Color(0xFF027EB5),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE3F2FA),
    onTertiaryContainer = Color(0xFF023B5C),
    background = Color(0xFFF0F2F5),
    onBackground = Color(0xFF111B21),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111B21),
    surfaceVariant = Color(0xFFF0F2F5),
    onSurfaceVariant = Color(0xFF667781),
    outline = Color(0xFFDDE3E8),
    error = Color(0xFFD32F2F),
    onError = Color.White,
    errorContainer = Color(0xFFFDECEC),
    onErrorContainer = Color(0xFF8C1D18)
)

private val DarkBrandScheme = darkColorScheme(
    primary = Color(0xFF00A884),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF0B3B2E),
    onPrimaryContainer = Color(0xFFD9FDD3),
    secondary = Color(0xFF25D366),
    onSecondary = Color(0xFF041414),
    secondaryContainer = Color(0xFF202C33),
    onSecondaryContainer = Color(0xFFE9EDEF),
    tertiary = Color(0xFF53BDEB),
    onTertiary = Color(0xFF0B1414),
    tertiaryContainer = Color(0xFF0F2E3A),
    onTertiaryContainer = Color(0xFFBFE7FA),
    background = Color(0xFF0B141A),
    onBackground = Color(0xFFE9EDEF),
    surface = Color(0xFF111B21),
    onSurface = Color(0xFFE9EDEF),
    surfaceVariant = Color(0xFF202C33),
    onSurfaceVariant = Color(0xFF8696A0),
    outline = Color(0xFF2A3942),
    error = Color(0xFFF15C6D),
    onError = Color.White,
    errorContainer = Color(0xFF3E1D20),
    onErrorContainer = Color(0xFFFFDAD6)
)

/**
 * Single source of truth for light/dark resolution (also unit-tested):
 * DARK forces dark, LIGHT forces light, every other mode follows the system.
 */
fun resolveDarkTheme(themeMode: ThemeMode, systemDark: Boolean): Boolean =
    themeMode == ThemeMode.DARK || (themeMode != ThemeMode.LIGHT && systemDark)

/**
 * Spec 31: theme changes propagate to buttons, FAB, selected tabs,
 * chat bubbles, highlights and progress (all read colorScheme.primary).
 */
@Composable
fun OllacoreTheme(
    themeMode: ThemeMode = ThemeMode.BLUE,
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    // DARK forces dark, LIGHT forces light; every other mode follows the
    // system (spec 31: Dark + Light + System Default).
    val effectiveDark = resolveDarkTheme(themeMode, darkTheme)

    // One brand identity: every accent mode renders the teal-green family
    // (legacy Blue/Green/Purple choices converge here); only the
    // light/dark tuning differs.
    val colorScheme = if (effectiveDark) DarkBrandScheme else LightBrandScheme

    val finalScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (effectiveDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> colorScheme
    }

    MaterialTheme(
        colorScheme = finalScheme,
        typography = OllacoreTypography,
        content = content
    )
}
