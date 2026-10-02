package com.ollacore.app.ui.call

/**
 * Call lifecycle states (WhatsApp-like UX). Terminal states are absorbing:
 * once a call ends there is no transition out of it - cleanup happens outside
 * the machine, history is written exactly once, and late socket/media events
 * are ignored by the ViewModel because [CallStateMachine.next] returns null.
 *
 * Spec mapping: CALLING = caller dialing (offer not yet on the wire),
 * RINGING = invite on the wire (callee phone ringing), INCOMING = callee
 * ringing locally (accept/decline), CONNECTING = accepted, waiting for media,
 * RECONNECTING = media or signaling lost mid-call and recovering, CONNECTED =
 * media flowing.
 */
enum class CallPhase {
    IDLE,
    CALLING,
    INCOMING,
    RINGING,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ENDED,
    DECLINED,
    BUSY,
    FAILED,
    MISSED;

    val isTerminal: Boolean
        get() = this == ENDED || this == DECLINED || this == BUSY ||
            this == FAILED || this == MISSED

    val isRinging: Boolean
        get() = this == CALLING || this == INCOMING || this == RINGING

    val isLive: Boolean
        get() = this != IDLE && !isTerminal
}

/** Events that drive the call lifecycle (fired by the ViewModel only). */
enum class CallEvent {
    /** User placed an outgoing call. */
    DIAL,
    /** Incoming invite received (callee side, ringing locally). */
    INCOMING,
    /** Local SDP offer was accepted by the signaling socket (on the wire). */
    OFFER_SENT,
    /** Callee accepted the invite. */
    ACCEPT,
    /** Remote answered (SDP answer arrived / pickup confirmed). */
    ANSWER,
    /** Media path is up (ICE connected/completed or remote track arrived). */
    MEDIA_UP,
    /** Signaling socket dropped (pre-established or mid-call). */
    SIGNAL_LOST,
    /** Signaling socket came back. */
    SIGNAL_BACK,
    /** Media path dropped (ICE disconnected; recovery restart in flight). */
    ICE_LOST,
    /** ICE recovery exhausted - media failed terminally. */
    ICE_DOWN,
    /** Remote explicitly declined. */
    REMOTE_DECLINE,
    /** Remote is busy. */
    REMOTE_BUSY,
    /** Remote hung up / call was cancelled from the other side. */
    REMOTE_HANGUP,
    /** User ended the call locally. */
    LOCAL_HANGUP,
    /** Callee rejected the incoming invite locally. */
    DECLINE,
    /** Ring timeout: nobody picked up (fired per-side with side-appropriate result). */
    NO_ANSWER,
    /** Connection timeout: accepted/dialed but media never came up. */
    TIMEOUT,
    /** Local abort before media start (permissions, missing camera, etc.). */
    ABORT,
    /** Fatal error (socket/auth/media). */
    FAIL
}

/**
 * Enforced call state machine: pure, JVM-testable, no Android dependencies.
 * Illegal transitions return null so callers can log and drop them instead of
 * silently corrupting the call (e.g. MEDIA_UP after hangup, ANSWER in IDLE).
 */
object CallStateMachine {

    private val transitions: Map<Pair<CallPhase, CallEvent>, CallPhase> = buildMap {
        // IDLE: only a dial or an incoming invite may start a call.
        put(CallPhase.IDLE to CallEvent.DIAL, CallPhase.CALLING)
        put(CallPhase.IDLE to CallEvent.INCOMING, CallPhase.INCOMING)
        put(CallPhase.IDLE to CallEvent.ABORT, CallPhase.ENDED)
        put(CallPhase.IDLE to CallEvent.FAIL, CallPhase.FAILED)

        // CALLING: offer being created / socket connecting.
        put(CallPhase.CALLING to CallEvent.OFFER_SENT, CallPhase.RINGING)
        put(CallPhase.CALLING to CallEvent.ANSWER, CallPhase.CONNECTING)
        put(CallPhase.CALLING to CallEvent.MEDIA_UP, CallPhase.CONNECTED)
        put(CallPhase.CALLING to CallEvent.SIGNAL_LOST, CallPhase.RECONNECTING)
        put(CallPhase.CALLING to CallEvent.ICE_LOST, CallPhase.RECONNECTING)
        put(CallPhase.CALLING to CallEvent.LOCAL_HANGUP, CallPhase.ENDED)
        put(CallPhase.CALLING to CallEvent.REMOTE_HANGUP, CallPhase.ENDED)
        put(CallPhase.CALLING to CallEvent.REMOTE_DECLINE, CallPhase.DECLINED)
        put(CallPhase.CALLING to CallEvent.REMOTE_BUSY, CallPhase.BUSY)
        put(CallPhase.CALLING to CallEvent.NO_ANSWER, CallPhase.ENDED)
        put(CallPhase.CALLING to CallEvent.TIMEOUT, CallPhase.FAILED)
        put(CallPhase.CALLING to CallEvent.ABORT, CallPhase.ENDED)
        put(CallPhase.CALLING to CallEvent.FAIL, CallPhase.FAILED)

        // RINGING: invite on the wire (caller waits for pickup).
        put(CallPhase.RINGING to CallEvent.OFFER_SENT, CallPhase.RINGING) // offer retry
        put(CallPhase.RINGING to CallEvent.ANSWER, CallPhase.CONNECTING)
        put(CallPhase.RINGING to CallEvent.MEDIA_UP, CallPhase.CONNECTED)
        put(CallPhase.RINGING to CallEvent.SIGNAL_LOST, CallPhase.RECONNECTING)
        put(CallPhase.RINGING to CallEvent.ICE_LOST, CallPhase.RECONNECTING)
        put(CallPhase.RINGING to CallEvent.LOCAL_HANGUP, CallPhase.ENDED) // cancel
        put(CallPhase.RINGING to CallEvent.REMOTE_HANGUP, CallPhase.ENDED)
        put(CallPhase.RINGING to CallEvent.REMOTE_DECLINE, CallPhase.DECLINED)
        put(CallPhase.RINGING to CallEvent.REMOTE_BUSY, CallPhase.BUSY)
        put(CallPhase.RINGING to CallEvent.NO_ANSWER, CallPhase.ENDED) // ring timeout
        put(CallPhase.RINGING to CallEvent.ABORT, CallPhase.ENDED)
        put(CallPhase.RINGING to CallEvent.FAIL, CallPhase.FAILED)

        // INCOMING: callee ringing locally, not yet accepted.
        put(CallPhase.INCOMING to CallEvent.ACCEPT, CallPhase.CONNECTING)
        put(CallPhase.INCOMING to CallEvent.DECLINE, CallPhase.DECLINED)
        put(CallPhase.INCOMING to CallEvent.LOCAL_HANGUP, CallPhase.DECLINED)
        put(CallPhase.INCOMING to CallEvent.REMOTE_HANGUP, CallPhase.MISSED) // caller cancelled
        put(CallPhase.INCOMING to CallEvent.NO_ANSWER, CallPhase.MISSED) // local ring timeout
        put(CallPhase.INCOMING to CallEvent.TIMEOUT, CallPhase.MISSED)
        put(CallPhase.INCOMING to CallEvent.SIGNAL_LOST, CallPhase.RECONNECTING)
        put(CallPhase.INCOMING to CallEvent.ABORT, CallPhase.ENDED)
        put(CallPhase.INCOMING to CallEvent.FAIL, CallPhase.FAILED)

        // CONNECTING: accepted, waiting for media.
        put(CallPhase.CONNECTING to CallEvent.ANSWER, CallPhase.CONNECTING) // duplicate answer
        put(CallPhase.CONNECTING to CallEvent.OFFER_SENT, CallPhase.CONNECTING) // re-offer
        put(CallPhase.CONNECTING to CallEvent.MEDIA_UP, CallPhase.CONNECTED)
        put(CallPhase.CONNECTING to CallEvent.SIGNAL_LOST, CallPhase.RECONNECTING)
        put(CallPhase.CONNECTING to CallEvent.ICE_LOST, CallPhase.RECONNECTING)
        put(CallPhase.CONNECTING to CallEvent.LOCAL_HANGUP, CallPhase.ENDED)
        put(CallPhase.CONNECTING to CallEvent.REMOTE_HANGUP, CallPhase.ENDED)
        put(CallPhase.CONNECTING to CallEvent.TIMEOUT, CallPhase.FAILED)
        put(CallPhase.CONNECTING to CallEvent.ABORT, CallPhase.ENDED)
        put(CallPhase.CONNECTING to CallEvent.FAIL, CallPhase.FAILED)

        // CONNECTED: media flowing.
        put(CallPhase.CONNECTED to CallEvent.MEDIA_UP, CallPhase.CONNECTED) // duplicate track/ICE
        put(CallPhase.CONNECTED to CallEvent.ANSWER, CallPhase.CONNECTED) // late/duplicate answer
        put(CallPhase.CONNECTED to CallEvent.SIGNAL_LOST, CallPhase.RECONNECTING)
        put(CallPhase.CONNECTED to CallEvent.ICE_LOST, CallPhase.RECONNECTING)
        put(CallPhase.CONNECTED to CallEvent.LOCAL_HANGUP, CallPhase.ENDED)
        put(CallPhase.CONNECTED to CallEvent.REMOTE_HANGUP, CallPhase.ENDED)
        put(CallPhase.CONNECTED to CallEvent.FAIL, CallPhase.FAILED)

        // RECONNECTING: recovering media and/or signaling; bounded by watchdog.
        put(CallPhase.RECONNECTING to CallEvent.SIGNAL_BACK, CallPhase.CONNECTING)
        put(CallPhase.RECONNECTING to CallEvent.MEDIA_UP, CallPhase.CONNECTED)
        put(CallPhase.RECONNECTING to CallEvent.SIGNAL_LOST, CallPhase.RECONNECTING) // retry tick
        put(CallPhase.RECONNECTING to CallEvent.ICE_LOST, CallPhase.RECONNECTING) // dup
        put(CallPhase.RECONNECTING to CallEvent.OFFER_SENT, CallPhase.RECONNECTING) // re-offer
        put(CallPhase.RECONNECTING to CallEvent.ANSWER, CallPhase.CONNECTING)
        put(CallPhase.RECONNECTING to CallEvent.ICE_DOWN, CallPhase.FAILED)
        put(CallPhase.RECONNECTING to CallEvent.TIMEOUT, CallPhase.FAILED)
        put(CallPhase.RECONNECTING to CallEvent.LOCAL_HANGUP, CallPhase.ENDED)
        put(CallPhase.RECONNECTING to CallEvent.REMOTE_HANGUP, CallPhase.ENDED)
        put(CallPhase.RECONNECTING to CallEvent.FAIL, CallPhase.FAILED)
        put(CallPhase.RECONNECTING to CallEvent.ABORT, CallPhase.ENDED)

        // Terminal states (ENDED/DECLINED/BUSY/FAILED/MISSED) are absorbing:
        // no entries on purpose - late events return null and are dropped.
    }

    /** Next state for (from, event), or null when the transition is illegal. */
    fun next(from: CallPhase, event: CallEvent): CallPhase? = transitions[from to event]

    fun canFire(from: CallPhase, event: CallEvent): Boolean = next(from, event) != null

    /** All legal destinations from a state (used by tests/diagnostics). */
    fun legalEvents(from: CallPhase): List<CallEvent> =
        transitions.filterKeys { it.first == from }.keys.map { it.second }
}
