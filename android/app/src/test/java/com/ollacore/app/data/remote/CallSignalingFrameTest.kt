package com.ollacore.app.data.remote

import com.ollacore.app.data.model.IceServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codec tests for the local signaling server protocol: inbound parse,
 * outbound builders and the mapping onto the existing RtcEvent vocabulary
 * that CallViewModel already handles. Pure JVM - no Android dependencies.
 */
class CallSignalingFrameTest {

    // ── inbound parse ─────────────────────────────────────────────────────

    @Test
    fun `parses auth ok with ice servers`() {
        val f = parseSignalingFrame(
            """{"type":"auth:ok","userId":"u-1","iceServers":""" +
                """[{"urls":["stun:s:3478"]}, """ +
                """{"urls":["turn:t:3478"],"username":"user","credential":"cred"}]}"""
        ) as SignalingFrame.AuthOk
        assertEquals("u-1", f.userId)
        assertEquals(2, f.iceServers.size)
        assertEquals(listOf("stun:s:3478"), f.iceServers[0].urls)
        assertNull(f.iceServers[0].username)
        assertEquals(listOf("turn:t:3478"), f.iceServers[1].urls)
        assertEquals("user", f.iceServers[1].username)
        assertEquals("cred", f.iceServers[1].credential)
    }

    @Test
    fun `parses incoming invite with media type`() {
        val f = parseSignalingFrame(
            """{"type":"call:incoming","callId":"c1","callerId":"u2","callerName":"Bob","roomId":"r9","media":"video"}"""
        ) as SignalingFrame.Incoming
        assertEquals("c1", f.callId)
        assertEquals("u2", f.callerId)
        assertEquals("Bob", f.callerName)
        assertEquals("r9", f.roomId)
        assertEquals("video", f.media)
        assertEquals(false, f.audioOnly)
        assertTrue(parseSignalingFrame(
            """{"type":"call:incoming","callId":"c2","callerId":"u3","roomId":"r1"}"""
        )!!.let { (it as SignalingFrame.Incoming).audioOnly })
    }

    @Test
    fun `parses lifecycle frames`() {
        assertEquals(SignalingFrame.Ringing("c1"),
            parseSignalingFrame("""{"type":"call:ringing","callId":"c1"}"""))
        assertEquals(SignalingFrame.Accepted("c1"),
            parseSignalingFrame("""{"type":"call:accept","callId":"c1"}"""))
        assertEquals(SignalingFrame.Rejected("c1", "declined"),
            parseSignalingFrame("""{"type":"call:reject","callId":"c1","reason":"declined"}"""))
        assertEquals(SignalingFrame.Busy("c1"),
            parseSignalingFrame("""{"type":"call:busy","callId":"c1"}"""))
        assertEquals(SignalingFrame.Failed("c1", "offline"),
            parseSignalingFrame("""{"type":"call:failed","callId":"c1","reason":"offline"}"""))
        assertEquals(SignalingFrame.Timeout("c1"),
            parseSignalingFrame("""{"type":"call:timeout","callId":"c1"}"""))
        assertEquals(SignalingFrame.Hangup("c1", "disconnect"),
            parseSignalingFrame("""{"type":"call:hangup","callId":"c1","reason":"disconnect"}"""))
        assertEquals(SignalingFrame.ErrorMsg("forbidden"),
            parseSignalingFrame("""{"type":"error","reason":"forbidden"}"""))
        assertEquals(SignalingFrame.Pong, parseSignalingFrame("""{"type":"pong"}"""))
    }

    @Test
    fun `parses offer and answer in object and string sdp forms`() {
        val obj = parseSignalingFrame(
            """{"type":"call:offer","callId":"c1","sdp":{"type":"offer","sdp":"v=0..."}}"""
        ) as SignalingFrame.Offer
        assertEquals("c1", obj.callId)
        assertEquals("v=0...", obj.sdp)

        val str = parseSignalingFrame(
            """{"type":"call:answer","callId":"c1","sdp":"v=0 answer..."}"""
        ) as SignalingFrame.Answer
        assertEquals("v=0 answer...", str.sdp)
    }

    @Test
    fun `parses candidate in nested and flat forms`() {
        val nested = parseSignalingFrame(
            """{"type":"call:ice-candidate","callId":"c1","candidate":""" +
                """{"candidate":"candidate:1","sdpMid":"0","sdpMLineIndex":1}}"""
        ) as SignalingFrame.Candidate
        assertEquals("candidate:1", nested.candidate)
        assertEquals("0", nested.sdpMid)
        assertEquals(1, nested.sdpMLineIndex)

        val flat = parseSignalingFrame(
            """{"type":"call:ice-candidate","callId":"c1","candidate":"candidate:2","sdpMid":"audio","sdpMLineIndex":0}"""
        ) as SignalingFrame.Candidate
        assertEquals("candidate:2", flat.candidate)
        assertEquals("audio", flat.sdpMid)
        assertEquals(0, flat.sdpMLineIndex)
    }

    @Test
    fun `unknown or garbled frames return null instead of throwing`() {
        assertNull(parseSignalingFrame("not json"))
        assertNull(parseSignalingFrame("""{"type":"presence:online","userId":"x"}"""))
        assertNull(parseSignalingFrame("""{"nope":1}"""))
        assertNull(parseSignalingFrame("""{"type":"call:offer","callId":"c1"}""")) // missing sdp
        assertNull(parseSignalingFrame("""{"type":"call:ice-candidate","callId":"c1"}""")) // missing candidate
    }

    // ── outbound builders ────────────────────────────────────────────────

    private fun roundtrip(frame: String): Map<String, kotlinx.serialization.json.JsonElement> =
        kotlinx.serialization.json.Json.parseToJsonElement(frame).let {
            it as kotlinx.serialization.json.JsonObject
        }

    @Test
    fun `builders emit the documented frame shapes`() {
        val auth = roundtrip(buildAuthFrame("tok"))
        assertEquals("auth", auth["type"].toString().trim('"'))
        assertEquals("tok", auth["token"].toString().trim('"'))

        val initiate = roundtrip(
            buildInitiateFrame("c1", "u2", "video", "r1", "Alice")
        )
        assertEquals("call:initiate", initiate["type"].toString().trim('"'))
        assertEquals("c1", initiate["callId"].toString().trim('"'))
        assertEquals("u2", initiate["receiverId"].toString().trim('"'))
        assertEquals("video", initiate["media"].toString().trim('"'))

        val offer = buildOfferFrame("c1", "v=0 offer")
        assertTrue(offer.contains("\"type\":\"call:offer\""))
        assertTrue(offer.contains("\"type\":\"offer\""))
        assertTrue(offer.contains("v=0 offer"))

        val candidate = roundtrip(buildCandidateFrame("c1", "candidate:1", "0", 2))
        assertEquals("call:ice-candidate", candidate["type"].toString().trim('"'))
        val inner = candidate["candidate"] as kotlinx.serialization.json.JsonObject
        assertEquals("candidate:1", inner["candidate"].toString().trim('"'))
        assertEquals("2", inner["sdpMLineIndex"].toString())

        val reject = roundtrip(buildRejectFrame("c1", "busy"))
        assertEquals("call:reject", reject["type"].toString().trim('"'))
        assertEquals("busy", reject["reason"].toString().trim('"'))

        val hangup = roundtrip(buildHangupFrame("c1", "hangup"))
        assertEquals("call:hangup", hangup["type"].toString().trim('"'))
    }

    // ── mapping onto the existing RtcEvent vocabulary ────────────────────

    @Test
    fun `lifecycle frames map to server events CallViewModel already handles`() {
        assertEquals(RtcEvent.ServerEvent("ringing"), SignalingFrame.Ringing("c").toRtcEvent())
        assertEquals(RtcEvent.ServerEvent("accepted"), SignalingFrame.Accepted("c").toRtcEvent())
        assertEquals(RtcEvent.ServerEvent("busy"), SignalingFrame.Busy("c").toRtcEvent())
        assertEquals(RtcEvent.ServerEvent("busy"), SignalingFrame.Rejected("c", "busy").toRtcEvent())
        assertEquals(RtcEvent.ServerEvent("declined", "nope"), SignalingFrame.Rejected("c", "nope").toRtcEvent())
        assertEquals(RtcEvent.ServerEvent("failed", "offline"), SignalingFrame.Failed("c", "offline").toRtcEvent())
        assertEquals(RtcEvent.ServerEvent("timeout"), SignalingFrame.Timeout("c").toRtcEvent())
        assertEquals(RtcEvent.ServerEvent("ended", "disconnect"), SignalingFrame.Hangup("c", "disconnect").toRtcEvent())
        assertEquals(RtcEvent.Error("forbidden"), SignalingFrame.ErrorMsg("forbidden").toRtcEvent())
    }

    @Test
    fun `media frames map to rtc events`() {
        val offer = SignalingFrame.Offer("c", "v=0").toRtcEvent()
        assertTrue(offer is RtcEvent.Offer && offer.sdp == "v=0" && offer.requestId == 0)

        val answer = SignalingFrame.Answer("c", "v=0 a").toRtcEvent()
        assertTrue(answer is RtcEvent.Answer && answer.sdp == "v=0 a")

        val cand = SignalingFrame.Candidate("c", "candidate:9", "1", 3).toRtcEvent()
        assertTrue(cand is RtcEvent.Candidate && cand.candidate == "candidate:9" &&
            cand.sdpMid == "1" && cand.sdpMLineIndex == 3)
    }

    @Test
    fun `navigation and connection frames do not leak into call events`() {
        assertNull(SignalingFrame.AuthOk("u", emptyList<IceServer>()).toRtcEvent())
        assertNull(SignalingFrame.AuthError("x").toRtcEvent())
        assertNull(SignalingFrame.Incoming("c", "u", "N", "r", "audio").toRtcEvent())
        assertNull(SignalingFrame.Pong.toRtcEvent())
    }

    @Test
    fun `log summary never contains payload values`() {
        // frameSummary is the shared log sanitizer: keys only, values never.
        val summary = frameSummary("""{"type":"call:offer","callId":"c1","sdp":{"type":"offer","sdp":"SECRET"}}""")
        assertTrue(summary.contains("keys="))
        assertTrue(!summary.contains("SECRET"))
        assertTrue(!summary.contains("sdp:") || summary.substringAfter("keys=").contains("sdp"))
    }
}
