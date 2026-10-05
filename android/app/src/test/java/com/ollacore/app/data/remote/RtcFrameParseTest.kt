package com.ollacore.app.data.remote

import org.junit.Assert.*
import org.junit.Test

class RtcFrameParseTest {

    private val cand =
        "candidate:842657312 1 udp 1685987327 192.168.1.5 54531 typ srflx raddr 10.0.0.2 rport 54531"

    @Test
    fun `answer flat shape parses`() {
        val e = parseRtcFrame("""{"type":"answer","sdp":"v=0 answer"}""")
        assertTrue(e is RtcEvent.Answer)
        assertEquals("v=0 answer", (e as RtcEvent.Answer).sdp)
    }

    @Test
    fun `offer carries request id`() {
        val e = parseRtcFrame("""{"type":"offer","sdp":"v=0 offer","request_id":7}""")
        assertTrue(e is RtcEvent.Offer)
        assertEquals(7, (e as RtcEvent.Offer).requestId)
    }

    @Test
    fun `candidate nested shape parses`() {
        val e = parseRtcFrame(
            """{"type":"candidate","candidate":{"candidate":"$cand","sdpMid":"0","sdpMLineIndex":0}}"""
        )
        assertTrue(e is RtcEvent.Candidate)
        e as RtcEvent.Candidate
        assertEquals(cand, e.candidate)
        assertEquals("0", e.sdpMid)
        assertEquals(0, e.sdpMLineIndex)
    }

    @Test
    fun `candidate flat shape parses`() {
        val e = parseRtcFrame(
            """{"type":"candidate","candidate":"$cand","sdpMid":"audio","sdpMLineIndex":1}"""
        )
        assertTrue(e is RtcEvent.Candidate)
        e as RtcEvent.Candidate
        assertEquals("audio", e.sdpMid)
        assertEquals(1, e.sdpMLineIndex)
    }

    @Test
    fun `candidate event shape parses`() {
        val e = parseRtcFrame(
            """{"event":"candidate","candidate":{"candidate":"$cand","sdpMid":"0","sdpMLineIndex":0}}"""
        )
        assertTrue(e is RtcEvent.Candidate)
    }

    @Test
    fun `named server events still route to ServerEvent`() {
        val e = parseRtcFrame("""{"event":"ended","reason":"bye"}""")
        assertTrue(e is RtcEvent.ServerEvent)
        assertEquals("ended", (e as RtcEvent.ServerEvent).event)
    }

    @Test
    fun `unknown frames return null instead of crashing`() {
        assertNull(parseRtcFrame("""{"hello":"world"}"""))
        assertTrue(parseRtcFrame("not json") is RtcEvent.Error)
    }

    @Test
    fun `candidate without payload is an error not a crash`() {
        assertTrue(parseRtcFrame("""{"type":"candidate"}""") is RtcEvent.Error)
    }
}
