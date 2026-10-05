package com.ollacore.app.data.local

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * CallLogEntry v2 record: extended fields (callId/answeredAt/endedAt/endedReason/
 * callerId/calleeId) + BUSY status must decode pre-existing v1 JSON (defaults)
 * and round-trip losslessly - DataStore persists this JSON, so backward
 * compatibility is a data-loss concern, not a style one.
 */
class CallLogEntryTest {

    // Same config as CallLogStore.
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `v1 entry without new fields decodes with defaults`() {
        val v1 = """
            {"id":"call-abc","roomId":"room-1","peerName":"Ada","direction":"OUTGOING",
             "audioOnly":true,"startedAt":1700000000000,"durationSec":42,"status":"COMPLETED"}
        """.trimIndent()
        val entry = json.decodeFromString(CallLogEntry.serializer(), v1)
        assertEquals("call-abc", entry.id)
        assertEquals("room-1", entry.roomId)
        assertEquals(CallStatus.COMPLETED, entry.status)
        assertEquals(42L, entry.durationSec)
        // New fields fall back to safe defaults (never null, never garbage).
        assertEquals("", entry.callId)
        assertEquals("", entry.callerId)
        assertEquals("", entry.calleeId)
        assertEquals(0L, entry.answeredAt)
        assertEquals(0L, entry.endedAt)
        assertEquals("", entry.endedReason)
    }

    @Test
    fun `v2 entry with all fields round-trips`() {
        val entry = CallLogEntry(
            id = "call-xyz",
            roomId = "room-9",
            peerName = "Grace",
            direction = CallDirection.INCOMING,
            audioOnly = false,
            startedAt = 1710000000123L,
            durationSec = 125L,
            status = CallStatus.DECLINED,
            callId = "srv-call-1",
            callerId = "user-peer",
            calleeId = "user-me",
            answeredAt = 0L,
            endedAt = 1710000000456L,
            endedReason = CallEndReason.REMOTE_DECLINED
        )
        val decoded = json.decodeFromString(CallLogEntry.serializer(), json.encodeToString(CallLogEntry.serializer(), entry))
        assertEquals(entry, decoded)
    }

    @Test
    fun `busy status serializes and decodes`() {
        val entry = CallLogEntry(
            id = "call-busy",
            roomId = "r",
            status = CallStatus.BUSY,
            endedReason = CallEndReason.BUSY
        )
        val encoded = json.encodeToString(CallLogEntry.serializer(), entry)
        assertTrue(encoded.contains("\"BUSY\""))
        val decoded = json.decodeFromString(CallLogEntry.serializer(), encoded)
        assertEquals(CallStatus.BUSY, decoded.status)
        assertEquals(CallEndReason.BUSY, decoded.endedReason)
    }

    @Test
    fun `unknown future fields are ignored instead of crashing`() {
        val future = """
            {"id":"call-f","roomId":"r","status":"FAILED","brandNewField":{"x":1}}
        """.trimIndent()
        val entry = json.decodeFromString(CallLogEntry.serializer(), future)
        assertEquals(CallStatus.FAILED, entry.status)
        assertEquals("call-f", entry.id)
    }

    @Test
    fun `end reason constants are stable machine strings`() {
        assertEquals("completed", CallEndReason.COMPLETED)
        assertEquals("no_answer", CallEndReason.NO_ANSWER)
        assertEquals("ice_failed", CallEndReason.ICE_FAILED)
        assertEquals("signaling_lost", CallEndReason.SIGNALING_LOST)
        assertEquals("remote_declined", CallEndReason.REMOTE_DECLINED)
        assertEquals("join_failed", CallEndReason.JOIN_FAILED)
    }
}
