package com.ollacore.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.ollacore.app.data.local.ThemeMode

// Official Ollacore brand schemes. Dynamic wallpaper tinting stays OFF
// so the Ollacore identity holds on every device.
//
// Spec 29 (dark, deliberate - not inverted): bg #0B1120, surface #111827,
// card #172033, text #F8FAFC, secondary #94A3B8, blue/purple/green accents.
// Spec 30 (light, default): white, #F8FAFC, light blue-gray, minimal shadow.
private val DarkBlueScheme = darkColorScheme(
    primary = Color(0xFF8B9DFF),
    onPrimary = Color(0xFF0F172A),
    primaryContainer = Color(0xFF2A3565),
    onPrimaryContainer = Color(0xFFE2E6FF),
    secondary = Color(0xFF34D399),
    onSecondary = Color(0xFF062B16),
    secondaryContainer = Color(0xFF0E3B2A),
    onSecondaryContainer = Color(0xFFC9F2D8),
    tertiary = Color(0xFFA78BFA),
    onTertiary = Color(0xFF2A1656),
    tertiaryContainer = Color(0xFF3B2A63),
    onTertiaryContainer = Color(0xFFE4D9FF),
    background = Color(0xFF0B1120),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF111827),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF172033),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF334155),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A)
)

private val DarkGreenScheme = DarkBlueScheme.copy(
    primary = Color(0xFF34D399),
    onPrimary = Color(0xFF062B16),
    primaryContainer = Color(0xFF0E3B2A),
    onPrimaryContainer = Color(0xFFC9F2D8)
)

private val DarkPurpleScheme = DarkBlueScheme.copy(
    primary = Color(0xFFA78BFA),
    onPrimary = Color(0xFF2A1656),
    primaryContainer = Color(0xFF3B2A63),
    onPrimaryContainer = Color(0xFFE4D9FF)
)

private val LightBlueScheme = lightColorScheme(
    primary = Color(0xFF5366FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2E6FF),
    onPrimaryContainer = Color(0xFF0F172A),
    secondary = Color(0xFF10B981),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF064E3B),
    tertiary = Color(0xFF8B5CF6),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEDE9FE),
    onTertiaryContainer = Color(0xFF2A1656),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF334155),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF334155),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF64748B),
    outline = Color(0xFFE2E8F0),
    error = Color(0xFFEF4444),
    onError = Color.White
)

private val LightGreenScheme = LightBlueScheme.copy(
    primary = Color(0xFF10B981),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1FAE5),
    onPrimaryContainer = Color(0xFF064E3B)
)

private val LightPurpleScheme = LightBlueScheme.copy(
    primary = Color(0xFF8B5CF6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE9FE),
    onPrimaryContainer = Color(0xFF2A1656)
)

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
    // DARK forces dark; every other mode follows the system (spec 31: Dark + System Default).
    val effectiveDark = themeMode == ThemeMode.DARK || darkTheme

    val colorScheme = when (themeMode) {
        ThemeMode.GREEN -> if (effectiveDark) DarkGreenScheme else LightGreenScheme
        ThemeMode.PURPLE -> if (effectiveDark) DarkPurpleScheme else LightPurpleScheme
        else -> if (effectiveDark) DarkBlueScheme else LightBlueScheme
    }

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
