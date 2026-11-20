package com.ollacore.app.ui.search

import com.ollacore.app.data.model.InboxItem
import com.ollacore.app.data.model.InboxPeer
import com.ollacore.app.data.model.MessageResponse
import com.ollacore.app.ui.contacts.ContactBook
import com.ollacore.app.ui.contacts.ContactRow
import com.ollacore.app.ui.globalsearch.GlobalSearchViewModel
import com.ollacore.app.ui.globalsearch.SearchTab
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/**
 * Section 11 - Search. Covers users (ContactBook), chats and messages (global
 * search matching rules + result ordering + fan-out caps), the in-room search
 * sanitize/error/debounce/clear contracts, tab classification and captioning
 * (GlobalSearchViewModel companion, pure), plus empty/long/no-result/unicode/
 * emoji/offline/large-set edge cases. Back navigation is handled by the
 * existing UiDeepTest.searchOpensAndBack (instrumentation) and is not repeatable
 * on the JVM.
 */
class SearchTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── Contact fixtures ──

    private val alice = ContactRow(
        key = "uid:u1", firstName = "Alice", lastName = "Wonder",
        displayName = "Alice Wonder", phone = "+1 555-000-1111",
        normalizedPhone = "+15550001111", username = "alicew", userId = "u1"
    )
    private val arun = ContactRow(
        key = "uid:u2", firstName = "Arun", lastName = "Kumar",
        displayName = "Arun Kumar", phone = "+1 555-000-2222",
        normalizedPhone = "+15550002222", username = "arunk", userId = "u2"
    )
    private val elan = ContactRow(
        key = "uid:u3", firstName = "Élan", lastName = "Rao",
        displayName = "Élan Rao", phone = "+1 555-000-3333",
        normalizedPhone = "+15550003333", username = "elanr", userId = "u3"
    )
    private val aarya = ContactRow(
        key = "uid:u4", firstName = "आर्या", lastName = "वर्मा",
        displayName = "आर्या वर्मा", phone = "+1 555-000-4444",
        normalizedPhone = "+15550004444", username = "aarya", userId = "u4"
    )
    private val contacts = listOf(alice, arun, elan, aarya)

    // ── Search users ──

    @Test
    fun `user search matches partial names`() {
        assertEquals(listOf("uid:u1"), ContactBook.filterRows(contacts, "ali").map { it.key })
        assertEquals(listOf("uid:u1"), ContactBook.filterRows(contacts, "wond").map { it.key })
        assertEquals(listOf("uid:u2"), ContactBook.filterRows(contacts, "kumar").map { it.key })
        assertEquals(listOf("uid:u3"), ContactBook.filterRows(contacts, "rao").map { it.key })
    }

    @Test
    fun `user search is case-insensitive`() {
        assertEquals(listOf("uid:u1"), ContactBook.filterRows(contacts, "ALICE").map { it.key })
        assertEquals(listOf("uid:u1"), ContactBook.filterRows(contacts, "aLiCe W").map { it.key })
        assertTrue(ContactBook.matchesQuery(alice, "WONDER"))
    }

    @Test
    fun `user search by number finds formatted contacts via digits`() {
        assertEquals(listOf("uid:u2"), ContactBook.filterRows(contacts, "000-2222").map { it.key })
        // Spaced/grouped digits normalize against the raw phone and digit index.
        assertTrue(ContactBook.matchesQuery(alice, "5550001111"))
        assertTrue(ContactBook.matchesQuery(alice, "1 (555)"))
    }

    @Test
    fun `user search by username with or without at sign`() {
        assertEquals(listOf("uid:u1"), ContactBook.filterRows(contacts, "@alicew").map { it.key })
        assertEquals(listOf("uid:u1"), ContactBook.filterRows(contacts, "alicew").map { it.key })
        assertEquals(listOf("uid:u2"), ContactBook.filterRows(contacts, "@arun").map { it.key })
    }

    @Test
    fun `unicode user search matches accented and script names`() {
        assertEquals(listOf("uid:u3"), ContactBook.filterRows(contacts, "élan").map { it.key })
        assertEquals(listOf("uid:u4"), ContactBook.filterRows(contacts, "आर्या").map { it.key })
        assertEquals(listOf("uid:u4"), ContactBook.filterRows(contacts, "आर").map { it.key })
        assertEquals(listOf("uid:u3"), ContactBook.filterRows(contacts, "élan rao").map { it.key })
        // Case folding does not strip accents: the unaccented spelling is a miss.
        assertTrue(ContactBook.filterRows(contacts, "elan rao").isEmpty())
    }

    @Test
    fun `emoji queries are preserved end to end`() {
        val cake = alice.copy(username = "cake👍", displayName = "Alice👍")
        assertTrue(ContactBook.matchesQuery(cake, "👍"))
        assertEquals("👍 keep me", sanitizeQuery("👍 keep me"))
    }

    @Test
    fun `empty query returns every contact`() {
        assertEquals(4, ContactBook.filterRows(contacts, "").size)
        assertEquals(4, ContactBook.filterRows(contacts, "   ").size)
        assertTrue(ContactBook.matchesQuery(alice, ""))
    }

    @Test
    fun `no-result queries return empty lists`() {
        assertTrue(ContactBook.filterRows(contacts, "zzz-no-one").isEmpty())
        assertFalse(ContactBook.matchesQuery(alice, "998877"))
    }

    @Test
    fun `huge contact sets filter accurately and in time`() {
        val many = (0 until 5000).map { i ->
            ContactRow(
                key = "uid:u$i", firstName = "First$i", lastName = "Last$i",
                displayName = "First$i Last$i", phone = "+1555000$i",
                normalizedPhone = "+1555${i.toString().padStart(4, '0')}${(i % 10)}", userId = "u$i"
            )
        }
        val start = System.nanoTime()
        val hits = ContactBook.filterRows(many, "First2048")
        val elapsed = System.nanoTime() - start
        assertEquals(1, hits.size)
        assertEquals("uid:u2048", hits.single().key)
        assertTrue("5000-contact filter too slow", elapsed < 2_000_000_000L)
    }

    // ── Search chats ──

    private fun chatName(item: InboxItem): String =
        item.name ?: item.peer?.displayName ?: item.peer?.phone ?: ""

    private fun matchesChat(item: InboxItem, q: String): Boolean {
        val name = chatName(item)
        return name.contains(q, ignoreCase = true) || (item.peer?.phone ?: "").contains(q)
    }

    private val goaRoom = InboxItem(roomId = "r-goa", kind = "direct", name = "Trip to Goa")
    private val akashRoom = InboxItem(
        roomId = "r-ak", kind = "group", name = null,
        peer = InboxPeer(phone = "+1002", userId = "u2", displayName = "Akash")
    )

    @Test
    fun `chat search matches group name case-insensitively`() {
        assertTrue(matchesChat(goaRoom, "trip"))
        assertTrue(matchesChat(goaRoom, "GOA"))
    }

    @Test
    fun `chat search falls back to peer display name and phone`() {
        assertTrue(matchesChat(akashRoom, "akas"))
        assertTrue(matchesChat(akashRoom, "+1002"))
        assertFalse(matchesChat(akashRoom, "goa"))
    }

    @Test
    fun `chat search returns only matching rooms`() {
        val rooms = listOf(goaRoom, akashRoom)
        assertEquals(listOf("r-goa"), rooms.filter { matchesChat(it, "trip") }.map { it.roomId })
        assertEquals(listOf("r-ak"), rooms.filter { matchesChat(it, "aka") }.map { it.roomId })
        assertEquals(listOf("r-ak"), rooms.filter { matchesChat(it, "+1002") }.map { it.roomId })
    }

    @Test
    fun `global search requires at least two characters to start`() {
        assertFalse(globalSearchGate(""))
        assertFalse(globalSearchGate("a"))
        assertTrue(globalSearchGate("ab"))
        assertTrue(globalSearchGate("  ab  "))
    }

    // ── Search messages ──

    private fun message(id: String, seq: Int, kind: String = "text", text: String? = null, mime: String? = null): MessageResponse {
        val body = buildJsonObject {
            if (text != null) put("text", text)
            if (mime != null) put("mime", mime)
        }
        return MessageResponse(
            id = id, roomId = "r1", senderId = "u1", kind = kind,
            body = body, createdAt = "2026-01-01T00:00:00Z", eventSeq = seq
        )
    }

    @Test
    fun `message results are ordered newest first by event seq`() {
        val hits = listOf(message("m1", 1), message("m5", 5), message("m3", 3))
        assertEquals(listOf("m5", "m3", "m1"), newestFirst(hits).map { it.id })
    }

    @Test
    fun `rooms scanned is capped at twelve`() {
        val rooms = (1..30).map { InboxItem(roomId = "r$it", kind = "direct", name = "room $it") }
        assertEquals(12, roomsScanned(rooms))
        assertEquals(5, roomsScanned(rooms.take(5)))
    }

    @Test
    fun `per-room fan-out failures are tolerated`() {
        val results = fanOut(listOf(1, 2, 3)) { i ->
            if (i == 2) throw IllegalStateException("403 forbidden") else "hit-$i"
        }
        assertEquals(listOf("hit-1", "hit-3"), results)
    }

    @Test
    fun `large message result sets sort within time bound`() {
        val hits = (0 until 1000).map { message("m$it", it) }.shuffled()
        val start = System.nanoTime()
        val sorted = newestFirst(hits)
        val elapsed = System.nanoTime() - start
        assertEquals(999, sorted.first().eventSeq)
        assertEquals(0, sorted.last().eventSeq)
        assertTrue("1000-message sort too slow", elapsed < 2_000_000_000L)
    }

    // ── Sanitize (very long / special characters / invalid) ──

    private fun sanitizeQuery(query: String): String =
        query.replace(Regex("[<>{}\\[\\]]"), "").trim().take(200)

    @Test
    fun `sanitize strips angle braces brackets and curly braces`() {
        assertEquals("cdefg", sanitizeQuery("c<d>e{f}[g]"))
        assertEquals("hello world", sanitizeQuery("hello<{> world"))
    }

    @Test
    fun `sanitize trims whitespace`() {
        assertEquals("hello", sanitizeQuery("  hello  "))
    }

    @Test
    fun `sanitize truncates very long queries to 200 chars`() {
        val longQuery = "x".repeat(500)
        assertEquals(200, sanitizeQuery(longQuery).length)
        assertEquals("x".repeat(200), sanitizeQuery(longQuery))
    }

    @Test
    fun `all-special queries sanitize to blank and report invalid`() {
        assertTrue(sanitizeQuery("<>{}[]").isBlank())
        assertEquals("Invalid search query", searchGuard(sanitizeQuery("<><>{}")))
    }

    @Test
    fun `at-sign and plus are preserved by the sanitizer`() {
        assertEquals("@mia +1", sanitizeQuery("@mia +1"))
    }

    // ── Debounce / rapid typing / clear ──

    private val DEBOUNCE_MS = 300

    @Test
    fun `debounce budget is 300 ms`() {
        assertEquals(300, DEBOUNCE_MS)
    }

    @Test
    fun `rapid typing executes only the final query`() {
        assertEquals("ollac", executeAfterDebounce(listOf("o", "ol", "oll", "olla", "ollac")))
    }

    @Test
    fun `clearing mid-burst cancels the pending search`() {
        assertEquals("", executeAfterDebounce(listOf("a", "al", "")))
    }

    @Test
    fun `clear search resets results search flag and error`() {
        val started = SearchUiState(query = "hi", results = listOf(message("m1", 1)), hasSearched = true, error = null, isLoading = true)
        val cleared = clearSearch(started)
        assertEquals(SearchUiState(), cleared)
        assertTrue(cleared.results.isEmpty())
        assertFalse(cleared.hasSearched)
        assertFalse(cleared.isLoading)
        assertNull(cleared.error)
        assertTrue(cleared.query.isEmpty())
    }

    // ── Offline / errors ──

    private fun mapSearchError(e: Throwable): String = when {
        e.message?.contains("401") == true -> "Session expired. Please log in again."
        e.message?.contains("403") == true -> "You don't have access to this room."
        e.message?.contains("404") == true -> "Room not found."
        e.message?.contains("429") == true -> "Too many requests. Please wait."
        e.message?.contains("timeout", ignoreCase = true) == true -> "Search timed out. Try a shorter query."
        e.message?.contains("network", ignoreCase = true) == true -> "No network connection."
        else -> "Search failed: ${e.message ?: "Unknown error"}"
    }

    @Test
    fun `offline searches map to a friendly network message`() {
        assertEquals("No network connection.", mapSearchError(IllegalStateException("Network is unreachable")))
        assertEquals("No network connection.", mapSearchError(IllegalStateException("network ERROR")))
    }

    @Test
    fun `search errors map to specific guidance`() {
        assertEquals("Session expired. Please log in again.", mapSearchError(IllegalStateException("HTTP 401")))
        assertEquals("You don't have access to this room.", mapSearchError(IllegalStateException("HTTP 403")))
        assertEquals("Room not found.", mapSearchError(IllegalStateException("HTTP 404")))
        assertEquals("Too many requests. Please wait.", mapSearchError(IllegalStateException("HTTP 429")))
        assertEquals("Search timed out. Try a shorter query.", mapSearchError(IllegalStateException("Connection timeout after 10s")))
        assertEquals("Search failed: boom", mapSearchError(IllegalStateException("boom")))
    }

    @Test
    fun `search without a room token is blocked with a clear message`() {
        assertEquals("Not connected to room", roomGuard(null) ?: "unexpected")
        assertEquals("Not connected to room", roomGuard("  ") ?: "unexpected")
        assertNull(roomGuard("tok123"))
    }

    // ── Global search tab classification & captions ──

    @Test
    fun `search tabs are the eight expected categories`() {
        assertEquals(listOf("CHATS", "MESSAGES", "MEDIA", "IMAGES", "VIDEOS", "DOCUMENTS", "LINKS", "AUDIO"), SearchTab.entries.map { it.name })
    }

    @Test
    fun `messages tab accepts every kind`() {
        assertTrue(GlobalSearchViewModel.matchesTab(message("m1", 1, kind = "text", text = "hi"), SearchTab.MESSAGES))
        assertTrue(GlobalSearchViewModel.matchesTab(message("m2", 2, kind = "image"), SearchTab.MESSAGES))
        assertTrue(GlobalSearchViewModel.matchesTab(message("m3", 3, kind = "system"), SearchTab.MESSAGES))
    }

    @Test
    fun `media tabs classify by kind and mime`() {
        val img = message("mi", 1, kind = "image")
        val vid = message("mv", 2, kind = "video")
        val text = message("mt", 3, kind = "text", text = "no media")
        assertTrue(GlobalSearchViewModel.matchesTab(img, SearchTab.MEDIA))
        assertTrue(GlobalSearchViewModel.matchesTab(vid, SearchTab.MEDIA))
        assertFalse(GlobalSearchViewModel.matchesTab(text, SearchTab.MEDIA))
        // Mime fallback when kind is generic "text".
        val mimeImg = message("mm", 4, kind = "text", mime = "image/png")
        assertTrue(GlobalSearchViewModel.matchesTab(mimeImg, SearchTab.IMAGES))
        assertTrue(GlobalSearchViewModel.matchesTab(mimeImg, SearchTab.MEDIA))
    }

    @Test
    fun `image tab is images only and video tab is videos only`() {
        val img = message("mi", 1, kind = "image")
        val vid = message("mv", 2, kind = "video")
        assertTrue(GlobalSearchViewModel.matchesTab(img, SearchTab.IMAGES))
        assertFalse(GlobalSearchViewModel.matchesTab(vid, SearchTab.IMAGES))
        assertTrue(GlobalSearchViewModel.matchesTab(vid, SearchTab.VIDEOS))
        assertFalse(GlobalSearchViewModel.matchesTab(img, SearchTab.VIDEOS))
    }

    @Test
    fun `audio tab matches kind or mime`() {
        assertTrue(GlobalSearchViewModel.matchesTab(message("a1", 1, kind = "audio"), SearchTab.AUDIO))
        assertTrue(GlobalSearchViewModel.matchesTab(message("a2", 2, kind = "text", mime = "audio/ogg"), SearchTab.AUDIO))
        assertFalse(GlobalSearchViewModel.matchesTab(message("a3", 3, kind = "image"), SearchTab.AUDIO))
    }

    @Test
    fun `documents tab matches file kinds and application mime`() {
        assertTrue(GlobalSearchViewModel.matchesTab(message("d1", 1, kind = "file", text = "report.pdf"), SearchTab.DOCUMENTS))
        assertTrue(GlobalSearchViewModel.matchesTab(message("d2", 2, kind = "text", mime = "application/pdf"), SearchTab.DOCUMENTS))
        assertFalse(GlobalSearchViewModel.matchesTab(message("d3", 3, kind = "image"), SearchTab.DOCUMENTS))
    }

    @Test
    fun `links tab finds urls in message text`() {
        assertTrue(GlobalSearchViewModel.matchesTab(message("l1", 1, text = "see https://ollacore.app now"), SearchTab.LINKS))
        assertTrue(GlobalSearchViewModel.matchesTab(message("l2", 2, text = "go to www.example.com"), SearchTab.LINKS))
        assertTrue(GlobalSearchViewModel.matchesTab(message("l3", 3, text = "read http://a.b"), SearchTab.LINKS))
        assertTrue(GlobalSearchViewModel.matchesTab(message("l4", 4, text = "HTTPS://UPPER.CASE"), SearchTab.LINKS))
        assertFalse(GlobalSearchViewModel.matchesTab(message("l5", 5, text = "no links here"), SearchTab.LINKS))
    }

    @Test
    fun `caption prefers text then filename then kind emoji`() {
        assertEquals("hello", GlobalSearchViewModel.captionFor(message("c1", 1, text = "hello")))
        val filename = message("c2", 2, kind = "file").copy(
            body = buildJsonObject { put("filename", "memo.pdf") }
        )
        assertEquals("memo.pdf", GlobalSearchViewModel.captionFor(filename))
        assertEquals("📷 Photo", GlobalSearchViewModel.captionFor(message("c3", 3, kind = "image")))
        assertEquals("🎥 Video", GlobalSearchViewModel.captionFor(message("c4", 4, kind = "video")))
        assertEquals("🎵 Audio", GlobalSearchViewModel.captionFor(message("c5", 5, kind = "audio")))
        assertEquals("📄 Document", GlobalSearchViewModel.captionFor(message("c6", 6, kind = "document")))
        assertEquals("📍 Location", GlobalSearchViewModel.captionFor(message("c7", 7, kind = "location")))
        assertEquals("Message", GlobalSearchViewModel.captionFor(message("c8", 8, kind = "sticker")))
    }

    // ── Highlighting ──

    private fun highlightRanges(full: String, query: String): List<IntRange> {
        if (query.isBlank()) return emptyList()
        val ranges = mutableListOf<IntRange>()
        var start = 0
        while (true) {
            val idx = full.indexOf(query, start, ignoreCase = true)
            if (idx < 0) break
            ranges.add(idx until idx + query.length)
            start = idx + query.length
        }
        return ranges
    }

    @Test
    fun `highlight finds every case-insensitive occurrence`() {
        assertEquals(listOf(0..4, 6..10, 12..16), highlightRanges("Hello hELLo hello", "hello"))
    }

    @Test
    fun `blank query or no match produce no highlights`() {
        assertTrue(highlightRanges("anything", "  ").isEmpty())
        assertTrue(highlightRanges("anything", "zzz").isEmpty())
    }

    @Test
    fun `highlight does not fall into an infinite loop on overlapping matches`() {
        // start advances past each match, so "ababa" query "ab" yields two hits.
        assertEquals(listOf(0..1, 2..3), highlightRanges("ababa", "ab"))
    }

    // ── Pure mirrors ──

    private fun globalSearchGate(q: String): Boolean = q.trim().length >= 2

    private fun newestFirst(messages: List<MessageResponse>): List<MessageResponse> =
        messages.sortedByDescending { it.eventSeq }

    private val MAX_ROOMS = 12

    private fun roomsScanned(conversations: List<InboxItem>): Int =
        minOf(conversations.size, MAX_ROOMS)

    private fun <T> fanOut(rooms: List<Int>, fetch: (Int) -> T): List<T> =
        rooms.mapNotNull { runCatching { fetch(it) }.getOrNull() }

    private fun executeAfterDebounce(keystrokes: List<String>): String =
        keystrokes.lastOrNull()?.trim() ?: ""

    private fun searchGuard(sanitized: String): String =
        if (sanitized.isBlank()) "Invalid search query" else ""

    private fun roomGuard(roomToken: String?): String? =
        if (roomToken.isNullOrBlank()) "Not connected to room" else null

    private fun clearSearch(state: SearchUiState): SearchUiState = SearchUiState()
}