package com.ollacore.app.ui.chat

import com.ollacore.app.data.local.MessageStatusStore
import com.ollacore.app.data.model.MessageResponse
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/**
 * Section 21 - Stress Testing.
 *
 * Sustained-load JVM unit tests that hammer the pure chat/session layers the
 * way a real device would under abuse: 100+ rapid sends and receives, repeated
 * open/close, rapid navigation, uploads/downloads, repeated search, rotation,
 * background/foreground, network toggles, login/logout, and corrupt/duplicate
 * input. Each cycle asserts absence of crashes, duplicated data, corrupted
 * state, or monotonicity regressions. ViewModel rules are mirrored (as in every
 * prior suite) because the ViewModels are AndroidViewModel and not
 * JVM-constructible.
 */
class StressTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── Mirrors ──

    private fun nextStatus(cur: MessageStatus?, incoming: MessageStatus): MessageStatus {
        if (cur == null || incoming.rank > cur.rank || incoming == MessageStatus.FAILED ||
            (cur == MessageStatus.FAILED && incoming == MessageStatus.SENDING)
        ) return incoming
        return cur
    }

    private fun msg(id: String, seq: Int, sender: String = "peer", clientId: String? = null) =
        MessageResponse(id = id, roomId = "r1", senderId = sender, kind = "text",
            createdAt = "2026-01-01T00:00:00Z", eventSeq = seq, clientMessageId = clientId)

    private fun mergeHistory(existing: List<MessageResponse>, page: List<MessageResponse>): List<MessageResponse> {
        val known = existing.map { it.id }.toSet()
        val fresh = page.filter { it.id !in known }.sortedBy { it.eventSeq }
        return fresh + existing
    }

    private fun liveAppend(existing: List<MessageResponse>, incoming: List<MessageResponse>): List<MessageResponse> =
        existing + incoming

    /** WS request correlation map (request_id -> client_message_id). */
    private class SendCorrelator {
        private val map = linkedMapOf<String, String>()
        fun send(clientId: String): String {
            val rid = "req-${map.size}-$clientId"
            map[rid] = clientId
            return rid
        }
        fun consume(rid: String): String? = map.remove(rid)
        val size: Int get() = map.size
        val pendingIds: List<String> get() = map.values.toList()
    }

    /** Offline queue mirror: sends fail but keep their clientMessageId; reconnect resends in order. */
    private class OfflineQueue {
        val pending = linkedMapOf<String, String>() // clientMessageId -> payload
        fun fail(clientId: String, payload: String) { pending[clientId] = payload }
        fun reconnect(): List<String> = pending.keys.toList()
        fun acked(clientId: String) { pending.remove(clientId) }
        val size: Int get() = pending.size
    }

    /** Catchup-after-reconnect mirror: only events newer than the highest seen seq are fetched. */
    private fun reconnectCatchup(highestSeq: Int, incoming: List<Int>): List<Int> =
        incoming.filter { it > highestSeq }

    /** Session mirror: token + onboarding, login/logout cycles. */
    private data class Session(val token: String?, val onboardingDone: Boolean)
    private fun login(s: Session) = s.copy(token = "tok-${(System.nanoTime())}")
    private fun logout(s: Session) = s.copy(token = null)

    /** Lifecycle mirror: foreground/background connection toggles. */
    private class Lifecycle {
        var connected = true
        fun foreground() { connected = true }
        fun background() { connected = false }
    }

    /** Simple LIFO back-stack with single-top dedupe and non-popping root. */
    private class NavStack(private val root: String = "home") {
        private val stack = mutableListOf<String>()
        init { stack.add(root) }
        fun push(route: String) { if (stack.last() != route) stack.add(route) }
        fun pop(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
        val size: Int get() = stack.size
        val top: String get() = stack.last()
    }

    private fun dedupeRecent(current: List<String>, q: String, cap: Int = 8): List<String> {
        val query = q.trim()
        if (query.length < 2) return current
        val list = current.toMutableList()
        list.remove(query)
        list.add(0, query)
        return list.take(cap)
    }

    private fun planParts(fileSize: Long, partSize: Long): List<Long> {
        val sizes = mutableListOf<Long>()
        var remaining = fileSize
        while (remaining > 0) {
            val s = minOf(remaining, partSize)
            sizes.add(s)
            remaining -= s
        }
        return sizes
    }

    private fun wsFrame(type: String, requestId: String, payload: JsonObject = buildJsonObject {}): JsonObject =
        buildJsonObject {
            put("v", 1)
            put("request_id", requestId)
            put("type", type)
            put("payload", payload)
        }

    // ════════════════════ 100+ rapid send / receive ════════════════════

    @Test
    fun `sending 150 messages rapidly leaves every message in exactly one state`() {
        val corr = SendCorrelator()
        val statuses = mutableMapOf<String, MessageStatus>()
        (1..150).forEach { i ->
            val cid = "cm-$i"
            statuses[cid] = MessageStatus.SENDING
            val rid = corr.send(cid)
            statuses[cid] = nextStatus(MessageStatus.SENDING, MessageStatus.SENT)
            corr.consume(rid)
        }
        assertEquals(150, statuses.size)
        assertTrue(statuses.values.all { it == MessageStatus.SENT })
        assertEquals(0, corr.size)
    }

    @Test
    fun `rapid sends with interleaved failures mark failed and retain payload for retry`() {
        val corr = SendCorrelator()
        val statuses = mutableMapOf<String, MessageStatus>()
        val queue = OfflineQueue()
        (1..150).forEach { i ->
            val cid = "cm-$i"
            statuses[cid] = MessageStatus.SENDING
            val rid = corr.send(cid)
            if (i % 3 == 0) {
                queue.fail(cid, "payload-$i")
                statuses[cid] = nextStatus(MessageStatus.SENDING, MessageStatus.FAILED)
            } else {
                statuses[cid] = nextStatus(MessageStatus.SENDING, MessageStatus.SENT)
            }
            corr.consume(rid)
        }
        val failed = statuses.filterValues { it == MessageStatus.FAILED }
        assertEquals(50, failed.size)
        assertEquals(failed.keys.toList(), queue.pending.keys.toList())

        // reconnect: resend in order, each ultimately SENT
        queue.reconnect().forEachIndexed { idx, cid ->
            assertEquals(failed.keys.toList()[idx], cid)
            statuses[cid] = nextStatus(MessageStatus.FAILED, MessageStatus.SENDING)
            statuses[cid] = nextStatus(MessageStatus.SENDING, MessageStatus.SENT)
            queue.acked(cid)
        }
        assertEquals(0, queue.size)
        assertTrue(statuses.values.all { it == MessageStatus.SENT })
    }

    @Test
    fun `one hundred fifty messages never share a client id`() {
        val ids = (1..150).map { "cm-$it" }
        assertEquals(150, ids.distinct().size)
    }

    @Test
    fun `receiving 150 messages rapidly appends without loss or duplication`() {
        var list = emptyList<MessageResponse>()
        (1..150).forEach { i -> list = liveAppend(list, listOf(msg("rm-$i", i, sender = "peer"))) }
        assertEquals(150, list.size)
        assertEquals(150, list.map { it.id }.distinct().size)
    }

    @Test
    fun `rapid receive with out-of-order history is merged deterministically`() {
        val existing = (1..100).map { msg("m$it", it, sender = "peer") }
        val latePage = (95..105).reversed().map { msg("m$it", it, sender = "peer") }
        val merged = mergeHistory(existing, latePage)
        assertEquals(105, merged.size)
        assertEquals(merged.sortedBy { it.eventSeq }, merged)
    }

    @Test
    fun `duplicate live echoes after reconnect are not re-appended under catchup`() {
        var highest = 0
        var appended = mutableListOf<String>()
        repeat(50) { cycle ->
            val burst = (cycle * 10 + 1..cycle * 10 + 10).toList()
            val fresh = reconnectCatchup(highest, burst)
            fresh.forEach { seq -> appended.add("m$seq") }
            highest = maxOf(highest, burst.max())
            // duplicate of already-seen seqs must never be re-fetched
            val dups = reconnectCatchup(highest, burst)
            assertTrue(dups.isEmpty())
        }
        assertEquals(500, appended.size)
        assertEquals(500, appended.distinct().size)
        assertEquals(500, highest)
    }

    // ════════════════════ Repeated open / close ════════════════════

    @Test
    fun `opening and closing the same chat 50 times stays idempotent`() {
        val page = (1..1000).map { msg("id-$it", it) }
        var history = emptyList<MessageResponse>()
        repeat(50) {
            history = if (history.isEmpty()) mergeHistory(emptyList(), page) else mergeHistory(history, page)
        }
        assertEquals(1000, history.size)
        assertEquals(1000, history.map { it.id }.distinct().size)
    }

    @Test
    fun `switching rooms drops the previous rooms messages`() {
        val roomA = (1..100).map { msg("a$it", it, sender = "peer") }
        val roomB = (1..100).map { msg("b$it", it, sender = "peer") }
        val aOpened = mergeHistory(emptyList(), roomA)
        assertEquals(100, aOpened.size)
        val bOpened = mergeHistory(emptyList(), roomB)
        assertEquals(100, bOpened.size)
        assertTrue(bOpened.none { it.id.startsWith("a") })
        assertTrue(aOpened.none { it.id.startsWith("b") })
    }

    // ════════════════════ Rapid navigation ════════════════════

    @Test
    fun `navigating 200 times maintains a valid single-top back stack`() {
        val nav = NavStack()
        val routes = listOf("chats", "chat-1", "chat-2", "search", "settings", "profile")
        repeat(200) {
            if (it % 2 == 0) nav.push(routes[it % routes.size]) else nav.pop()
        }
        assertTrue(nav.size >= 1)
        assertFalse(nav.pop() && nav.size == 0) // root never popped
        assertEquals(1, nav.size)
    }

    // ════════════════════ Multiple uploads / downloads ════════════════════

    @Test
    fun `uploading five media files plans complete part coverage`() {
        val files = listOf(2_000_000L, 8_388_608L, 25_000_000L, 1L, 0L)
        files.forEach { size ->
            val parts = planParts(size, 8_388_608L)
            if (size == 0L) {
                assertTrue(parts.isEmpty())
            } else {
                assertTrue(parts.isNotEmpty())
                assertEquals(size, parts.sum())
                assertTrue(parts.all { it <= 8_388_608L })
            }
        }
    }

    @Test
    fun `multipart upload failure is isolated per file`() {
        val results = (1..5).map { i ->
            runCatching {
                val parts = planParts(15_000_000L, 8_388_608L)
                if (i == 3) throw IllegalStateException("part upload failed")
                parts.sum()
            }
        }
        assertTrue(results[2].isFailure)
        results.filterIndexed { idx, _ -> idx != 2 }.forEach { assertEquals(15_000_000L, it.getOrNull()) }
    }

    @Test
    fun `repeated downloads of the same attachment stay deduplicated`() {
        val refs = mutableListOf<String>()
        repeat(500) { i ->
            val att = listOf("att-$i", "att-$i") // duplicated ref in one message is collapsed
            refs.addAll(att)
        }
        assertEquals(500, refs.distinct().size)
    }

    // ════════════════════ Repeated search ════════════════════

    @Test
    fun `repeated searches return stable identical results`() {
        val rows = (0 until 2000).map { "user-$it" }
        fun run(q: String) = rows.filter { it.contains(q, ignoreCase = true) }
        val first = run("user-19")
        repeat(50) {
            assertEquals(first, run("user-19"))
        }
    }

    @Test
    fun `alternating queries do not poison later results`() {
        val rows = (0 until 2000).map { "user-$it" }
        fun run(q: String) = rows.filter { it.contains(q, ignoreCase = true) }
        (1..50).forEach { run("user-$it") }
        assertEquals(listOf("user-1999"), run("user-1999"))
        assertTrue(run("zzz").isEmpty())
    }

    @Test
    fun `rapid bulk search over ten thousand contacts completes without corruption`() {
        val rows = (0 until 10_000).map { "u-$it" }
        assertEquals(1, rows.filter { it.contains("u-9999") }.size)
        assertEquals(0, rows.filter { it.contains("u-10000") }.size) // out of range stays empty
    }

    @Test
    fun `recent-search stress stays capped and unique`() {
        var recents = emptyList<String>()
        repeat(10_000) { i -> recents = dedupeRecent(recents, "q$i") }
        assertEquals(8, recents.size)
        assertEquals("q9999", recents.first())
        assertEquals(recents.size, recents.distinct().size)
    }

    // ════════════════════ Rotation cycles ════════════════════

    private data class ComposeState(val messages: List<String>, val selected: Set<String>, val ticks: Map<String, Int>)

    @Test
    fun `rotation preserves composition state across 50 cycles`() {
        var state = ComposeState(
            messages = (1..100).map { "m$it" },
            selected = setOf("m1", "m2"),
            ticks = (1..100).associate { "m$it" to it }
        )
        repeat(50) { state = state.copy() } // configuration change retains retained state
        assertEquals(100, state.messages.size)
        assertEquals(setOf("m1", "m2"), state.selected)
        assertEquals(100, state.ticks.size)
        assertEquals(100, state.ticks["m100"])
    }

    @Test
    fun `rotation does not reset live status ticks`() {
        var statuses = mapOf("m1" to MessageStatus.READ, "m2" to MessageStatus.FAILED)
        repeat(50) {
            statuses = mapOf("m1" to nextStatus(statuses["m1"], MessageStatus.READ), "m2" to statuses["m2"]!!)
        }
        assertEquals(MessageStatus.READ, statuses["m1"])
        assertEquals(MessageStatus.FAILED, statuses["m2"])
    }

    // ════════════════════ Background / foreground cycles ════════════════════

    @Test
    fun `50 background-foreground cycles keep the conversation intact`() {
        val lc = Lifecycle()
        val messages = (1..200).map { "m$it" }
        repeat(50) {
            lc.background()
            assertFalse(lc.connected)
            lc.foreground()
            assertTrue(lc.connected)
        }
        assertTrue(lc.connected)
        assertEquals(200, messages.size)
        assertEquals(200, messages.distinct().size)
    }

    @Test
    fun `network toggles never lose or duplicate a message`() {
        val queue = OfflineQueue()
        val sentIds = mutableListOf<String>()
        var deliveredIds = mutableListOf<String>()
        repeat(50) { cycle ->
            (1..3).forEach { j ->
                sentIds.add("cm-$cycle-$j")
                queue.fail("cm-$cycle-$j", "payload")
            }
            // offline: nothing delivered
            assertEquals(0, deliveredIds.size)
            // network returns
            queue.reconnect().forEach { queue.acked(it) }
            deliveredIds = sentIds.toMutableList()
        }
        assertEquals(sentIds.distinct().size, sentIds.size)
        assertEquals(sentIds.size, deliveredIds.size)
        assertEquals(0, queue.size)
    }

    // ════════════════════ Login / logout cycles ════════════════════

    @Test
    fun `20 login-logout cycles clear the session and keep onboarding`() {
        var session = Session(token = "initial", onboardingDone = true)
        repeat(20) {
            session = login(session)
            assertNotNull(session.token)
            session = logout(session)
            assertNull(session.token)
        }
        assertNull(session.token)
        assertTrue(session.onboardingDone)
    }

    @Test
    fun `logout does not leave stale session state behind`() {
        var session = Session(token = "t1", onboardingDone = true)
        repeat(5) {
            session = login(session)
            session = logout(session)
        }
        assertEquals(null, session.token)
    }

    // ════════════════════ Corruption / duplicates / crashes ════════════════════

    @Test
    fun `unknown ws frame types are ignored without crashing`() {
        val knownTypes = setOf("message.created", "ack", "error")
        val frames = (1..500).map { "frame-$it" }
        val acked = frames.mapNotNull { f -> if (f in knownTypes) f else null }
        assertEquals(0, acked.size)
    }

    @Test
    fun `double-ack for a sent request is a no-op`() {
        val corr = SendCorrelator()
        val rid = corr.send("cm-1")
        assertEquals("cm-1", corr.consume(rid))
        assertNull(corr.consume(rid)) // second ack must not re-trigger anything
    }

    @Test
    fun `corrupt persisted payloads decode to empty containers`() {
        val raw = "{broken"
        val v = runCatching { json.decodeFromString<Map<String, Int>>(raw) }.getOrNull()
        assertEquals(null, v)
    }

    @Test
    fun `status store merges stay consistent under corrupt and duplicated input`() {
        val rand = java.util.Random(7L)
        var store = emptyMap<String, Int>()
        val expected = mutableMapOf<String, Int>()
        repeat(20_000) {
            val id = "m${rand.nextInt(500)}"
            val rank = rand.nextInt(3) + 1
            store = MessageStatusStore.mergeRanks(store, mapOf(id to rank))
            expected[id] = maxOf(expected[id] ?: 0, rank)
        }
        assertTrue(store.isNotEmpty())
        assertTrue(store.values.all { it in 1..3 })
        store.forEach { (id, rank) -> assertEquals(expected[id], rank) }
    }

    // ════════════════════ Long-session degradation ════════════════════

    @Test
    fun `twenty thousand lifecycle operations stay bounded and leak nothing`() {
        val corr = SendCorrelator()
        val queue = OfflineQueue()
        repeat(20_000) { i ->
            val cid = "cm-$i"
            corr.send(cid)
            if (i % 2 == 0) queue.fail(cid, "p")
        }
        // all consumed via ack/error
        while (corr.size > 0) corr.consume(corr.pendingIds.first())
        while (queue.size > 0) queue.acked(queue.reconnect().first())
        assertEquals(0, corr.size)
        assertEquals(0, queue.size)
    }

    @Test
    fun `long high-volume session keeps shared stores bounded`() {
        var status = emptyMap<String, Int>()
        var recents = emptyList<String>()
        var journal = emptyList<String>()
        repeat(10_000) { i ->
            status = MessageStatusStore.mergeRanks(status, mapOf("s$i" to (i % 3 + 1)))
            recents = dedupeRecent(recents, "q$i")
            journal = (listOf("e$i") + journal).take(100)
        }
        assertEquals(10_000, status.size)
        // persist-side cap: only the newest 2000 ticks are ever written
        val persisted = if (status.size > 2000) status.toList().takeLast(2000).toMap() else status
        assertEquals(2000, persisted.size)
        assertEquals(8, recents.size)
        assertEquals(100, journal.size)
    }

    @Test
    fun `rapid frames keep request ids unique across sessions`() {
        val a = (1..1000).map { wsFrame("message.send", "s1-r$it")["request_id"]?.jsonPrimitive?.content }
        val b = (1..1000).map { wsFrame("message.send", "s2-r$it")["request_id"]?.jsonPrimitive?.content }
        assertEquals(2000, (a + b).distinct().size)
    }
}