package com.ollacore.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

/**
 * Selection-toolbar semantics (top-right Forward / Star in selection mode).
 *
 * Bug report: tapping Forward or Star with messages selected appears to do
 * nothing. Root causes under test:
 *  1. Star used a per-item toggle loop: on a mixed selection (one starred,
 *     one not) it just swaps the mix — the header count stays "2", the
 *     visible set looks unchanged, and two concurrent DataStore
 *     read-modify-writes can clobber each other.
 *  2. Forward had no feedback and no selection clear; on a dead socket the
 *     send fails against an invisible status key, so literally nothing
 *     happens on screen.
 */
class MessageActionsTest {

    @Test
    fun `old per-item toggle loop leaves mixed state - bug reproduced`() {
        // Simulates the pre-fix toolbar handler:
        //   uiState.selectedIds.forEach { onToggleStar(it) }
        val starred = mutableSetOf("a") // "a" starred, "b" not (as in the screenshot)
        listOf("a", "b").forEach { id ->
            if (id in starred) starred.remove(id) else starred.add(id)
        }
        // Mixed in, mixed out: "a" got unstarred, "b" got starred.
        // Net visible change ~ zero, which is exactly the "star not working" report.
        assertEquals(setOf("b"), starred)
    }

    @Test
    fun `batch star on mixed selection stars all`() {
        assertTrue(resolveBatchStarAction(listOf("a", "b"), setOf("a")))
    }

    @Test
    fun `batch star when all selected already starred unstars all`() {
        assertFalse(resolveBatchStarAction(listOf("a", "b"), setOf("a", "b")))
    }

    @Test
    fun `batch star on none starred stars all`() {
        assertTrue(resolveBatchStarAction(listOf("a"), emptySet()))
    }

    @Test
    fun `batch star on empty selection resolves to unstar`() {
        assertFalse(resolveBatchStarAction(emptyList(), setOf("a")))
    }

    @Test
    fun `forward body gets single Forwarded prefix`() {
        assertEquals("[Forwarded] hello", buildForwardBody("hello", "text", null))
    }

    @Test
    fun `forward body does not double prefix`() {
        assertEquals("[Forwarded] hello", buildForwardBody("[Forwarded] hello", "text", null))
    }

    @Test
    fun `forward body falls back to media label`() {
        assertEquals("[Forwarded] photo.jpg", buildForwardBody("", "image", "photo.jpg"))
        assertEquals("[Forwarded] 📷 Photo", buildForwardBody("", "image", null))
        assertEquals("[Forwarded] 🎵 Voice message", buildForwardBody("", "voice_note", null))
    }
}
