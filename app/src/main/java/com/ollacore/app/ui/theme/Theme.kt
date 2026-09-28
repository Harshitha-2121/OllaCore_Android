package com.ollacore.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.ollacore.app.data.local.ThemeMode

// Ollacore pink identity. Dynamic wallpaper tinting stays OFF so the
// Ollacore identity holds on every device.
//
// Light: pastel pink/cream background (#FBEFF3), white surfaces, deep-plum
// text, purple/magenta primary. Dark: near-black warm plum background,
// pink-tinted surfaces, light lavender primary. One primary family in both
// modes - never blue/green/orange outside semantic (success/warning/error)
// states.
private val LightBrandScheme = lightColorScheme(
    primary = Color(0xFFA21CAF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF5D0FE),
    onPrimaryContainer = Color(0xFF4A044E),
    secondary = Color(0xFFEC4899),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFCE7F3),
    onSecondaryContainer = Color(0xFF831843),
    tertiary = Color(0xFF7C3AED),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEDE9FE),
    onTertiaryContainer = Color(0xFF3B1470),
    background = Color(0xFFFBEFF3),
    onBackground = Color(0xFF4A2530),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF4A2530),
    surfaceVariant = Color(0xFFF6E3EA),
    onSurfaceVariant = Color(0xFF8A6570),
    outline = Color(0xFFE7C6D2),
    error = Color(0xFFEF4444),
    onError = Color.White
)

private val DarkBrandScheme = darkColorScheme(
    primary = Color(0xFFE9A6F5),
    onPrimary = Color(0xFF3B0A2E),
    primaryContainer = Color(0xFF5C1A4E),
    onPrimaryContainer = Color(0xFFFBDDF5),
    secondary = Color(0xFFF472B6),
    onSecondary = Color(0xFF3B0A1E),
    secondaryContainer = Color(0xFF5C1A34),
    onSecondaryContainer = Color(0xFFFBDDF0),
    tertiary = Color(0xFFC4B5FD),
    onTertiary = Color(0xFF2E1065),
    tertiaryContainer = Color(0xFF4C1D95),
    onTertiaryContainer = Color(0xFFEDE9FE),
    background = Color(0xFF150A10),
    onBackground = Color(0xFFF9EDEF),
    surface = Color(0xFF211016),
    onSurface = Color(0xFFF9EDEF),
    surfaceVariant = Color(0xFF2F1A23),
    onSurfaceVariant = Color(0xFFC998A8),
    outline = Color(0xFF4A2B37),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A)
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

    // One brand identity: every accent mode renders the pink/purple family
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
