package com.ollacore.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class BlockFilterTest {

    // Timestamps: block event at t=10_000.
    private val blockedAt = 10_000L
    private val before = 9_000L
    private val at = 10_000L
    private val after = 11_000L

    @Test
    fun `history before block stays visible`() {
        // "Hello" + "How are you?" from B before A blocks B.
        assertFalse(blockedMessageHidden(true, blockedAt, before))
    }

    @Test
    fun `own messages never hidden by someone elses block`() {
        // "Hi" from A: sender not blocked -> visible even after the block.
        assertFalse(blockedMessageHidden(false, blockedAt, after))
        assertFalse(blockedMessageHidden(false, null, after))
    }

    @Test
    fun `message at exact block instant is hidden`() {
        // Boundary (>=): a message stamped exactly at the block counts as new.
        assertTrue(blockedMessageHidden(true, blockedAt, at))
    }

    @Test
    fun `new message after block is hidden`() {
        // "Are you there?" from B after the block.
        assertTrue(blockedMessageHidden(true, blockedAt, after))
    }

    @Test
    fun `unblocked sender fully visible`() {
        assertFalse(blockedMessageHidden(false, blockedAt, before))
        assertFalse(blockedMessageHidden(false, null, null))
    }

    @Test
    fun `unknown timestamps fail closed while blocked`() {
        // Legacy block without stamp, or unparseable instant: hide.
        assertTrue(blockedMessageHidden(true, null, before))
        assertTrue(blockedMessageHidden(true, blockedAt, null))
        assertTrue(blockedMessageHidden(true, null, null))
    }

    @Test
    fun `block-unblock-reblock cycle uses latest cutoff`() {
        // Cycle: block@10k, unblock (clears), messages@12k visible (not blocked),
        // re-block@15k hides only 15k+ messages.
        assertFalse(blockedMessageHidden(false, null, 12_000L))
        assertFalse(blockedMessageHidden(true, 15_000L, 12_000L))
        assertTrue(blockedMessageHidden(true, 15_000L, 16_000L))
    }

    @Test
    fun `messageCreatedAtMs parses server instants`() {
        assertEquals(1_700_000_000_000L, messageCreatedAtMs("2023-11-14T22:13:20Z"))
        // Epoch seconds auto-scaled to millis.
        assertEquals(1_700_000_000_000L, messageCreatedAtMs("1700000000"))
        assertEquals(1_700_000_000_000L, messageCreatedAtMs("1700000000000"))
        assertNull(messageCreatedAtMs(null))
        assertNull(messageCreatedAtMs(""))
        assertNull(messageCreatedAtMs("not-a-time"))
    }
}
