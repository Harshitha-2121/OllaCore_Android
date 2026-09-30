package com.ollacore.app.ui.appearance

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.ollacore.app.OllacoreApp
import com.ollacore.app.data.local.ChatThemeStore
import com.ollacore.app.data.model.ChatThemes
import com.ollacore.app.data.model.ResolvedChatStyle
import com.ollacore.app.data.model.resolveChatStyle
import androidx.compose.runtime.compositionLocalOf
import com.ollacore.app.data.model.ChatCustom

/** Nullable so MessageBubble keeps scheme colors outside a themed subtree. */
val LocalChatTheme = compositionLocalOf<ResolvedChatStyle?> { null }

/**
 * Effective app dark mode (MainActivity provides the themeMode-resolved
 * value; default true matches the app default ThemeMode.DARK).
 */
val LocalAppDark = compositionLocalOf { true }

/** Container store, same pattern as SettingsRoot.rememberPrefs. */
@Composable
fun rememberChatThemeStore(): ChatThemeStore {
    val context = LocalContext.current
    return remember(context) {
        (context.applicationContext as OllacoreApp).container.chatThemeStore
    }
}

/** Live resolved chat style (theme + tweaks), survives rotation/recreation. */
@Composable
fun rememberChatStyle(): ResolvedChatStyle {
    val store = rememberChatThemeStore()
    val appDark = LocalAppDark.current
    val themeId by store.themeId.collectAsState(initial = ChatThemes.default.id)
    val custom by store.custom.collectAsState(initial = ChatCustom())
    // Default follows the app dark/light mode (tweaks still apply on top);
    // explicitly picked themes render exactly as designed.
    return remember(themeId, custom, appDark) {
        val theme = if (ChatThemes.isDefaultId(themeId)) ChatThemes.defaultFor(appDark)
        else ChatThemes.find(themeId)
        resolveChatStyle(theme, custom)
    }
}

/** Currently selected theme id. */
@Composable
fun rememberSelectedThemeId(): String {
    val store = rememberChatThemeStore()
    val id by store.themeId.collectAsState(initial = ChatThemes.default.id)
    return id
}
