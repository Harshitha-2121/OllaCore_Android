package com.ollacore.app.data.util

import com.ollacore.app.data.model.InboxItem
import org.junit.Assert.*
import org.junit.Test

class UnreadCountsTest {

    private fun item(room: String, unread: Int) = InboxItem(
        roomId = room,
        kind = "direct",
        name = room,
        unreadCount = unread
    )

    @Test
    fun `case1 single chat with 2 unread badges 1`() {
        assertEquals(1, unreadConversationCount(listOf(item("alice", 2))))
    }

    @Test
    fun `case2 two chats badge 2 not sum`() {
        assertEquals(
            2,
            unreadConversationCount(listOf(item("alice", 2), item("bob", 3)))
        )
    }

    @Test
    fun `case3 more messages in same unread chat badge unchanged`() {
        val before = unreadConversationCount(listOf(item("alice", 1)))
        val after = unreadConversationCount(listOf(item("alice", 2)))
        assertEquals(1, before)
        assertEquals(1, after)
    }

    @Test
    fun `case4 reading clears that conversation from badge`() {
        assertEquals(1, unreadConversationCount(listOf(item("alice", 2))))
        assertEquals(0, unreadConversationCount(listOf(item("alice", 0))))
    }

    @Test
    fun `case5 opening read chat leaves badge unchanged`() {
        val state = listOf(item("alice", 2), item("bob", 0))
        assertEquals(1, unreadConversationCount(state))
    }

    @Test
    fun `case6 new message in read chat raises badge by 1`() {
        assertEquals(0, unreadConversationCount(listOf(item("alice", 0))))
        assertEquals(1, unreadConversationCount(listOf(item("alice", 0), item("bob", 1))))
    }

    @Test
    fun `spec example 3 chats 6 messages badges 3`() {
        assertEquals(
            3,
            unreadConversationCount(
                listOf(item("alice", 3), item("bob", 2), item("qa", 1))
            )
        )
    }

    @Test
    fun `duplicates counted once and archived excluded`() {
        assertEquals(
            1,
            unreadConversationCount(
                listOf(item("alice", 2), item("alice", 2)),
                archivedRooms = emptySet()
            )
        )
        assertEquals(
            0,
            unreadConversationCount(listOf(item("alice", 2)), setOf("alice"))
        )
    }
}
