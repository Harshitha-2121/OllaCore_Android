package com.ollacore.app.data.model

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Appearance → Chat Theme system.
 *
 * Every theme is DATA (this file), never separate UI code: the theme grid,
 * previews, wallpaper/bubble screens and the live chat all render from
 * [ChatTheme] + [ResolvedChatStyle]. Adding a theme = one list entry.
 *
 * Wallpapers are procedural (Canvas painters, zero assets) so the catalog
 * stays dependency-free and resolution-independent.
 */

// ── Wallpapers ──────────────────────────────────────────────────────────

sealed interface ChatWallpaper {
    /** Dark doodle glyphs (rings/plus/arcs/squares). Null colors = app scheme. */
    data class Doodle(val base: Color? = null, val ink: Color? = null) : ChatWallpaper
    /** Linear gradient wash + soft radial blobs. */
    data class GradientWash(val colors: List<Color>, val blob: Color) : ChatWallpaper
    /** Large translucent pastel blobs on a light base. */
    data class PastelBlobs(val base: Color, val blobs: List<Color>) : ChatWallpaper
    /** Abstract flower: stem arcs + petals. */
    data class Floral(val base: Color, val petal: Color, val leaf: Color) : ChatWallpaper
    /** Sky gradient + tree-canopy blobs along the edges. */
    data class SkyNature(val top: Color, val bottom: Color, val canopy: Color) : ChatWallpaper
    /** Dark base + big rotated leaf ellipses. */
    data class DarkLeaves(val base: Color, val leaf: Color, val vein: Color) : ChatWallpaper
    /** Diagonal translucent wave bands. */
    data class Waves(val base: Color, val band: Color) : ChatWallpaper
    /** Flat color (custom color wallpapers). */
    data class Solid(val color: Color) : ChatWallpaper
    /** User gallery pick (persisted Uri string). */
    data class Photo(val uri: String) : ChatWallpaper
}

// ── Theme ───────────────────────────────────────────────────────────────

data class ChatTheme(
    val id: String,
    val name: String,
    val wallpaper: ChatWallpaper,
    val incomingBubble: Color,
    val incomingText: Color,
    val outgoingBubble: Color,
    val outgoingText: Color,
    val corner: Dp = 16.dp
)

// ── Catalog ─────────────────────────────────────────────────────────────

private const val INK_DARK = 0xFF1F2C34
private const val INK_LIGHT = 0xFFF1F5F9
private const val PAPER_LIGHT = 0xFFFFFBEB

object ChatThemes {
    val default: ChatTheme = ChatTheme(
        id = "default_dark",
        name = "Default",
        wallpaper = ChatWallpaper.Doodle(),
        incomingBubble = Color(INK_DARK),
        incomingText = Color.White,
        outgoingBubble = Color(0xFF00A884),
        outgoingText = Color.White
    )

    val all: List<ChatTheme> = listOf(
        default,
        ChatTheme(
            id = "dark_purple", name = "Deep Purple",
            wallpaper = ChatWallpaper.Doodle(base = Color(0xFF14101F)),
            incomingBubble = Color(INK_DARK), incomingText = Color.White,
            outgoingBubble = Color(0xFF7C5CFF), outgoingText = Color.White
        ),
        ChatTheme(
            id = "dark_magenta", name = "Magenta Night",
            wallpaper = ChatWallpaper.Doodle(base = Color(0xFF1C1018)),
            incomingBubble = Color(INK_DARK), incomingText = Color.White,
            outgoingBubble = Color(0xFFC93BAE), outgoingText = Color.White
        ),
        ChatTheme(
            id = "dark_orange", name = "Ember",
            wallpaper = ChatWallpaper.Doodle(base = Color(0xFF1D130C)),
            incomingBubble = Color(INK_DARK), incomingText = Color.White,
            outgoingBubble = Color(0xFFE8712B), outgoingText = Color.White
        ),
        ChatTheme(
            id = "dark_teal", name = "Teal Depth",
            wallpaper = ChatWallpaper.Doodle(base = Color(0xFF0B1A18)),
            incomingBubble = Color(INK_DARK), incomingText = Color.White,
            outgoingBubble = Color(0xFF009688), outgoingText = Color.White
        ),
        ChatTheme(
            id = "dark_blue", name = "Midnight Blue",
            wallpaper = ChatWallpaper.Doodle(base = Color(0xFF0C1526)),
            incomingBubble = Color(INK_DARK), incomingText = Color.White,
            outgoingBubble = Color(0xFF3B82F6), outgoingText = Color.White
        ),
        ChatTheme(
            id = "nature_sky", name = "Canopy Sky",
            wallpaper = ChatWallpaper.SkyNature(
                top = Color(0xFF9FC5E8), bottom = Color(0xFFD9EAD3), canopy = Color(0xFF38761D)
            ),
            incomingBubble = Color(0xFF243B2A), incomingText = Color.White,
            outgoingBubble = Color(0xFF1F6F43), outgoingText = Color.White
        ),
        ChatTheme(
            id = "abstract_pink", name = "Rose Wash",
            wallpaper = ChatWallpaper.PastelBlobs(
                base = Color(0xFFF9E2D2),
                blobs = listOf(Color(0xFFF5B8C1), Color(0xFFFADADD), Color(0xFFE8A0BF))
            ),
            incomingBubble = Color(0xFFFFF7ED), incomingText = Color(0xFF431407),
            outgoingBubble = Color(0xFFC2255C), outgoingText = Color.White
        ),
        ChatTheme(
            id = "dark_leaves", name = "Night Leaves",
            wallpaper = ChatWallpaper.DarkLeaves(
                base = Color(0xFF0E1512), leaf = Color(0xFF1E4633), vein = Color(0xFF2F6B4F)
            ),
            incomingBubble = Color(0xFF1A2420), incomingText = Color.White,
            outgoingBubble = Color(0xFFE03131), outgoingText = Color.White
        ),
        ChatTheme(
            id = "gray_abstract", name = "Stone Flow",
            wallpaper = ChatWallpaper.Waves(base = Color(0xFF4B5563), band = Color(0xFF9CA3AF)),
            incomingBubble = Color(0xFFE5E7EB), incomingText = Color(0xFF111827),
            outgoingBubble = Color(0xFF6B7280), outgoingText = Color.White
        ),
        ChatTheme(
            id = "black_abstract", name = "Onyx Flow",
            wallpaper = ChatWallpaper.Waves(base = Color(0xFF0A0A0B), band = Color(0xFF3F3F46)),
            incomingBubble = Color(0xFF27272A), incomingText = Color.White,
            outgoingBubble = Color(0xFF52525B), outgoingText = Color.White
        ),
        ChatTheme(
            id = "pastel_purple", name = "Lavender Mist",
            wallpaper = ChatWallpaper.PastelBlobs(
                base = Color(0xFFE9E4F5),
                blobs = listOf(Color(0xFFC4B5FD), Color(0xFFDDD6FE), Color(0xFFA78BFA))
            ),
            incomingBubble = Color(0xFFFBFAFF), incomingText = Color(0xFF2E1065),
            outgoingBubble = Color(0xFF7048E8), outgoingText = Color.White
        ),
        ChatTheme(
            id = "pink_floral", name = "Bloom",
            wallpaper = ChatWallpaper.Floral(
                base = Color(0xFFFDE8E4), petal = Color(0xFFF06595), leaf = Color(0xFF66A80F)
            ),
            incomingBubble = Color(0xFFFFF0F3), incomingText = Color(0xFF5C1031),
            outgoingBubble = Color(0xFFD6336C), outgoingText = Color.White
        ),
        ChatTheme(
            id = "sunset_glow", name = "Amber Dusk",
            wallpaper = ChatWallpaper.GradientWash(
                colors = listOf(Color(0xFFFDBA74), Color(0xFFFB923C), Color(0xFFC2410C)),
                blob = Color(0xFFFEF3C7)
            ),
            incomingBubble = Color(0xFF431407), incomingText = Color.White,
            outgoingBubble = Color(0xFFD9480F), outgoingText = Color.White
        ),
        ChatTheme(
            id = "ocean_blue", name = "Deep Ocean",
            wallpaper = ChatWallpaper.GradientWash(
                colors = listOf(Color(0xFF082F49), Color(0xFF0C4A6E), Color(0xFF0369A1)),
                blob = Color(0xFF38BDF8)
            ),
            incomingBubble = Color(0xFF0C2D48), incomingText = Color.White,
            outgoingBubble = Color(0xFF1971C2), outgoingText = Color.White
        ),
    )

    fun find(id: String?): ChatTheme = all.firstOrNull { it.id == id } ?: default
}

// ── Custom overrides ────────────────────────────────────────────────────

enum class ChatTextMode { AUTO, LIGHT, DARK }

/** User tweaks from the Bubble/Wallpaper screens. Everything optional. */
data class ChatCustom(
    val incomingBubble: Color? = null,
    val outgoingBubble: Color? = null,
    val textMode: ChatTextMode = ChatTextMode.AUTO,
    /** Null = theme corner. */
    val corner: Dp? = null,
    /** 0..1, null = opaque. */
    val opacity: Float? = null,
    val wallpaper: ChatWallpaper? = null
) {
    val hasBubbleTweaks: Boolean get() =
        incomingBubble != null || outgoingBubble != null || textMode != ChatTextMode.AUTO ||
            corner != null || opacity != null
}

// ── Resolved style (what the chat UI actually consumes) ─────────────────

/**
 * Fully-resolved chat appearance: theme base + custom overrides applied.
 * Pure function of (theme, custom) — unit-tested.
 */
data class ResolvedChatStyle(
    val themeId: String,
    val wallpaper: ChatWallpaper,
    val incomingBubble: Color,
    val incomingText: Color,
    val outgoingBubble: Color,
    val outgoingText: Color,
    val corner: Dp
)

fun resolveChatStyle(theme: ChatTheme, custom: ChatCustom = ChatCustom()): ResolvedChatStyle {
    val opacity = custom.opacity?.coerceIn(0.35f, 1f) ?: 1f
    val inBubble = (custom.incomingBubble ?: theme.incomingBubble).copy(alpha = opacity)
    val outBubble = (custom.outgoingBubble ?: theme.outgoingBubble).copy(alpha = opacity)
    return ResolvedChatStyle(
        themeId = theme.id,
        wallpaper = custom.wallpaper ?: theme.wallpaper,
        incomingBubble = inBubble,
        incomingText = when (custom.textMode) {
            ChatTextMode.LIGHT -> Color.White
            ChatTextMode.DARK -> Color(0xFF111111)
            ChatTextMode.AUTO -> pickTextOn(theme.incomingBubble, theme.incomingText)
        },
        outgoingBubble = outBubble,
        outgoingText = when (custom.textMode) {
            ChatTextMode.LIGHT -> Color.White
            ChatTextMode.DARK -> Color(0xFF111111)
            ChatTextMode.AUTO -> pickTextOn(theme.outgoingBubble, theme.outgoingText)
        },
        corner = custom.corner ?: theme.corner
    )
}

/**
 * Keeps the theme's authored text color when it already contrasts with the
 * (possibly custom) bubble; otherwise falls back by luminance. Pure.
 */
fun pickTextOn(bubble: Color, authored: Color): Color {
    if (contrastRatio(bubble, authored) >= 3f) return authored
    return if (luminance(bubble) > 0.4f) Color(0xFF111111) else Color.White
}

fun luminance(c: Color): Float {
    fun lin(v: Float): Float =
        if (v <= 0.03928f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    return 0.2126f * lin(c.red) + 0.7152f * lin(c.green) + 0.0722f * lin(c.blue)
}

fun contrastRatio(a: Color, b: Color): Float {
    val (l1, l2) = maxOf(luminance(a), luminance(b)) to minOf(luminance(a), luminance(b))
    return (l1 + 0.05f) / (l2 + 0.05f)
}

/** WhatsApp-style asymmetric bubble shapes from a corner radius. Pure. */
fun bubbleShapes(corner: Dp): Pair<androidx.compose.foundation.shape.RoundedCornerShape, androidx.compose.foundation.shape.RoundedCornerShape> {
    val tail = 4.dp
    val own = androidx.compose.foundation.shape.RoundedCornerShape(
        topStart = corner, topEnd = corner, bottomStart = corner, bottomEnd = tail
    )
    val peer = androidx.compose.foundation.shape.RoundedCornerShape(
        topStart = tail, topEnd = corner, bottomStart = corner, bottomEnd = corner
    )
    return own to peer
}
