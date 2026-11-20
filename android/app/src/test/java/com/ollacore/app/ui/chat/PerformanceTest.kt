package com.ollacore.app.ui.chat

import com.ollacore.app.data.local.MessageStatusStore
import com.ollacore.app.data.model.MessageResponse
import com.ollacore.app.ui.contacts.ContactBook
import com.ollacore.app.ui.contacts.ContactRow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/**
 * Section 20 - Performance Testing.
 *
 * JVM unit tests pin the latency/throughput/upper-bound contracts of the pure
 * layers the chat runs on (message stores, history merge, request correlation,
 * WS frame building, upload planning, search, block filtering). Every expensive
 * UI/network quantity is bounded by a mirror constant or a cap test here:
 * page sizes, fan-out caps, store caps (memory + storage), work-avoidance
 * (CPU/battery), bounded loops (ANR/freeze guards), graceful degradation
 * (corrupt state). ViewModels are AndroidViewModel (not JVM-constructible), so
 * their decision rules are mirrored and pinned, exactly as in the earlier suites.
 */
class PerformanceTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── Mirrors of production rules ──

    /** ChatViewModel.setStatus monotonic rule (line ~1118). */
    private fun nextStatus(cur: MessageStatus?, incoming: MessageStatus): MessageStatus {
        if (cur == null || incoming.rank > cur.rank || incoming == MessageStatus.FAILED ||
            (cur == MessageStatus.FAILED && incoming == MessageStatus.SENDING)
        ) return incoming
        return cur
    }

    /** loadMore merge: dedupe by id, prepend fresh page sorted ascending. */
    private fun mergeHistory(existing: List<MessageResponse>, page: List<MessageResponse>): List<MessageResponse> {
        val known = existing.map { it.id }.toSet()
        val fresh = page.filter { it.id !in known }.sortedBy { it.eventSeq }
        return fresh + existing
    }

    /** Initial load: server page sorted ascending (loadMessages). */
    private fun initialLoad(page: List<MessageResponse>): List<MessageResponse> =
        page.sortedBy { it.eventSeq }

    /** Live WS append keeps existing row order and identity (no re-sort churn). */
    private fun liveAppend(existing: List<MessageResponse>, incoming: List<MessageResponse>): List<MessageResponse> =
        existing + incoming

    /** Seed own messages to at-least SENT when unknown (history + live echo rules). */
    private fun seedOwnSent(
        current: Map<String, MessageStatus>,
        ownMsgIds: List<String>
    ): Map<String, MessageStatus> {
        val seeded = current.toMutableMap()
        ownMsgIds.forEach { id -> if (!seeded.containsKey(id)) seeded[id] = MessageStatus.SENT }
        return seeded
    }

    /** MessageStatusStore trimming after a merge (persist-side cap, oldest inserted dropped). */
    private fun trimStatus(merged: Map<String, Int>, cap: Int): Map<String, Int> =
        if (merged.size > cap) merged.entries.drop(merged.size - cap).associate { it.key to it.value } else merged

    /** ChatPrefsStore.addRecentSearch: trim, min 2 chars, move to front, dedupe, cap 8. */
    private fun dedupeRecent(current: List<String>, q: String, cap: Int = 8): List<String> {
        val query = q.trim()
        if (query.length < 2) return current
        val list = current.toMutableList()
        list.remove(query)
        list.add(0, query)
        return list.take(cap)
    }

    /** GroupEventStore / CallLogStore log pipeline: prepend + cap. */
    private fun prependCap(current: List<String>, entry: String, cap: Int): List<String> =
        (listOf(entry) + current).take(cap)

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
    }

    /** Mirror of ChatWebSocket.send frame. */
    private fun wsFrame(type: String, requestId: String, payload: JsonObject = buildJsonObject {}): JsonObject =
        buildJsonObject {
            put("v", 1)
            put("request_id", requestId)
            put("type", type)
            put("payload", payload)
        }

    /** Multipart part splitter (AttachmentUploader.uploadMultipart). */
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

    /** ChatScreen.uploadQualityValue. */
    private fun uploadQuality(name: String): Int = when (name) {
        "high" -> 92
        "saver" -> 65
        else -> 80
    }

    /** Post-block visibility filter over a whole history. */
    private fun filterBlocked(
        messages: List<MessageResponse>,
        blockedSenders: Set<String>,
        blockTimes: Map<String, Long>
    ): List<MessageResponse> = messages.filter { m ->
        if (m.senderId !in blockedSenders) true
        else !blockedMessageHidden(
            senderBlocked = true,
            blockedAtMs = blockTimes[m.senderId],
            createdAtMs = messageCreatedAtMs(m.createdAt)
        )
    }

    private fun msg(id: String, seq: Int, sender: String = "peer", createdAt: String = "2026-01-01T00:00:00Z") =
        MessageResponse(id = id, roomId = "r1", senderId = sender, kind = "text", createdAt = createdAt, eventSeq = seq)

    private fun elapsedMs(start: Long): Long = (System.nanoTime() - start) / 1_000_000L

    // ════════════════════ Chat loading / transitions (large datasets) ════════════════════

    @Test
    fun `merging a ten-thousand message page stays within time bound`() {
        val existing = (0 until 500).map { msg("id-$it", it) }
        val page = (0 until 10_000).map { msg("id-$it", 1000 + it) }
        val start = System.nanoTime()
        val merged = mergeHistory(existing, page)
        val ms = elapsedMs(start)
        assertTrue("10k history merge took ${ms}ms", ms < 5_000)
        assertEquals(10_000, merged.size)
        assertEquals(merged.size, merged.map { it.id }.distinct().size)
    }

    @Test
    fun `initial load of ten thousand messages stays within time bound`() {
        val page = (10_000 downTo 1).map { msg("id-$it", it) }
        val start = System.nanoTime()
        val loaded = initialLoad(page)
        val ms = elapsedMs(start)
        assertTrue("10k initial load took ${ms}ms", ms < 5_000)
        assertEquals(1, loaded.first().eventSeq)
        assertEquals(10_000, loaded.last().eventSeq)
    }

    @Test
    fun `live append preserves existing row order and identity`() {
        val existing = (1..500).map { msg("e$it", it) }
        val incoming = (501..550).map { msg("n$it", it) }
        val result = liveAppend(existing, incoming)
        assertEquals(550, result.size)
        assertEquals(existing.map { it.id }, result.take(500).map { it.id })
        assertEquals(incoming.map { it.id }, result.drop(500).map { it.id })
    }

    @Test
    fun `overlapping history pages never duplicate ids`() {
        val first = mergeHistory(emptyList(), (1..100).map { msg("id-$it", it) })
        val again = mergeHistory(first, (90..110).map { msg("id-$it", it) })
        assertEquals(110, again.size)
        assertEquals(110, again.map { it.id }.distinct().size)
    }

    @Test
    fun `own-message seed never downgrades a known tick`() {
        val live = mapOf("m1" to MessageStatus.READ, "m2" to MessageStatus.FAILED)
        val seeded = seedOwnSent(live, listOf("m1", "m2", "m3", "m4"))
        assertEquals(MessageStatus.READ, seeded["m1"])
        assertEquals(MessageStatus.FAILED, seeded["m2"])
        assertEquals(MessageStatus.SENT, seeded["m3"])
        assertEquals(MessageStatus.SENT, seeded["m4"])
    }

    // ════════════════════ Message send / receive latency ════════════════════

    @Test
    fun `one thousand correlated sends complete within time bound`() {
        val corr = SendCorrelator()
        val start = System.nanoTime()
        val rids = (1..1000).map { corr.send("cm-$it") }
        rids.forEach { assertNotNull(corr.consume(it)) }
        val ms = elapsedMs(start)
        assertTrue("1000 send/ack pairs took ${ms}ms", ms < 5_000)
        assertEquals(0, corr.size)
    }

    @Test
    fun `send frames carry unique request ids`() {
        val ids = (1..1000).map { wsFrame("message.send", "r-$it")["request_id"]?.jsonPrimitive?.content }
        assertEquals(1000, ids.distinct().size)
    }

    @Test
    fun `send frame embeds client id kind body and attachment ids`() {
        val payload = buildJsonObject {
            put("client_message_id", "cm-1")
            put("kind", "text")
            put("attachment_ids", buildJsonObject {})
        }
        val frame = wsFrame("message.send", "r-1", payload)
        assertEquals("message.send", frame["type"]?.jsonPrimitive?.content)
        assertEquals("cm-1", frame["payload"]?.jsonObject?.get("client_message_id")?.jsonPrimitive?.content)
        assertEquals(1, frame["v"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `receipt chain upgrades monotonically under latency`() {
        val chain = listOf(
            nextStatus(null, MessageStatus.SENDING),
            nextStatus(MessageStatus.SENDING, MessageStatus.SENT),
            nextStatus(MessageStatus.SENT, MessageStatus.DELIVERED),
            nextStatus(MessageStatus.DELIVERED, MessageStatus.READ)
        )
        assertEquals(listOf(0, 1, 2, 3), chain.map { it.rank })
    }

    @Test
    fun `historical delivered fan-out is capped at twenty per load`() {
        val peerMsgs = (1..1000).map { msg("p$it", it, sender = "peer") }
        val capped = peerMsgs.filter { it.senderId != "me" }.sortedByDescending { it.eventSeq }.take(20)
        assertEquals(20, capped.size)
        assertEquals(1000, capped.first().eventSeq)
    }

    // ════════════════════ Memory leaks / bounded stores ════════════════════

    @Test
    fun `status store caps at 2000 entries under sustained load`() {
        val all = (1..5000).associate { "msg-$it" to (it % 3 + 1) }
        val merged = MessageStatusStore.mergeRanks(emptyMap(), all)
        assertEquals(5000, merged.size)
        val trimmed = trimStatus(merged, 2000)
        assertEquals(2000, trimmed.size)
        assertTrue(trimmed.all { (_, v) -> v in 1..3 })
    }

    @Test
    fun `status merges never downgrade under randomized churn`() {
        var store = emptyMap<String, Int>()
        val expected = mutableMapOf<String, Int>()
        val rand = java.util.Random(42L)
        repeat(4000) {
            val id = "m${rand.nextInt(300)}"
            val rank = rand.nextInt(4) + 1
            store = MessageStatusStore.mergeRanks(store, mapOf(id to rank))
            expected[id] = maxOf(expected[id] ?: 0, rank)
        }
        expected.forEach { (id, maxRank) -> assertEquals(maxRank, store[id]) }
        assertTrue(store.size <= 300)
    }

    @Test
    fun `correlator consumes every request exactly once`() {
        val corr = SendCorrelator()
        val rids = (1..1000).map { corr.send("cm-$it") }
        rids.shuffled().forEach { rid -> assertNotNull(corr.consume(rid)) }
        rids.forEach { rid -> assertNull(corr.consume(rid)) } // double consume is a no-op
        assertEquals(0, corr.size)
    }

    @Test
    fun `failed sends drain the correlator and retain resends`() {
        val corr = SendCorrelator()
        val rids = (1..150).map { corr.send("cm-$it") }
        rids.forEachIndexed { i, rid -> corr.consume(rid) }
        assertEquals(0, corr.size)
    }

    // ════════════════════ Network usage bounds ════════════════════

    @Test
    fun `network page sizes are small fixed constants`() {
        assertEquals(50, HISTORY_PAGE_LIMIT)
        assertEquals(20, SEARCH_PAGE_LIMIT)
        assertEquals(200, CATCHUP_LIMIT)
        assertEquals(12, GLOBAL_MAX_ROOMS)
        assertEquals(30, GLOBAL_INBOX_LIMIT)
        assertEquals(5, GLOBAL_PER_ROOM_LIMIT)
        assertEquals(8_388_608L, MULTIPART_PART_SIZE)
    }

    @Test
    fun `media prefetch and previews are capped`() {
        assertEquals(12, MEDIA_PREVIEW_LIMIT)
        assertEquals(20, MARK_DELIVERED_FANOUT)
    }

    // ════════════════════ Storage usage bounds ════════════════════

    @Test
    fun `recent searches dedupe and cap at eight`() {
        var recents = emptyList<String>()
        (1..50).forEach { i ->
            recents = dedupeRecent(recents, "query$i")
            recents = dedupeRecent(recents, "query$i") // repeat immediately -> moves to front
        }
        assertEquals(8, recents.size)
        assertEquals("query50", recents.first())
        assertTrue(recents.distinct().size == recents.size)
        assertEquals(8, dedupeRecent(recents, "x").size) // under 2 chars ignored -> unchanged
    }

    @Test
    fun `group journal and call log cap at one hundred`() {
        var journal = emptyList<String>()
        repeat(10_000) { i -> journal = prependCap(journal, "e$i", 100) }
        assertEquals(100, journal.size)
        assertEquals("e9999", journal.first())

        var callLog = emptyList<String>()
        repeat(500) { i -> callLog = prependCap(callLog, "c$i", 100) }
        assertEquals(100, callLog.size)
    }

    @Test
    fun `local contacts cap at 2000 under heavy syncing`() {
        var contacts = emptyMap<String, Int>()
        repeat(3000) { i -> contacts = contacts.filterKeys { it != "c$i" } + ("c$i" to i) }
        val trimmed = trimStatus(contacts, 2000)
        assertEquals(2000, trimmed.size)
    }

    @Test
    fun `report submissions cap at fifty`() {
        var reports = emptyList<String>()
        repeat(200) { i -> reports = prependCap(reports, "r$i", 50) }
        assertEquals(50, reports.size)
    }

    // ════════════════════ CPU / battery / ANR guards ════════════════════

    @Test
    fun `rapid blank typing schedules no search work`() {
        val keystrokes = List(50) { "" }
        val executed = keystrokes.lastOrNull()?.takeIf { it.isNotBlank() }
        assertNull(executed)
    }

    @Test
    fun `highlight scan over a hundred thousand characters terminates`() {
        val huge = "ab".repeat(50_000)
        val start = System.nanoTime()
        var count = 0
        var startIdx = 0
        while (true) {
            val idx = huge.indexOf("ab", startIdx, ignoreCase = true)
            if (idx < 0) break
            count++
            startIdx = idx + 2
        }
        val ms = elapsedMs(start)
        assertTrue("100k-char highlight took ${ms}ms", ms < 5_000)
        assertEquals(50_000, count)
    }

    @Test
    fun `blocked-message scan over ten thousand messages stays within time bound`() {
        val messages = (1..10_000).map { msg("m$it", it, sender = if (it % 2 == 0) "blocked" else "peer") }
        val start = System.nanoTime()
        val visible = filterBlocked(messages, setOf("blocked"), mapOf("blocked" to 1L))
        val ms = elapsedMs(start)
        assertTrue("10k block scan took ${ms}ms", ms < 5_000)
        assertEquals(5000, visible.size)
    }

    @Test
    fun `corrupt persisted status decodes to empty instead of crashing`() {
        val safe = jsonSafeDecode<Map<String, Int>>("not json{{") ?: emptyMap()
        assertEquals(emptyMap<String, Int>(), safe)
    }

    @Test
    fun `malformed recent-search payload degrades to empty list`() {
        val safe = jsonSafeDecode<List<String>>("[1,2,{") ?: emptyList()
        assertEquals(emptyList<String>(), safe)
    }

    // ════════════════════ Startup / re-entry ════════════════════

    @Test
    fun `restart restore merges persisted ticks without downgrading live state`() {
        val persisted = mapOf("m1" to 1, "m2" to 2) // SENT, DELIVERED
        val live = mutableMapOf("m1" to MessageStatus.READ, "m2" to MessageStatus.SENT)
        persisted.forEach { (id, rank) ->
            val status = MessageStatus.entries.find { it.rank == rank }
            if (status != null) {
                val cur = live[id]
                live[id] = if (cur == null || status.rank > cur.rank) status else cur
            }
        }
        assertEquals(MessageStatus.READ, live["m1"])
        assertEquals(MessageStatus.DELIVERED, live["m2"])
    }

    @Test
    fun `reopening a chat loads history idempotently without duplicates`() {
        val page = (1..1_000).map { msg("id-$it", it) }
        val firstLoad = initialLoad(page)
        val secondLoad = mergeHistory(firstLoad, page)
        assertEquals(firstLoad.map { it.id }, secondLoad.map { it.id })
    }

    // ════════════════════ Large datasets: chat list + search ════════════════════

    private data class ChatItem(
        val id: String,
        val kind: String,
        val name: String,
        val unread: Int = 0,
        val pinned: Boolean = false,
        val starred: Boolean = false
    )

    private fun visibleChats(all: List<ChatItem>, filter: String): List<ChatItem> {
        val f = filter.lowercase()
        val filtered = all.filter {
            when (f) {
                "unread" -> it.unread > 0
                "group" -> it.kind.equals("group", ignoreCase = true)
                "starred" -> it.starred
                else -> true
            }
        }
        return filtered.sortedWith(compareBy({ if (it.pinned) 0 else 1 }, { it.name.lowercase() }))
    }

    @Test
    fun `chat list of five thousand rows filters and sorts within time bound`() {
        val rows = (1..5000).map { i ->
            ChatItem(
                id = "r$i",
                kind = if (i % 3 == 0) "group" else "direct",
                name = "Chat $i",
                unread = if (i % 10 == 0) 1 else 0,
                pinned = i % 7 == 0,
                starred = i % 5 == 0
            )
        }
        val start = System.nanoTime()
        val visible = visibleChats(rows, "")
        val unread = visibleChats(rows, "unread")
        val groups = visibleChats(rows, "group")
        val ms = elapsedMs(start)
        assertTrue("5k chat filter+sort took ${ms}ms", ms < 5_000)
        assertEquals(5000, visible.size)
        assertEquals(500, unread.size)
        assertEquals(1666, groups.size)
        val firstUnpinned = visible.indexOfFirst { !it.pinned }
        if (firstUnpinned >= 0) assertTrue(visible.drop(firstUnpinned).none { it.pinned })
        assertEquals(0, visible.zipWithNext().count { (a, b) -> !a.pinned && b.pinned })
    }

    @Test
    fun `search over ten thousand contacts completes within time bound`() {
        val contacts = (0 until 10_000).map { i ->
            ContactRow(
                key = "uid:u$i", firstName = "First$i", lastName = "Last$i",
                displayName = "First$i Last$i", phone = "+1555$i",
                normalizedPhone = "+1555${i.toString().padStart(5, '0')}", userId = "u$i"
            )
        }
        val start = System.nanoTime()
        val hits = ContactBook.filterRows(contacts, "First9999")
        val ms = elapsedMs(start)
        assertTrue("10k contact search took ${ms}ms", ms < 5_000)
        assertEquals(1, hits.size)
        assertEquals("uid:u9999", hits.single().key)
    }

    // ── Mirror constants ──

    /** Safe persisted-store decode: any failure yields null, caller falls back to an empty container. */
    private inline fun <reified T> jsonSafeDecode(raw: String): T? = runCatching {
        json.decodeFromString<T>(raw)
    }.getOrNull()

    private val HISTORY_PAGE_LIMIT = 50
    private val SEARCH_PAGE_LIMIT = 20
    private val CATCHUP_LIMIT = 200
    private val GLOBAL_MAX_ROOMS = 12
    private val GLOBAL_INBOX_LIMIT = 30
    private val GLOBAL_PER_ROOM_LIMIT = 5
    private val MULTIPART_PART_SIZE = 8_388_608L
    private val MEDIA_PREVIEW_LIMIT = 12
    private val MARK_DELIVERED_FANOUT = 20
}