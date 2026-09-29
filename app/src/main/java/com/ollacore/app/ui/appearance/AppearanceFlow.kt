package com.ollacore.app.ui.appearance

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Appearance entry point (Settings → Appearance section).
 * Own little back-stack so Back walks theme → bubble/wallpaper → main →
 * Settings, without touching the app nav graph.
 */
@Composable
fun AppearanceFlow(onBack: () -> Unit, modifier: Modifier = Modifier) {
    var screen by remember { mutableStateOf("main") }
    BackHandler(enabled = screen != "main") { screen = "main" }
    when (screen) {
        "theme" -> ChatThemeScreen(
            onBack = { screen = "main" },
            onOpenBubbles = { screen = "bubble" },
            onOpenWallpaper = { screen = "wallpaper" },
            modifier = modifier
        )
        "bubble" -> BubbleScreen(onBack = { screen = "theme" }, modifier = modifier)
        "wallpaper" -> WallpaperScreen(onBack = { screen = "theme" }, modifier = modifier)
        else -> AppearanceScreen(
            onBack = onBack,
            onOpenTheme = { screen = "theme" },
            modifier = modifier
        )
    }
}
