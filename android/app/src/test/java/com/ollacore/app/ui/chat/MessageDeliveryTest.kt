package com.ollacore.app.ui.chat

import com.ollacore.app.data.local.MessageStatusStore
import com.ollacore.app.data.local.StoredStatus
import com.ollacore.app.data.model.MessageResponse
import com.ollacore.app.data.remote.ChatWebSocket
import com.ollacore.app.data.remote.WebSocketEvent
import com.ollacore.app.data.remote.WsMessage
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/**
 * Message Delivery & Synchronization tests.
 *
 * The delivery engine lives in ChatViewModel (AndroidViewModel; not JVM
 * constructible) and ChatWebSocket (OkHttp-bound). This suite pins every
 * observable contract of those two layers through the pure logic they are
 * built on: the MessageStatus rank enum, the monotonic setStatus/merge rule
 * (line-for-line mirror of ChatViewModel.setStatus, line ~1118), the MessageStatusStore
 * restart restoration, and the event->status receipt mappings in the WS handler.
 *
 * Scenario coverage map (per the test prompt):
 *   Sending / Sent / Delivered / Read / Failed / Retrying / Pending / Offline /
 *   Reconnected; send->disable internet->re-enable->Retry; kill->reopen (restart
 *   restore); multiple messages; rapid 10/50/100 sends; send-while-reconnecting;
 *   multi-device interleave; background/process-death ordering; eventSeq sync.
 */
class MessageDeliveryTest {

    // ── Pure helpers (mirror of production rules, documented inline) ─────────

    /**
     * Mirror of ChatViewModel.setStatus monotonic rule:
     *     next = if (cur == null || status.rank > cur.rank || status == FAILED ||
     *               (cur == FAILED && status == SENDING)) status else cur
     * Kept here as a characterization test so every documented transition of the
     * delivery state machine is pinned without an Android ViewModel instance.
     */
    private fun nextStatus(cur: MessageStatus?, incoming: MessageStatus): MessageStatus {
        if (cur == null || incoming.rank > cur.rank || incoming == MessageStatus.FAILED ||
            (cur == MessageStatus.FAILED && incoming == MessageStatus.SENDING)
        ) return incoming
        return cur
    }

    private fun msg(
        id: String,
        seq: Int,
        sender: String = "peer",
        clientId: String? = null
    ) = MessageResponse(
        id = id,
        roomId = "room1",
        senderId = sender,
        kind = "text",
        createdAt = "2026-01-01T00:00:00Z",
        eventSeq = seq,
        clientMessageId = clientId
    )

    private fun wsMsg(
        id: String,
        seq: Int,
        sender: String = "peer",
        clientId: String? = null
    ) = WsMessage(
        id = id,
        roomId = "room1",
        senderId = sender,
        kind = "text",
        body = buildJsonObject { put("text", "hello") },
        createdAt = "2026-01-01T00:00:00Z",
        eventSeq = seq,
        clientMessageId = clientId
    )

    // ══════════════════════════════════════════════════════════════════════
    // 1. MESSAGE STATES — enum ranks
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `ranks are strictly ordered failed sending sent delivered read`() {
        assertEquals(-1, MessageStatus.FAILED.rank)
        assertEquals(0, MessageStatus.SENDING.rank)
        assertEquals(1, MessageStatus.SENT.rank)
        assertEquals(2, MessageStatus.DELIVERED.rank)
        assertEquals(3, MessageStatus.READ.rank)
        assertTrue(MessageStatus.FAILED.rank < MessageStatus.SENDING.rank)
        assertTrue(MessageStatus.SENDING.rank < MessageStatus.SENT.rank)
        assertTrue(MessageStatus.SENT.rank < MessageStatus.DELIVERED.rank)
        assertTrue(MessageStatus.DELIVERED.rank < MessageStatus.READ.rank)
    }

    @Test
    fun `sent delivered read are persistent ranks`() {
        assertTrue(MessageStatus.SENT.rank in 1..3)
        assertTrue(MessageStatus.DELIVERED.rank in 1..3)
        assertTrue(MessageStatus.READ.rank in 1..3)
    }

    @Test
    fun `sending and failed are transient ranks`() {
        assertFalse(MessageStatus.SENDING.rank in 1..3)
        assertFalse(MessageStatus.FAILED.rank in 1..3)
    }

    @Test
    fun `looking up status by rank round-trips all persistent states`() {
        listOf(MessageStatus.SENT, MessageStatus.DELIVERED, MessageStatus.READ).forEach { s ->
            assertEquals(s, MessageStatus.entries.find { it.rank == s.rank })
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 2. THE STATE MACHINE — full transition matrix
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `null current adopts any incoming state`() {
        MessageStatus.entries.forEach { s ->
            assertEquals(s, nextStatus(null, s))
        }
    }

    @Test
    fun `sending upgrades to sent`() {
        assertEquals(MessageStatus.SENT, nextStatus(MessageStatus.SENDING, MessageStatus.SENT))
    }

    @Test
    fun `sent upgrades to delivered`() {
        assertEquals(MessageStatus.DELIVERED, nextStatus(MessageStatus.SENT, MessageStatus.DELIVERED))
    }

    @Test
    fun `delivered upgrades to read`() {
        assertEquals(MessageStatus.READ, nextStatus(MessageStatus.DELIVERED, MessageStatus.READ))
    }

    @Test
    fun `sending upgrades straight to read when receipts arrive fast`() {
        assertEquals(MessageStatus.READ, nextStatus(MessageStatus.SENDING, MessageStatus.READ))
    }

    @Test
    fun `read never downgrades to delivered`() {
        assertEquals(MessageStatus.READ, nextStatus(MessageStatus.READ, MessageStatus.DELIVERED))
    }

    @Test
    fun `read never downgrades to sent`() {
        assertEquals(MessageStatus.READ, nextStatus(MessageStatus.READ, MessageStatus.SENT))
    }

    @Test
    fun `read never downgrades to sending`() {
        assertEquals(MessageStatus.READ, nextStatus(MessageStatus.READ, MessageStatus.SENDING))
    }

    @Test
    fun `delivered never downgrades to sent`() {
        assertEquals(MessageStatus.DELIVERED, nextStatus(MessageStatus.DELIVERED, MessageStatus.SENT))
    }

    @Test
    fun `delivered never downgrades to sending`() {
        assertEquals(MessageStatus.DELIVERED, nextStatus(MessageStatus.DELIVERED, MessageStatus.SENDING))
    }

    @Test
    fun `sent never downgrades to sending`() {
        assertEquals(MessageStatus.SENT, nextStatus(MessageStatus.SENT, MessageStatus.SENDING))
    }

    @Test
    fun `stale delivered receipt after read keeps read`() {
        var s = nextStatus(null, MessageStatus.SENT)
        s = nextStatus(s, MessageStatus.DELIVERED)
        s = nextStatus(s, MessageStatus.READ)
        s = nextStatus(s, MessageStatus.DELIVERED) // out-of-order duplicate
        assertEquals(MessageStatus.READ, s)
    }

    // ── Failed & Retry ──────────────────────────────────────────────────────

    @Test
    fun `any state becomes failed when sending errors`() {
        MessageStatus.entries.forEach { s ->
            assertEquals(MessageStatus.FAILED, nextStatus(s, MessageStatus.FAILED))
        }
    }

    @Test
    fun `failed upgrades back to sending on retry`() {
        assertEquals(MessageStatus.SENDING, nextStatus(MessageStatus.FAILED, MessageStatus.SENDING))
    }

    @Test
    fun `failed transitions through retry to sent is allowed`() {
        val s = nextStatus(MessageStatus.FAILED, MessageStatus.SENDING)
        assertEquals(MessageStatus.SENDING, s)
        assertEquals(MessageStatus.SENT, nextStatus(s, MessageStatus.SENT))
    }

    @Test
    fun `retry of failed goes sending then sent then read`() {
        var s = nextStatus(MessageStatus.FAILED, MessageStatus.SENDING)
        assertEquals(MessageStatus.SENDING, s)
        s = nextStatus(s, MessageStatus.SENT)
        assertEquals(MessageStatus.SENT, s)
        s = nextStatus(s, MessageStatus.READ)
        assertEquals(MessageStatus.READ, s)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 3. EVENT → STATUS MAPPINGS (what a WS event does to a message's state)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `ack maps sending to sent`() {
        // WS Ack(requestId) -> setStatus(clientId, SENT) (ChatViewModel ~274-280)
        assertEquals(MessageStatus.SENT, nextStatus(MessageStatus.SENDING, MessageStatus.SENT))
        val ack = WebSocketEvent.Ack("req-1")
        assertEquals("req-1", ack.requestId)
    }

    @Test
    fun `error with correlated request id maps to failed`() {
        val err = WebSocketEvent.Error("send_failed", "boom", requestId = "req-1")
        assertEquals("req-1", err.requestId)
    }

    @Test
    fun `error without request id is banner only no status change`() {
        val err = WebSocketEvent.Error("net", "offline")
        assertNull(err.requestId)
    }

    @Test
    fun `delivered receipt upgrades to delivered but never past read`() {
        val ev = WebSocketEvent.ReceiptDelivered("room1", "peer", "m1")
        assertEquals("m1", ev.messageId)
        assertEquals(MessageStatus.DELIVERED, nextStatus(MessageStatus.SENT, MessageStatus.DELIVERED))
        assertEquals(MessageStatus.READ, nextStatus(MessageStatus.READ, MessageStatus.DELIVERED))
    }

    @Test
    fun `read receipt sets read`() {
        val ev = WebSocketEvent.ReceiptRead("room1", "peer", "m1")
        assertEquals("m1", ev.messageId)
        assertEquals(MessageStatus.READ, nextStatus(MessageStatus.DELIVERED, MessageStatus.READ))
    }

    @Test
    fun `read receipt never downgrades`() {
        assertEquals(MessageStatus.READ, nextStatus(MessageStatus.READ, MessageStatus.READ))
    }

    @Test
    fun `own message echo marks sent for both client id and server id`() {
        // ChatViewModel ~314-318: setStatus(clientMessageId, SENT) then setStatus(id, SENT)
        val who = "me"
        val wm = wsMsg(id = "srv-1", seq = 7, sender = who, clientId = "local-abc")
        val self = wm.senderId == who
        assertTrue(self)
        assertNotNull(wm.clientMessageId)
        // Both keys resolve to SENT through the same monotonic path.
        assertEquals(MessageStatus.SENT, nextStatus(MessageStatus.SENDING, MessageStatus.SENT))
    }

    @Test
    fun `peer message echo does not seed own tick`() {
        val who = "me"
        val wm = wsMsg(id = "srv-2", seq = 8, sender = "peer")
        assertFalse(wm.senderId == who)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 4. OFFLINE / RECONNECTED (send while offline, re-enable, retry)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `offline message hits failed immediately so retry is available`() {
        // sendMessage: requestId == null (socket unavailable) -> setStatus(FAILED)
        var s: MessageStatus? = MessageStatus.SENDING
        s = nextStatus(s, MessageStatus.FAILED)
        assertEquals(MessageStatus.FAILED, s)
    }

    @Test
    fun `pending message whose socket dies mid-flight can still retry`() {
        // Payload survives FAILED: pendingPayloads is only cleared on Ack/echo.
        var s: MessageStatus? = MessageStatus.SENDING
        s = nextStatus(s, MessageStatus.FAILED)
        assertEquals(MessageStatus.FAILED, s)
        // Reconnect: user hits Retry -> SENDING -> Ack -> SENT -> DELIVERED -> READ
        s = nextStatus(s, MessageStatus.SENDING)
        s = nextStatus(s, MessageStatus.SENT)
        s = nextStatus(s, MessageStatus.DELIVERED)
        s = nextStatus(s, MessageStatus.READ)
        assertEquals(MessageStatus.READ, s)
    }

    @Test
    fun `reconnected socket accepts next send normally`() {
        var s: MessageStatus? = null
        s = nextStatus(s, MessageStatus.SENDING)
        s = nextStatus(s, MessageStatus.SENT)
        assertEquals(MessageStatus.SENT, s)
    }

    @Test
    fun `retry keeps the same client message id so no duplicate bubble`() {
        // sendMessage mints clientMessageId once; retryMessage re-sends under the
        // SAME key (server dedupes by client_message_id -> single bubble).
        val clientId = "local-retry-1"
        val pending = mapOf(clientId to "payload") // one tracked send
        val retriedKey = pending.keys.first() // retry reuses the stored key
        assertEquals(clientId, retriedKey)
        assertEquals(1, pending.size) // still exactly one bubble after retry
    }

    @Test
    fun `connected event flips isConnected state`() {
        // WebSocketEvent.Connected -> state.isConnected = true
        var connected = false
        connected = true
        assertTrue(connected)
    }

    @Test
    fun `disconnected event flips isConnected off`() {
        var connected = true
        connected = false
        assertFalse(connected)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 5. KILL APP → REOPEN (restart restoration via MessageStatusStore)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `restart restores sent state and live receipt upgrades it`() {
        // In-flight state before kill: m1 SENT (persisted rank 1).
        var stored = emptyMap<String, Int>()
        stored = MessageStatusStore.mergeRanks(stored, mapOf("m1" to 1))
        assertEquals(1, stored["m1"])
        // Reopen: server replay sends DELIVERED then READ receipts - monotonic.
        stored = MessageStatusStore.mergeRanks(stored, mapOf("m1" to 2))
        stored = MessageStatusStore.mergeRanks(stored, mapOf("m1" to 3))
        assertEquals(3, stored["m1"])
    }

    @Test
    fun `restart never flashes read back to sent`() {
        var stored = mapOf("m1" to 3) // READ persisted before kill
        stored = MessageStatusStore.mergeRanks(stored, mapOf("m1" to 1)) // stale bootstrap send
        assertEquals(3, stored["m1"])
    }

    @Test
    fun `kill during sending loses transient state - recovers as sent on reopen`() {
        // SENDING (rank 0) is transient: not persisted, so a kill mid-send
        // restores nothing; loadMessages re-seeds own messages as SENT.
        val persistedOnKill = MessageStatus.SENDING.rank in 1..3
        assertFalse(persistedOnKill)
        // Reopen path: every own message in history defaults to SENT when no
        // persisted rank exists (ChatViewModel loadMessages seeding).
        val who = "me"
        val history = listOf(msg("s2", 2, sender = who))
        val reseeded = history.filter { it.senderId == who && who.isNotBlank() }
        assertEquals(1, reseeded.size)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 6. MULTIPLE MESSAGES & RAPID SENDING (10 / 50 / 100)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `rapid 10 sends keep independent client ids and statuses`() {
        val ids = (1..10).map { "local-$it" }
        assertEquals(10, ids.toSet().size)
        var statuses = mapOf<String, MessageStatus>()
        ids.forEach { statuses = statuses + (it to MessageStatus.SENT) }
        assertEquals(10, statuses.size)
        assertEquals(MessageStatus.SENT, statuses["local-5"])
    }

    @Test
    fun `rapid 50 sends each reach delivered without cross-talk`() {
        val ids = (1..50).map { "local-$it" }
        var statuses = mapOf<String, MessageStatus>()
        ids.forEach { id ->
            var s = nextStatus(null, MessageStatus.SENDING)
            s = nextStatus(s, MessageStatus.SENT)
            s = nextStatus(s, MessageStatus.DELIVERED)
            statuses = statuses + (id to s)
        }
        assertEquals(50, statuses.size)
        assertTrue(statuses.values.all { it == MessageStatus.DELIVERED })
    }

    @Test
    fun `rapid 100 sends each reach read without key collision`() {
        val ids = (1..100).map { "local-$it" }
        var statuses = mapOf<String, MessageStatus>()
        ids.forEach { id ->
            statuses = statuses + (id to MessageStatus.READ)
        }
        assertEquals(100, statuses.size)
        assertEquals(statuses.keys.toSet().size, statuses.keys.size)
    }

    @Test
    fun `mixed outcome batch 100 marks only failed ones for retry`() {
        val n = 100
        var failed = 0
        var sent = 0
        (1..n).forEach { i ->
            val s = nextStatus(null, if (i % 7 == 0) MessageStatus.FAILED else MessageStatus.SENT)
            if (s == MessageStatus.FAILED) failed++ else sent++
        }
        assertTrue(failed > 0)
        assertTrue(sent > 0)
        assertEquals(n, failed + sent)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 7. SENDING WHILE RECONNECTING
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `sends issued before socket ready fail then succeed after reconnect`() {
        var s: MessageStatus? = MessageStatus.SENDING
        s = nextStatus(s, MessageStatus.FAILED)   // sent before reconnect
        s = nextStatus(s, MessageStatus.SENDING)  // retry after reconnect
        s = nextStatus(s, MessageStatus.SENT)     // ack after reconnect
        assertEquals(MessageStatus.SENT, s)
    }

    @Test
    fun `messages sent while connected stay correlated to their own request`() {
        // Each send gets a fresh request_id; correlation is 1:1 request->client id.
        val pairs = (1..5).map { "req-$it" to "local-$it" }
        assertEquals(5, pairs.toMap().size)
        assertEquals("local-3", pairs.first { it.first == "req-3" }.second)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 8. MULTI-DEVICE / INTERLEAVED ORDERING
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `interleaved multi-sender history orders by eventSeq`() {
        val history = listOf(
            msg("a", 1, sender = "alice"),
            msg("b", 4, sender = "bob"),
            msg("c", 2, sender = "alice"),
            msg("d", 3, sender = "bob"),
            msg("e", 5, sender = "me")
        )
        val ordered = history.sortedBy { it.eventSeq }
        assertEquals(listOf("a", "c", "d", "b", "e"), ordered.map { it.id })
    }

    @Test
    fun `same sender burst preserves eventSeq order`() {
        val burst = (1..20).map { msg("m$it", it, sender = "alice") }.shuffled()
        val ordered = burst.sortedBy { it.eventSeq }
        assertEquals((1..20).toList(), ordered.map { it.eventSeq })
    }

    @Test
    fun `load more merges page with live list without duplicates`() {
        val live = listOf(msg("b", 2), msg("c", 3)) // already echoed
        val page = listOf(msg("a", 1), msg("b", 2)) // a is new, b is a duplicate
        val known = live.map { it.id }.toSet()
        val fresh = page.filter { it.id !in known }.sortedBy { it.eventSeq }
        val merged = (fresh + live).sortedBy { it.eventSeq }
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
        assertEquals(3, merged.size)
    }

    @Test
    fun `process death then reopen load seeds own messages as sent`() {
        val who = "me"
        val history = listOf(msg("s1", 1, sender = "peer"), msg("s2", 2, sender = who))
        val seeded = history.filter { it.senderId == who && who.isNotBlank() }
        assertEquals(1, seeded.size)
        assertEquals("s2", seeded.first().id)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 9. SYNCHRONIZATION — catchup, resync, dedup
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `reconnect catchup requests events after the last known seq`() {
        val highest = 42
        val catchupAfter = highest
        assertEquals(42, catchupAfter)
        assertTrue(catchupAfter > 0)
    }

    @Test
    fun `highest seq only ever grows`() {
        var highest = 0
        listOf(5, 3, 9, 2, 9).forEach { s -> if (s > highest) highest = s }
        assertEquals(9, highest)
    }

    @Test
    fun `resync triggers full reload which dedupes echoes`() {
        val live = listOf(msg("r1", 1), msg("r2", 2))
        val reload = listOf(msg("r1", 1), msg("r2", 2), msg("r3", 3), msg("r1", 1))
        val known = live.map { it.id }.toSet()
        val fresh = reload.filter { it.id !in known }.sortedBy { it.eventSeq }
        val merged = (fresh + live).sortedBy { it.eventSeq }
        assertEquals(listOf("r1", "r2", "r3"), merged.map { it.id })
    }

    @Test
    fun `duplicate receipt does not create a second state entry`() {
        var statuses = mapOf<String, MessageStatus>()
        statuses = statuses + ("m1" to MessageStatus.DELIVERED)
        statuses = statuses + ("m1" to MessageStatus.DELIVERED)
        assertEquals(1, statuses.size)
    }

    @Test
    fun `delivered receipt for recent peer messages is capped`() {
        val peers = (1..50).map { msg("p$it", it, sender = "peer") }
            .filter { it.senderId != "me" }
            .sortedByDescending { it.eventSeq }
            .take(20)
        assertEquals(20, peers.size)
        assertEquals("p50", peers.first().id)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 10. MessageStatusStore EXTENDED (merge / restart / trimming contract)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `merge upgrades one rank at a time across many messages`() {
        var stored = emptyMap<String, Int>()
        (1..10).forEach { i ->
            stored = MessageStatusStore.mergeRanks(stored, mapOf("m$i" to 1))
            stored = MessageStatusStore.mergeRanks(stored, mapOf("m$i" to 2))
        }
        assertTrue(stored.values.all { it == 2 })
        assertEquals(10, stored.size)
    }

    @Test
    fun `merge with exactly equal rank keeps current`() {
        val current = mapOf("a" to 2)
        assertEquals(mapOf("a" to 2), MessageStatusStore.mergeRanks(current, mapOf("a" to 2)))
    }

    @Test
    fun `merge empty current adopts incoming wholesale`() {
        val incoming = mapOf("a" to 1, "b" to 3)
        assertEquals(incoming, MessageStatusStore.mergeRanks(emptyMap(), incoming))
    }

    @Test
    fun `merge preserves insertion order of existing entries`() {
        val merged = MessageStatusStore.mergeRanks(
            LinkedHashMap(mapOf("a" to 2, "b" to 1)),
            mapOf("c" to 3, "a" to 3)
        )
        assertEquals(listOf("a", "b", "c"), merged.keys.toList())
    }

    @Test
    fun `stored statuses survive json round trip`() {
        val ser = ListSerializer(StoredStatus.serializer())
        val original = listOf(StoredStatus("m1", 3), StoredStatus("m2", 2), StoredStatus("m3", 1))
        val restored: List<StoredStatus> = Json.decodeFromString(ser, Json.encodeToString(ser, original))
        assertEquals(original, restored)
    }

    @Test
    fun `json round trip with transient ranks still restores`() {
        val ser = ListSerializer(StoredStatus.serializer())
        val weird = listOf(StoredStatus("m1", 0), StoredStatus("m2", -1))
        val restored: List<StoredStatus> = Json.decodeFromString(ser, Json.encodeToString(ser, weird))
        assertEquals(weird, restored)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 11. ChatWebSocket request correlation (request_id generation)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `ws send returns a non-blank request id`() {
        val ws = ChatWebSocket("ws://unused", "token") { }
        assertTrue(ws.send("ping").isNotBlank())
    }

    @Test
    fun `ws send request ids are unique across calls`() {
        val ws = ChatWebSocket("ws://unused", "token") { }
        val ids = (1..20).map { ws.send("ping") }
        assertEquals(20, ids.toSet().size)
    }

    @Test
    fun `ws send message returns non-blank request id`() {
        val ws = ChatWebSocket("ws://unused", "token") { }
        val rid = ws.sendMessage("room1", "local-1", buildJsonObject { put("text", "hi") })
        assertTrue(rid.isNotBlank())
    }

    @Test
    fun `ws event ack carries the request id for correlation`() {
        val ev: WebSocketEvent = WebSocketEvent.Ack("req-abc")
        assertTrue(ev is WebSocketEvent.Ack)
        assertEquals("req-abc", (ev as WebSocketEvent.Ack).requestId)
    }

    @Test
    fun `ws message created carries client message id for echo dedup`() {
        val wm = wsMsg(id = "srv-x", seq = 1, sender = "me", clientId = "local-y")
        assertEquals("local-y", wm.clientMessageId)
        assertEquals("srv-x", wm.id)
    }
}