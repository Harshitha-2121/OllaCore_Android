package com.ollacore.app.ui.call

import org.junit.Assert.*
import org.junit.Test

class CallStateMachineTest {

    // ── Happy paths ────────────────────────────────────────────────

    @Test
    fun `outgoing happy path reaches connected then ends`() {
        var s = CallPhase.IDLE
        s = CallStateMachine.next(s, CallEvent.DIAL)!!
        assertEquals(CallPhase.CALLING, s)
        s = CallStateMachine.next(s, CallEvent.OFFER_SENT)!!
        assertEquals(CallPhase.RINGING, s)
        s = CallStateMachine.next(s, CallEvent.ANSWER)!!
        assertEquals(CallPhase.CONNECTING, s)
        s = CallStateMachine.next(s, CallEvent.MEDIA_UP)!!
        assertEquals(CallPhase.CONNECTED, s)
        s = CallStateMachine.next(s, CallEvent.LOCAL_HANGUP)!!
        assertEquals(CallPhase.ENDED, s)
    }

    @Test
    fun `incoming happy path reaches connected then remote hangup`() {
        var s = CallPhase.IDLE
        s = CallStateMachine.next(s, CallEvent.INCOMING)!!
        assertEquals(CallPhase.INCOMING, s)
        s = CallStateMachine.next(s, CallEvent.ACCEPT)!!
        assertEquals(CallPhase.CONNECTING, s)
        s = CallStateMachine.next(s, CallEvent.MEDIA_UP)!!
        assertEquals(CallPhase.CONNECTED, s)
        s = CallStateMachine.next(s, CallEvent.REMOTE_HANGUP)!!
        assertEquals(CallPhase.ENDED, s)
    }

    // ── Ringing outcomes (per-side semantics) ──────────────────────

    @Test
    fun `caller ring timeout is no-answer end not missed`() {
        val ringing = CallStateMachine.next(CallStateMachine.next(CallPhase.IDLE, CallEvent.DIAL)!!, CallEvent.OFFER_SENT)!!
        assertEquals(CallPhase.ENDED, CallStateMachine.next(ringing, CallEvent.NO_ANSWER))
    }

    @Test
    fun `callee ring timeout is missed`() {
        val incoming = CallStateMachine.next(CallPhase.IDLE, CallEvent.INCOMING)!!
        assertEquals(CallPhase.MISSED, CallStateMachine.next(incoming, CallEvent.NO_ANSWER))
    }

    @Test
    fun `callee decline is declined and caller cancel while ringing remotely is missed`() {
        assertEquals(CallPhase.DECLINED, CallStateMachine.next(CallPhase.INCOMING, CallEvent.DECLINE))
        assertEquals(CallPhase.MISSED, CallStateMachine.next(CallPhase.INCOMING, CallEvent.REMOTE_HANGUP))
        // Local end while ringing = reject.
        assertEquals(CallPhase.DECLINED, CallStateMachine.next(CallPhase.INCOMING, CallEvent.LOCAL_HANGUP))
    }

    @Test
    fun `remote decline and busy are distinct terminal states`() {
        val ringing = CallPhase.RINGING
        assertEquals(CallPhase.DECLINED, CallStateMachine.next(ringing, CallEvent.REMOTE_DECLINE))
        assertEquals(CallPhase.BUSY, CallStateMachine.next(ringing, CallEvent.REMOTE_BUSY))
        assertEquals(CallPhase.DECLINED, CallStateMachine.next(CallPhase.CALLING, CallEvent.REMOTE_DECLINE))
        assertEquals(CallPhase.BUSY, CallStateMachine.next(CallPhase.CALLING, CallEvent.REMOTE_BUSY))
    }

    @Test
    fun `offer retry is a legal no-op while already ringing`() {
        assertEquals(CallPhase.RINGING, CallStateMachine.next(CallPhase.RINGING, CallEvent.OFFER_SENT))
    }

    // ── Reconnect cycles ───────────────────────────────────────────

    @Test
    fun `ice loss and recovery cycle through reconnecting`() {
        var s = CallPhase.CONNECTED
        s = CallStateMachine.next(s, CallEvent.ICE_LOST)!!
        assertEquals(CallPhase.RECONNECTING, s)
        s = CallStateMachine.next(s, CallEvent.MEDIA_UP)!!
        assertEquals(CallPhase.CONNECTED, s)
    }

    @Test
    fun `signaling loss and recovery returns through connecting`() {
        var s = CallPhase.RINGING
        s = CallStateMachine.next(s, CallEvent.SIGNAL_LOST)!!
        assertEquals(CallPhase.RECONNECTING, s)
        s = CallStateMachine.next(s, CallEvent.SIGNAL_BACK)!!
        assertEquals(CallPhase.CONNECTING, s)
        s = CallStateMachine.next(s, CallEvent.MEDIA_UP)!!
        assertEquals(CallPhase.CONNECTED, s)
    }

    @Test
    fun `signaling recovery while media already up goes straight to connected`() {
        assertEquals(CallPhase.CONNECTED, CallStateMachine.next(CallPhase.RECONNECTING, CallEvent.MEDIA_UP))
    }

    @Test
    fun `reconnect is bounded - timeout and ice failure are terminal failure`() {
        assertEquals(CallPhase.FAILED, CallStateMachine.next(CallPhase.RECONNECTING, CallEvent.TIMEOUT))
        assertEquals(CallPhase.FAILED, CallStateMachine.next(CallPhase.RECONNECTING, CallEvent.ICE_DOWN))
    }

    @Test
    fun `repeated signal loss while reconnecting is a legal no-op`() {
        assertEquals(CallPhase.RECONNECTING, CallStateMachine.next(CallPhase.RECONNECTING, CallEvent.SIGNAL_LOST))
        assertEquals(CallPhase.RECONNECTING, CallStateMachine.next(CallPhase.RECONNECTING, CallEvent.ICE_LOST))
    }

    // ── Duplicate/idempotent events on a live call ─────────────────

    @Test
    fun `duplicate media up and answer on connected are no-ops`() {
        assertEquals(CallPhase.CONNECTED, CallStateMachine.next(CallPhase.CONNECTED, CallEvent.MEDIA_UP))
        assertEquals(CallPhase.CONNECTED, CallStateMachine.next(CallPhase.CONNECTED, CallEvent.ANSWER))
    }

    @Test
    fun `duplicate answer while connecting is a no-op`() {
        assertEquals(CallPhase.CONNECTING, CallStateMachine.next(CallPhase.CONNECTING, CallEvent.ANSWER))
    }

    // ── Illegal transitions are rejected ───────────────────────────

    @Test
    fun `idle rejects everything except dial incoming abort fail`() {
        assertNull(CallStateMachine.next(CallPhase.IDLE, CallEvent.MEDIA_UP))
        assertNull(CallStateMachine.next(CallPhase.IDLE, CallEvent.ANSWER))
        assertNull(CallStateMachine.next(CallPhase.IDLE, CallEvent.LOCAL_HANGUP))
        assertNull(CallStateMachine.next(CallPhase.IDLE, CallEvent.OFFER_SENT))
        assertNull(CallStateMachine.next(CallPhase.IDLE, CallEvent.ACCEPT))
        assertNotNull(CallStateMachine.next(CallPhase.IDLE, CallEvent.DIAL))
        assertNotNull(CallStateMachine.next(CallPhase.IDLE, CallEvent.INCOMING))
        assertNotNull(CallStateMachine.next(CallPhase.IDLE, CallEvent.ABORT))
        assertNotNull(CallStateMachine.next(CallPhase.IDLE, CallEvent.FAIL))
    }

    @Test
    fun `incoming ringing rejects offer and media until accepted`() {
        assertNull(CallStateMachine.next(CallPhase.INCOMING, CallEvent.OFFER_SENT))
        assertNull(CallStateMachine.next(CallPhase.INCOMING, CallEvent.MEDIA_UP))
        assertNull(CallStateMachine.next(CallPhase.INCOMING, CallEvent.ANSWER))
        assertNull(CallStateMachine.next(CallPhase.INCOMING, CallEvent.REMOTE_BUSY))
    }

    @Test
    fun `declining after hangup or answering after end is illegal`() {
        assertNull(CallStateMachine.next(CallPhase.ENDED, CallEvent.DECLINE))
        assertNull(CallStateMachine.next(CallPhase.CONNECTED, CallEvent.DECLINE))
        assertNull(CallStateMachine.next(CallPhase.ENDED, CallEvent.MEDIA_UP))
        assertNull(CallStateMachine.next(CallPhase.DECLINED, CallEvent.ANSWER))
        assertNull(CallStateMachine.next(CallPhase.MISSED, CallEvent.ACCEPT))
        assertNull(CallStateMachine.next(CallPhase.BUSY, CallEvent.ACCEPT))
        assertNull(CallStateMachine.next(CallPhase.FAILED, CallEvent.REMOTE_HANGUP))
    }

    @Test
    fun `every event from a terminal state is rejected`() {
        for (phase in CallPhase.values()) {
            if (!phase.isTerminal) continue
            for (event in CallEvent.values()) {
                assertNull("terminal $phase must reject $event", CallStateMachine.next(phase, event))
            }
        }
    }

    @Test
    fun `connected rejects ring-only events`() {
        assertNull(CallStateMachine.next(CallPhase.CONNECTED, CallEvent.DECLINE))
        assertNull(CallStateMachine.next(CallPhase.CONNECTED, CallEvent.NO_ANSWER))
        assertNull(CallStateMachine.next(CallPhase.CONNECTED, CallEvent.OFFER_SENT))
        assertNull(CallStateMachine.next(CallPhase.CONNECTED, CallEvent.TIMEOUT))
    }

    // ── Helpers / invariants ───────────────────────────────────────

    @Test
    fun `canFire matches next`() {
        assertTrue(CallStateMachine.canFire(CallPhase.IDLE, CallEvent.DIAL))
        assertFalse(CallStateMachine.canFire(CallPhase.IDLE, CallEvent.MEDIA_UP))
        assertFalse(CallStateMachine.canFire(CallPhase.ENDED, CallEvent.LOCAL_HANGUP))
    }

    @Test
    fun `phase helpers classify terminal ringing and live correctly`() {
        assertTrue(CallPhase.ENDED.isTerminal)
        assertTrue(CallPhase.MISSED.isTerminal)
        assertTrue(CallPhase.DECLINED.isTerminal)
        assertTrue(CallPhase.BUSY.isTerminal)
        assertTrue(CallPhase.FAILED.isTerminal)
        assertFalse(CallPhase.CONNECTED.isTerminal)
        assertFalse(CallPhase.IDLE.isTerminal)
        assertTrue(CallPhase.CALLING.isRinging)
        assertTrue(CallPhase.RINGING.isRinging)
        assertTrue(CallPhase.INCOMING.isRinging)
        assertFalse(CallPhase.CONNECTED.isRinging)
        assertTrue(CallPhase.CONNECTED.isLive)
        assertFalse(CallPhase.IDLE.isLive)
        assertFalse(CallPhase.FAILED.isLive)
    }

    @Test
    fun `legal events from idle are exactly the four starters`() {
        val legal = CallStateMachine.legalEvents(CallPhase.IDLE).toSet()
        assertEquals(
            setOf(CallEvent.DIAL, CallEvent.INCOMING, CallEvent.ABORT, CallEvent.FAIL),
            legal
        )
    }

    @Test
    fun `every legal transition is total - no missing pairs for live states`() {
        // Every live non-terminal state must accept LOCAL_HANGUP (user can always end).
        for (phase in CallPhase.values()) {
            if (phase == CallPhase.IDLE || phase.isTerminal) continue
            assertNotNull("live $phase must accept LOCAL_HANGUP", CallStateMachine.next(phase, CallEvent.LOCAL_HANGUP))
        }
    }
}
