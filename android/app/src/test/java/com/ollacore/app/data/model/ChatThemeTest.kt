package com.ollacore.app.data.model

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ollacore.app.ui.appearance.PromptHashGenerator
import org.junit.Assert.*
import org.junit.Test

class ChatThemeTest {

    @Test
    fun `catalog has unique ids and covers the reference families`() {
        val ids = ChatThemes.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(ids.size >= 12)
        assertTrue(ids.contains("default_dark"))
        assertTrue(ids.containsAll(listOf("dark_purple", "dark_blue", "nature_sky", "dark_leaves", "black_abstract")))
    }

    @Test
    fun `find falls back to default for null and unknown`() {
        assertEquals("default_dark", ChatThemes.find(null).id)
        assertEquals("default_dark", ChatThemes.find("nope").id)
        assertEquals("ocean_blue", ChatThemes.find("ocean_blue").id)
    }

    @Test
    fun `resolve without custom returns theme values`() {
        val s = resolveChatStyle(ChatThemes.default)
        assertEquals(ChatThemes.default.outgoingBubble, s.outgoingBubble)
        assertEquals(ChatThemes.default.incomingBubble, s.incomingBubble)
        assertEquals(ChatThemes.default.wallpaper, s.wallpaper)
    }

    @Test
    fun `resolve applies overrides and clamps opacity`() {
        val red = Color.Red
        val s = resolveChatStyle(
            ChatThemes.default,
            ChatCustom(outgoingBubble = red, opacity = 0.1f)
        )
        assertEquals(red.copy(alpha = 0.35f), s.outgoingBubble)
        val s2 = resolveChatStyle(ChatThemes.default, ChatCustom(opacity = 5f))
        assertEquals(1f, s2.outgoingBubble.alpha)
    }

    @Test
    fun `auto text keeps contrasting authored color else falls back`() {
        // White authored on white bubble -> unreadable -> dark fallback.
        assertEquals(Color(0xFF111111), pickTextOn(Color.White, Color.White))
        // White authored on dark bubble -> kept.
        assertEquals(Color.White, pickTextOn(Color(0xFF111111), Color.White))
    }

    @Test
    fun `forced text modes win over authored`() {
        val s = resolveChatStyle(
            ChatThemes.default, ChatCustom(textMode = ChatTextMode.DARK)
        )
        assertEquals(Color(0xFF111111), s.outgoingText)
        val s2 = resolveChatStyle(
            ChatThemes.default, ChatCustom(textMode = ChatTextMode.LIGHT)
        )
        assertEquals(Color.White, s2.incomingText)
    }

    @Test
    fun `bubble shapes keep tails on the right corners`() {
        val density = androidx.compose.ui.unit.Density(1f)
        val box = androidx.compose.ui.geometry.Size(1000f, 1000f)
        val (own, peer) = bubbleShapes(16.dp)
        assertEquals(4f, own.bottomEnd.toPx(box, density), 0.01f)
        assertEquals(16f, own.topStart.toPx(box, density), 0.01f)
        assertEquals(4f, peer.topStart.toPx(box, density), 0.01f)
        assertEquals(16f, peer.bottomEnd.toPx(box, density), 0.01f)
    }

    @Test
    fun `default follows app dark-light mode`() {
        assertEquals(ChatThemes.default, ChatThemes.defaultFor(true))
        val light = ChatThemes.defaultFor(false)
        assertEquals("default_light", light.id)
        // Light default: white incoming bubbles with dark text, same
        // scheme-aware Doodle wallpaper and brand-green outgoing.
        assertEquals(Color.White, light.incomingBubble)
        assertEquals(Color(0xFF1F2C34), light.incomingText)
        assertEquals(ChatThemes.default.outgoingBubble, light.outgoingBubble)
        assertEquals(ChatThemes.default.wallpaper, light.wallpaper)
        assertTrue(ChatThemes.isDefaultId("default_dark"))
        assertTrue(ChatThemes.isDefaultId("default_light"))
        assertFalse(ChatThemes.isDefaultId("pastel_purple"))
        assertFalse(ChatThemes.isDefaultId(null))
    }

    @Test
    fun `ai generator is deterministic and theme-shaped`() {
        val a = PromptHashGenerator.generate("calm ocean evening")
        val b = PromptHashGenerator.generate("calm ocean evening")
        assertEquals(a, b)
        assertEquals("ai_draft", a.id)
        assertTrue(a.outgoingBubble != a.incomingBubble)
    }
}
