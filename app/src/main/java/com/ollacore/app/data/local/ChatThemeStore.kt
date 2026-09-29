package com.ollacore.app.data.local

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ollacore.app.data.model.ChatCustom
import com.ollacore.app.data.model.ChatTextMode
import com.ollacore.app.data.model.ChatThemes
import com.ollacore.app.data.model.ChatWallpaper
import com.ollacore.app.data.model.ResolvedChatStyle
import com.ollacore.app.data.model.resolveChatStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.chatThemeStore by preferencesDataStore(name = "ollacore_chat_theme")

/**
 * Appearance → Chat Theme persistence (CLIENT-ONLY DataStore).
 *
 * Semantics (mirror the reference app): selecting a theme replaces BOTH
 * bubble and wallpaper, so it clears custom tweaks. The Wallpaper screen
 * overrides only the wallpaper; the Bubble screen overrides only bubbles.
 * Everything survives restart; unknown ids fall back to the default theme.
 */
class ChatThemeStore(private val context: Context) {

    companion object {
        private val KEY_THEME = stringPreferencesKey("theme_id")
        private val KEY_IN = intPreferencesKey("custom_in")
        private val KEY_OUT = intPreferencesKey("custom_out")
        private val KEY_TEXT_MODE = stringPreferencesKey("text_mode")
        private val KEY_CORNER = floatPreferencesKey("corner_dp")
        private val KEY_OPACITY = floatPreferencesKey("opacity")
        private val KEY_WALLPAPER_ID = stringPreferencesKey("wallpaper_id")
        private val KEY_WALLPAPER_URI = stringPreferencesKey("wallpaper_uri")
        private val KEY_WALLPAPER_COLOR = intPreferencesKey("wallpaper_color")

        /** Built-in wallpapers selectable by id (theme wallpapers by theme id). */
        val BUILTIN_WALLPAPER_IDS: List<String> =
            ChatThemes.all.map { it.id } + listOf("solid_black", "solid_midnight", "solid_cream")

        fun builtinWallpaper(id: String): ChatWallpaper = when (id) {
            "solid_black" -> ChatWallpaper.Solid(Color(0xFF000000))
            "solid_midnight" -> ChatWallpaper.Solid(Color(0xFF0B141A))
            "solid_cream" -> ChatWallpaper.Solid(Color(0xFFFFFBEB))
            else -> ChatThemes.find(id).takeIf { it.id == id }?.wallpaper
                ?: ChatThemes.default.wallpaper
        }
    }

    val themeId: Flow<String> = context.chatThemeStore.data.map { it[KEY_THEME] ?: ChatThemes.default.id }

    val style: Flow<ResolvedChatStyle> = context.chatThemeStore.data.map { prefs ->
        val theme = ChatThemes.find(prefs[KEY_THEME])
        val inArgb = prefs[KEY_IN] ?: 0
        val outArgb = prefs[KEY_OUT] ?: 0
        val textMode = runCatching { ChatTextMode.valueOf(prefs[KEY_TEXT_MODE] ?: "AUTO") }
            .getOrElse { ChatTextMode.AUTO }
        val cornerDp = prefs[KEY_CORNER]?.takeIf { it >= 0f }
        val opacity = prefs[KEY_OPACITY]?.takeIf { it >= 0f }
        val wallpaper = wallpaperOf(prefs[KEY_WALLPAPER_ID], prefs[KEY_WALLPAPER_URI], prefs[KEY_WALLPAPER_COLOR] ?: 0)
        resolveChatStyle(
            theme,
            ChatCustom(
                incomingBubble = if (inArgb != 0) Color(inArgb) else null,
                outgoingBubble = if (outArgb != 0) Color(outArgb) else null,
                textMode = textMode,
                corner = cornerDp?.dp,
                opacity = opacity,
                wallpaper = wallpaper
            )
        )
    }

    private fun wallpaperOf(id: String?, uri: String?, colorArgb: Int): ChatWallpaper? {
        if (!uri.isNullOrBlank()) return ChatWallpaper.Photo(uri)
        if (colorArgb != 0) return ChatWallpaper.Solid(Color(colorArgb))
        if (id.isNullOrBlank()) return null
        return builtinWallpaper(id)
    }

    /** Selecting a theme replaces bubble + wallpaper: custom tweaks are cleared. */
    suspend fun selectTheme(id: String) {
        context.chatThemeStore.edit {
            it[KEY_THEME] = id
            it.remove(KEY_IN); it.remove(KEY_OUT); it.remove(KEY_TEXT_MODE)
            it.remove(KEY_CORNER); it.remove(KEY_OPACITY)
            it.remove(KEY_WALLPAPER_ID); it.remove(KEY_WALLPAPER_URI); it.remove(KEY_WALLPAPER_COLOR)
        }
    }

    suspend fun saveBubbles(
        incoming: Color?,
        outgoing: Color?,
        textMode: ChatTextMode,
        cornerDp: Float?,
        opacity: Float?
    ) {
        context.chatThemeStore.edit {
            if (incoming == null) it.remove(KEY_IN) else it[KEY_IN] = incoming.toArgb()
            if (outgoing == null) it.remove(KEY_OUT) else it[KEY_OUT] = outgoing.toArgb()
            it[KEY_TEXT_MODE] = textMode.name
            if (cornerDp == null) it.remove(KEY_CORNER) else it[KEY_CORNER] = cornerDp
            if (opacity == null) it.remove(KEY_OPACITY) else it[KEY_OPACITY] = opacity
        }
    }

    /** Wallpaper-only override (bubble tweaks untouched). */
    suspend fun saveWallpaper(wallpaper: ChatWallpaper?) {
        context.chatThemeStore.edit {
            it.remove(KEY_WALLPAPER_ID); it.remove(KEY_WALLPAPER_URI); it.remove(KEY_WALLPAPER_COLOR)
            when (wallpaper) {
                null -> Unit
                is ChatWallpaper.Photo -> it[KEY_WALLPAPER_URI] = wallpaper.uri
                is ChatWallpaper.Solid -> it[KEY_WALLPAPER_COLOR] = wallpaper.color.toArgb()
                else -> {
                    val id = ChatThemes.all.firstOrNull { t -> t.wallpaper == wallpaper }?.id
                    if (id != null) it[KEY_WALLPAPER_ID] = id
                }
            }
        }
    }

    /** Drops bubble + wallpaper tweaks, keeps the selected theme. */
    suspend fun resetCustomization() {
        context.chatThemeStore.edit {
            it.remove(KEY_IN); it.remove(KEY_OUT); it.remove(KEY_TEXT_MODE)
            it.remove(KEY_CORNER); it.remove(KEY_OPACITY)
            it.remove(KEY_WALLPAPER_ID); it.remove(KEY_WALLPAPER_URI); it.remove(KEY_WALLPAPER_COLOR)
        }
    }

    /** Full reset: default theme + no tweaks. */
    suspend fun resetAll() {
        context.chatThemeStore.edit { it.clear() }
    }

    private fun Color.toArgb(): Int {
        val a = (alpha * 255f + 0.5f).toInt()
        val r = (red * 255f + 0.5f).toInt()
        val g = (green * 255f + 0.5f).toInt()
        val b = (blue * 255f + 0.5f).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
