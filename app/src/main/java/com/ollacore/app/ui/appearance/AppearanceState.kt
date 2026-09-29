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

/** Nullable so MessageBubble keeps scheme colors outside a themed subtree. */
val LocalChatTheme = compositionLocalOf<ResolvedChatStyle?> { null }

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
    val style by store.style.collectAsState(initial = resolveChatStyle(ChatThemes.default))
    return style
}

/** Currently selected theme id. */
@Composable
fun rememberSelectedThemeId(): String {
    val store = rememberChatThemeStore()
    val id by store.themeId.collectAsState(initial = ChatThemes.default.id)
    return id
}
