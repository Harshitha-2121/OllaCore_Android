package com.ollacore.app.ui.chat

import com.ollacore.app.data.model.MessageResponse
import org.junit.Assert.*
import org.junit.Test

/**
 * BUG-02 regression suite: one user action / one server message must render as
 * exactly ONE row, across WS events, acks, and history resync.
 */
class MessageDedupeTest {

    private fun msg(
        id: String,
        seq: Int,
        clientId: String? = null,
        createdAt: String = "2026-01-01T00:00:00Z"
    ) = MessageResponse(
        id = id,
        roomId = "room-1",
        senderId = "u-sender",
        kind = "text",
        createdAt = createdAt,
        eventSeq = seq,
        clientMessageId = clientId
    )

    @Test
    fun `re-delivered frame replaces the row instead of appending a twin`() {
        val first = msg("m1", 10)
        val list = mergeIncomingMessage(listOf(first), msg("m1", 10))
        assertEquals(1, list.size)
        assertEquals("m1", list[0].id)
    }

    @Test
    fun `newer frame upgrades content in place and keeps list position`() {
        val a = msg("a", 5)
        val b = msg("b", 6)
        val editedB = msg("b", 9, createdAt = "2026-01-01T00:01:00Z")
        val list = mergeIncomingMessage(listOf(a, b), editedB)
        assertEquals(listOf("a", "b"), list.map { it.id })
        assertEquals("2026-01-01T00:01:00Z", list[1].createdAt)
    }

    @Test
    fun `delayed older frame never rolls back a newer row`() {
        val newer = msg("m1", 12, createdAt = "2026-01-01T00:02:00Z")
        val stale = msg("m1", 8, createdAt = "2026-01-01T00:00:00Z")
        val list = mergeIncomingMessage(listOf(newer), stale)
        assertEquals(1, list.size)
        assertEquals("2026-01-01T00:02:00Z", list[0].createdAt)
    }

    @Test
    fun `optimistic shell is claimed by the server echo sharing its client id`() {
        // Local row whose id is the client_message_id it was sent with.
        val optimistic = msg("local-42", 0, clientId = "local-42")
        val confirmed = msg("srv-900", 15, clientId = "local-42")
        val list = mergeIncomingMessage(listOf(optimistic), confirmed)
        assertEquals(1, list.size)
        assertEquals("srv-900", list[0].id)
        assertEquals(15, list[0].eventSeq)
    }

    @Test
    fun `server echo claims a stale optimistic row that already has the client id`() {
        val optimistic = msg("srv-old", 3, clientId = "local-7")
        val confirmed = msg("srv-new", 4, clientId = "local-7")
        val list = mergeIncomingMessage(listOf(optimistic), confirmed)
        assertEquals(1, list.size)
        assertEquals("srv-new", list[0].id)
    }

    @Test
    fun `identical text with different server ids is NOT deduplicated`() {
        // Legitimate repeated messages ("ok", "ok") must both survive: merging
        // is id-based, never text-based.
        val one = msg("m1", 1)
        val two = msg("m2", 2)
        val list = dedupeById(listOf(one, two, msg("m1", 1)))
        assertEquals(listOf("m1", "m2"), list.map { it.id })
    }

    @Test
    fun `history dedupe keeps first position and newest content`() {
        val early = msg("m1", 1, createdAt = "old")
        val late = msg("m1", 7, createdAt = "new")
        val other = msg("m2", 2, createdAt = "mid")
        val list = dedupeById(listOf(early, other, late))
        assertEquals(listOf("m1", "m2"), list.map { it.id })
        assertEquals("new", list[0].createdAt)
    }

    @Test
    fun `merge of an empty list appends`() {
        val list = mergeIncomingMessage(emptyList(), msg("m1", 1))
        assertEquals(1, list.size)
        assertEquals("m1", list[0].id)
    }
}
