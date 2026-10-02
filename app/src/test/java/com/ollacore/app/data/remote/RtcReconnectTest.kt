package com.ollacore.app.data.remote

import org.junit.Assert.*
import org.junit.Test

/**
 * Signaling safety: bounded exponential backoff, payload-free log summaries
 * (SDP/ICE credentials must never reach logcat) and busy/declined routing.
 */
class RtcReconnectTest {

    // ── Backoff ────────────────────────────────────────────────────

    @Test
    fun `backoff starts at 500ms and doubles to an 8s cap`() {
        assertEquals(500L, rtcReconnectDelayMs(1))
        assertEquals(1_000L, rtcReconnectDelayMs(2))
        assertEquals(2_000L, rtcReconnectDelayMs(3))
        assertEquals(4_000L, rtcReconnectDelayMs(4))
        assertEquals(8_000L, rtcReconnectDelayMs(5))
        assertEquals(8_000L, rtcReconnectDelayMs(6))
        assertEquals(8_000L, rtcReconnectDelayMs(50))
    }

    @Test
    fun `backoff is monotonic and never negative`() {
        var prev = -1L
        for (attempt in 1..10) {
            val d = rtcReconnectDelayMs(attempt)
            assertTrue("attempt $attempt delay must be > 0", d > 0)
            assertTrue("attempt $attempt must not decrease", d >= prev)
            prev = d
        }
        assertEquals(500L, rtcReconnectDelayMs(0)) // defensive: treated as first attempt
        assertTrue(rtcReconnectDelayMs(-3) > 0)
    }

    @Test
    fun `max attempts constant is bounded`() {
        assertTrue(RTC_MAX_RECONNECT_ATTEMPTS in 1..10)
    }

    // ── Log hygiene: summaries never carry payloads ────────────────

    @Test
    fun `frame summary lists keys only - sdp values never leak`() {
        val sdp = "v=0\r\no=- 1 1 IN IP4 192.168.1.5\r\na=ice-ufrag:SECRETUFRAG\r\na=ice-pwd:SECRETPWD\r\n"
        val summary = frameSummary("""{"cmd":"offer","sdp":{"type":"offer","sdp":"$sdp"}}""")
        assertEquals("keys=cmd,sdp", summary)
        assertFalse(summary.contains("SECRET"))
        assertFalse(summary.contains("192.168"))
        assertFalse(summary.contains("v=0"))
    }

    @Test
    fun `frame summary degrades safely on non-json`() {
        assertEquals("unparseable(len=11)", frameSummary("not json at"))
        assertEquals("unparseable(len=0)", frameSummary(""))
    }

    @Test
    fun `event summary carries length not content`() {
        val sdp = "v=0\r\na=ice-ufrag:SECRETUFRAG\r\na=ice-pwd:SECRETPWD".repeat(3)
        val summary = eventSummary(RtcEvent.Answer(sdp))
        assertEquals("answer(len=${sdp.length})", summary)
        assertFalse(summary.contains("SECRET"))
    }

    @Test
    fun `event summary for reconnecting and auth includes attempt bounds`() {
        assertEquals("reconnecting(2/5)", eventSummary(RtcEvent.Reconnecting(2, 5)))
        assertEquals("auth_required", eventSummary(RtcEvent.AuthRequired))
        assertEquals("connected", eventSummary(RtcEvent.Connected))
    }

    @Test
    fun `candidate summary carries mid and index only`() {
        val summary = eventSummary(
            RtcEvent.Candidate("candidate:1 1 udp 1 192.168.1.5 54531 typ host", "0", 0)
        )
        assertEquals("candidate(mid=0,idx=0)", summary)
        assertFalse(summary.contains("192.168"))
    }

    // ── Busy / declined routing (server events) ────────────────────

    @Test
    fun `busy event parses as server event`() {
        val e = parseRtcFrame("""{"event":"busy","reason":"user busy"}""")
        assertTrue(e is RtcEvent.ServerEvent)
        assertEquals("busy", (e as RtcEvent.ServerEvent).event)
    }

    @Test
    fun `declined and rejected events parse as server events`() {
        assertEquals("declined", (parseRtcFrame("""{"event":"declined"}""") as RtcEvent.ServerEvent).event)
        assertEquals("rejected", (parseRtcFrame("""{"event":"rejected"}""") as RtcEvent.ServerEvent).event)
    }
}
