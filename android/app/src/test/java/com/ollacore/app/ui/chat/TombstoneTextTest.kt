package com.ollacore.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class TombstoneTextTest {

    @Test
    fun `sender sees you phrasing`() {
        assertEquals("You deleted this message", tombstoneText(isOwn = true))
    }

    @Test
    fun `receiver sees this phrasing`() {
        assertEquals("This message was deleted", tombstoneText(isOwn = false))
    }

    @Test
    fun `phrasing never leaks content`() {
        // Contract: placeholder carries no body text in either direction.
        listOf(tombstoneText(true), tombstoneText(false)).forEach {
            assertFalse(it.contains("http"))
            assertTrue(it.contains("deleted"))
        }
    }
}
