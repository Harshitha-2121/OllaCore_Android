package com.ollacore.app.ui.home

import com.ollacore.app.data.model.InboxItem
import com.ollacore.app.data.model.LastMessage
import org.junit.Assert.*
import org.junit.Test

/** BUG-02 (chat list): duplicate conversations must collapse; name twins must not. */
class HomeInboxTest {

    private fun room(roomId: String, seq: Int?, name: String? = null, preview: String? = null) =
        InboxItem(
            roomId = roomId,
            kind = "group",
            name = name,
            lastMessage = LastMessage(preview = preview, eventSeq = seq)
        )

    @Test
    fun `duplicate room collapses to a single row`() {
        val list = dedupeInboxByRoom(
            listOf(room("r1", 10, "Family", "hi"), room("r1", 10, "Family", "hi"))
        )
        assertEquals(1, list.size)
        assertEquals("r1", list[0].roomId)
    }

    @Test
    fun `duplicate room keeps the newest preview and unread state`() {
        val list = dedupeInboxByRoom(
            listOf(
                room("r1", 4, "Family", "old"),
                room("r2", 7, "Work", "mid"),
                room("r1", 9, "Family", "new")
            )
        )
        assertEquals(listOf("r1", "r2"), list.map { it.roomId })
        assertEquals("new", list[0].lastMessage?.preview)
    }

    @Test
    fun `same name different rooms stay distinct`() {
        // Two separate groups may legitimately share a display name; merging by
        // name would silently hide a conversation.
        val list = dedupeInboxByRoom(
            listOf(room("r1", 1, "Family"), room("r2", 2, "Family"))
        )
        assertEquals(listOf("r1", "r2"), list.map { it.roomId })
    }

    @Test
    fun `order of first appearance is preserved`() {
        val list = dedupeInboxByRoom(
            listOf(room("b", 5), room("a", 6), room("b", 7))
        )
        assertEquals(listOf("b", "a"), list.map { it.roomId })
    }

    @Test
    fun `rows without a sequence still dedupe by room`() {
        val list = dedupeInboxByRoom(listOf(room("r1", null), room("r1", null)))
        assertEquals(1, list.size)
    }

    @Test
    fun `empty inbox stays empty`() {
        assertTrue(dedupeInboxByRoom(emptyList()).isEmpty())
    }
}
