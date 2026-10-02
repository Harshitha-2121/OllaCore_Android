package com.ollacore.app.ui.call

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ollacore.app.OllacoreApp
import com.ollacore.app.data.local.CallDirection
import com.ollacore.app.data.local.CallEndReason
import com.ollacore.app.data.local.CallLogEntry
import com.ollacore.app.data.local.CallStatus
import com.ollacore.app.data.model.IceServer
import com.ollacore.app.data.remote.RtcEvent
import com.ollacore.app.data.remote.RtcWebSocket
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RTCStatsReport
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Call engine + lifecycle. State changes go through [CallStateMachine] (enforced
 * transitions, terminal states absorbing) via [fire]; every terminal path writes
 * the history record exactly once ([writeLog] guard) and tears WebRTC down.
 *
 * Side effects are phase-driven in [onPhaseChanged]: ringtone/vibration
 * ([CallRinger]), foreground service start/stop, connect watchdog, duration
 * ticker and quality stats. Signaling reconnect is socket-level
 * ([RtcWebSocket] backoff) with one token-refresh rejoin as fallback; media
 * recovery is ICE restart + [ConnectivityManager] callback.
 */
data class CallUiState(
    val callId: String = "",
    val roomId: String = "",
    val peerName: String = "",
    val phase: CallPhase = CallPhase.IDLE,
    val isIncoming: Boolean = false,
    val audioOnly: Boolean = false,
    val isMuted: Boolean = false,
    val isVideoEnabled: Boolean = true,
    val isSpeakerOn: Boolean = false,
    val isScreenSharing: Boolean = false,
    val localVideoTrack: VideoTrack? = null,
    /** All remote streams (1-to-1 = first entry; group/SFU = grid). */
    val remoteStreams: List<MediaStream> = emptyList(),
    /** Seconds since media came up (0 before first connect). */
    val elapsedSec: Long = 0L,
    /** Packet-loss derived media quality (POOR shows the "Poor connection" note). */
    val quality: CallQualityLevel = CallQualityLevel.GOOD,
    val error: String? = null
)

class CallViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as OllacoreApp).container
    private val directoryRepo = container.directoryRepository
    private val sessionStore = container.sessionStore
    private val callLogStore = container.callLogStore

    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    // CLIENT-ONLY history bookkeeping (no backend): ring start + connect time.
    private var callStartEpoch: Long = 0L
    private var connectedEpoch: Long = 0L
    private var logWritten: Boolean = false

    private var rtcWebSocket: RtcWebSocket? = null
    private var peerConnection: PeerConnection? = null
    private var localStream: MediaStream? = null
    private var eglBase: EglBase? = null
    private var videoCapturer: VideoCapturer? = null
    private var cameraCapturer: CameraVideoCapturer? = null
    private var cameraVideoTrack: VideoTrack? = null
    private var videoSender: RtpSender? = null
    private var offerOnConnect: Boolean = true

    // ── Signaling diagnostics + race guards ──
    /** Local call id for logs/history (server call_id is not passed to this route). */
    private var callId: String = ""
    /** Incremented per call session (and per signaling rejoin); stale socket events are ignored. */
    private var signalingGen: Int = 0
    /** Remote ICE received before setRemoteDescription: queued, flushed after. */
    private val pendingRemoteCandidates = ArrayDeque<IceCandidate>()
    private var answerReceived: Boolean = false
    private var acceptedByMe: Boolean = false
    private var offerAttempts: Int = 0
    private var rtcConnected: Boolean = false
    private var connectWatchdogJob: Job? = null
    private var offerRetryJob: Job? = null
    private var durationJob: Job? = null
    private var statsJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    /** ICE diagnostics surfaced in failure messages (no payload logged). */
    private var localCandidateCount: Int = 0
    private var remoteCandidateCount: Int = 0
    private var iceRestarts: Int = 0
    private var hadTurnServer: Boolean = false
    /** One full signaling rejoin (fresh room token) per call after socket retries are exhausted. */
    private var rejoinAttempted: Boolean = false
    /** Quality stats: last inbound-rtp sample (WebRTC stats thread only). */
    private var lastQualitySample: CallQuality.Sample? = null
    private var lastNetworkRestartAt: Long = 0L
    /** Serializes [fire] so concurrent threads (main + WebRTC native) can't interleave transitions. */
    private val fireLock = Any()

    private companion object {
        const val TAG_CALL = "[CALL]"
        const val TAG_WEBRTC = "[WEBRTC]"
        const val TAG_SDP = "[SDP]"
        const val TAG_ICE = "[ICE]"
        const val TAG_MEDIA = "[MEDIA]"
        const val TAG_CALL_STATE = "[CALL_STATE]"
        const val CONNECT_TIMEOUT_MS = 45_000L
        const val OFFER_RETRY_MS = 12_000L
        const val MAX_OFFER_ATTEMPTS = 3
        const val MAX_ICE_RESTARTS = 3
        const val QUALITY_STATS_MS = 3_000L
        const val NETWORK_RESTART_MIN_GAP_MS = 5_000L
    }

    private fun clog(tag: String, msg: String) {
        Log.i(tag, "[call=$callId room=${_uiState.value.roomId}] $msg")
    }

    // Screen share (real MediaProjection, replaces previous mock toggle)
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var screenTrack: VideoTrack? = null
    private var screenHelper: SurfaceTextureHelper? = null

    private fun audioManager(): AudioManager =
        getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // ── State machine ─────────────────────────────────────────────

    /**
     * The only place the phase changes. Returns false for illegal transitions
     * (logged and dropped) - terminal states are absorbing.
     */
    private fun fire(event: CallEvent): Boolean = synchronized(fireLock) {
        val from = _uiState.value.phase
        val to = CallStateMachine.next(from, event)
        if (to == null) {
            Log.w(TAG_CALL_STATE, "[call=$callId] illegal transition: $from + $event (dropped)")
            return false
        }
        if (to != from) clog(TAG_CALL_STATE, "$from -${event.name}-> $to")
        _uiState.update {
            it.copy(
                phase = to,
                error = when (to) {
                    CallPhase.CONNECTED -> null          // banner cleared when media is up
                    CallPhase.RECONNECTING -> "Reconnecting…"
                    else -> it.error
                }
            )
        }
        true
    }

    /**
     * Phase-driven side effects. Runs on the main thread via a StateFlow
     * collector (conflation is safe: effects always derive from the latest
     * phase, intermediate values never change the outcome).
     */
    init {
        viewModelScope.launch {
            var prev = CallPhase.IDLE
            _uiState.collect { state ->
                val now = state.phase
                if (now != prev) {
                    val from = prev
                    prev = now
                    onPhaseChanged(from, now)
                }
            }
        }
    }

    private fun onPhaseChanged(from: CallPhase, to: CallPhase) {
        val ctx = getApplication<Application>()
        // Ringtone / vibration / ringback (single process-wide source of truth).
        val mode = when {
            to == CallPhase.INCOMING -> CallRinger.Mode.INCOMING
            to == CallPhase.CALLING || to == CallPhase.RINGING ||
                to == CallPhase.CONNECTING || to == CallPhase.RECONNECTING ->
                if (!isIncomingSafe() && !answerReceived) CallRinger.Mode.RINGBACK
                else CallRinger.Mode.NONE
            else -> CallRinger.Mode.NONE
        }
        CallRinger.setMode(ctx, mode)

        when {
            to == CallPhase.CONNECTING -> restartConnectWatchdog() // accepted: bound media wait
            to == CallPhase.RECONNECTING && connectWatchdogJob == null -> startConnectWatchdog()
            to == CallPhase.CONNECTED -> {
                cancelConnectWatchdog()
                startDurationTicker()
                startQualityStats()
            }
            to.isTerminal || to == CallPhase.IDLE -> {
                CallRinger.stop()
                CallForegroundService.stop(ctx)
                cancelConnectWatchdog()
                cancelDurationAndStats()
            }
        }
    }

    private fun isIncomingSafe(): Boolean = _uiState.value.isIncoming

    // ── Entry points ──────────────────────────────────────────────

    /** Outgoing call from chat (voice = audioOnly, video = full). Also callee-side init. */
    fun joinCall(roomId: String, audioOnly: Boolean = false, incoming: Boolean = false, peerName: String = "") {
        // Re-entry guard: rotation/recomposition re-runs the nav LaunchedEffect -
        // never restart a live call for the same room on top of itself.
        if (_uiState.value.phase.isLive) {
            if (_uiState.value.roomId == roomId) {
                clog(TAG_CALL_STATE, "joinCall ignored: call already live for this room")
                return
            }
            endCall() // different room while somehow live: finish the old one first
        }
        callStartEpoch = System.currentTimeMillis()
        connectedEpoch = 0L
        callId = java.util.UUID.randomUUID().toString()
        signalingGen++
        answerReceived = false
        acceptedByMe = false
        offerAttempts = 0
        rtcConnected = false
        localCandidateCount = 0
        remoteCandidateCount = 0
        iceRestarts = 0
        hadTurnServer = false
        logWritten = false
        rejoinAttempted = false
        lastQualitySample = null
        pendingRemoteCandidates.clear()
        clog(TAG_CALL_STATE, "joinCall incoming=$incoming audioOnly=$audioOnly peer=$peerName")
        _uiState.update {
            CallUiState(
                callId = callId,
                roomId = roomId,
                peerName = peerName,
                audioOnly = audioOnly,
                isIncoming = incoming,
                isVideoEnabled = !audioOnly,
                isSpeakerOn = !audioOnly // video defaults to speaker, voice to earpiece
            )
        }
        fire(if (incoming) CallEvent.INCOMING else CallEvent.DIAL)
        // Foreground service (mic/camera types) keeps the call alive when backgrounded.
        CallForegroundService.start(getApplication(), peerName, audioOnly)
        registerNetworkCallback()
        startConnectWatchdog()
        if (!incoming) {
            offerOnConnect = true
            connectAsPeer(roomId, audioOnly)
        } else {
            offerOnConnect = false
        }
    }

    /** Accept an incoming call: join RTC as callee and wait for the offer. */
    fun acceptIncoming() {
        val roomId = _uiState.value.roomId
        if (roomId.isBlank()) return
        if (callId.isBlank()) callId = java.util.UUID.randomUUID().toString()
        signalingGen++
        answerReceived = false
        acceptedByMe = true
        offerAttempts = 0
        rtcConnected = false
        localCandidateCount = 0
        remoteCandidateCount = 0
        iceRestarts = 0
        pendingRemoteCandidates.clear()
        clog(TAG_CALL_STATE, "acceptIncoming")
        offerOnConnect = false
        if (!fire(CallEvent.ACCEPT)) return
        startConnectWatchdog()
        connectAsPeer(roomId, _uiState.value.audioOnly)
    }

    /** Decline an incoming call (no RTC joined). Logged as DECLINED, never missed. */
    fun declineIncoming() {
        val s = _uiState.value
        clog(TAG_CALL_STATE, "declineIncoming phase=${s.phase}")
        if (s.phase == CallPhase.INCOMING) {
            finishCall(CallEvent.DECLINE, CallStatus.DECLINED, CallEndReason.DECLINED, null)
        }
    }

    /** Abort before media start (e.g. camera/mic permission denied): no history (no start epoch), just cleanup. */
    fun abortCall(reason: String) {
        Log.w(TAG_CALL, "[call=$callId room=${_uiState.value.roomId}] abortCall: $reason")
        finishCall(CallEvent.ABORT, CallStatus.FAILED, CallEndReason.ABORTED, reason)
    }

    private fun connectAsPeer(roomId: String, audioOnly: Boolean) {
        viewModelScope.launch {
            val token = sessionStore.sessionToken.first()
            if (token == null) {
                finishCall(CallEvent.FAIL, CallStatus.FAILED, CallEndReason.AUTH_FAILED, "Not authenticated")
                return@launch
            }
            val deviceId = "android-call-${java.util.UUID.randomUUID()}"
            directoryRepo.getRoomToken(token, roomId, deviceId)
                .onSuccess { response ->
                    connectRtc(response.rtcWebsocketUrl, response.accessToken, response.iceServers, audioOnly)
                }
                .onFailure { e ->
                    finishCall(
                        CallEvent.FAIL, CallStatus.FAILED, CallEndReason.JOIN_FAILED,
                        e.message ?: "Failed to join call"
                    )
                }
        }
    }

    private fun connectRtc(url: String, token: String, iceServers: List<IceServer>, audioOnly: Boolean) {
        try {
            eglBase = EglBase.create()
            val stun = iceServers.count { s -> s.urls.any { u -> u.startsWith("stun") } }
            val turn = iceServers.count { s -> s.urls.any { u -> u.startsWith("turn") } }
            // Credential values never logged.
            clog(TAG_WEBRTC, "iceServers=${iceServers.size} stun=$stun turn=$turn audioOnly=$audioOnly")
            if (turn == 0) {
                clog(TAG_WEBRTC, "STUN-only config: fine on open NATs, will fail behind symmetric NAT/restrictive firewalls (needs server TURN)")
            }
            val configs = iceServers.map { server ->
                PeerConnection.IceServer.builder(server.urls)
                    .apply {
                        if (!server.username.isNullOrBlank()) setUsername(server.username)
                        if (!server.credential.isNullOrBlank()) setPassword(server.credential)
                    }
                    .createIceServer()
            }
            val rtcConfig = PeerConnection.RTCConfiguration(configs).apply {
                // Explicit modern baseline: one bundle, muxed RTCP, TCP fallback
                // allowed (restrictive firewalls often block UDP), and gathering
                // that continues so late network paths are still found.
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
                continualGatheringPolicy =
                    PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }
            hadTurnServer = turn > 0
            peerConnection = createPeerConnection(rtcConfig)
            // Voice calls skip the camera entirely (no permission needed); video starts front camera.
            localStream = WebRTCUtils.createLocalStream(
                getApplication(), eglBase!!.eglBaseContext, audioOnly = audioOnly
            ) { capturer, camera ->
                videoCapturer = capturer
                cameraCapturer = camera
            }
            cameraVideoTrack = localStream?.videoTracks?.firstOrNull()
            clog(TAG_MEDIA, "local stream: audio=${localStream?.audioTracks?.size} video=${localStream?.videoTracks?.size}")
            _uiState.update { it.copy(localVideoTrack = cameraVideoTrack) }
            // Earpiece for voice, speaker for video; in-communication mode for both.
            audioManager().apply {
                mode = AudioManager.MODE_IN_COMMUNICATION
                isSpeakerphoneOn = _uiState.value.isSpeakerOn
            }
            rtcConnected = false
            openSignaling(url, token)
        } catch (e: Exception) {
            Log.e(TAG_CALL, "[call=$callId room=${_uiState.value.roomId}] connectRtc failed", e)
            finishCall(
                CallEvent.FAIL, CallStatus.FAILED, CallEndReason.MEDIA_ERROR,
                "Couldn't start the call: ${e.message ?: "camera/mic unavailable"}"
            )
        }
    }

    /** Attach a signaling socket (fresh call or token-refresh rejoin). */
    private fun openSignaling(url: String, token: String) {
        val gen = signalingGen
        rtcWebSocket = RtcWebSocket(url, token, viewModelScope) { event ->
            viewModelScope.launch {
                if (gen == signalingGen) handleRtcEvent(event)
                else Log.w(TAG_CALL, "[call=$callId] ignoring stale event from older session")
            }
        }
        rtcWebSocket?.connect()
    }

    private fun createPeerConnection(config: PeerConnection.RTCConfiguration): PeerConnection? {
        val factory = WebRTCUtils.getPeerConnectionFactory(getApplication())
        clog(TAG_WEBRTC, "creating PeerConnection")
        return factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                clog(TAG_SDP, "signalingState=$state")
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                clog(TAG_ICE, "iceConnectionState=$state")
                if (state == PeerConnection.IceConnectionState.CONNECTED ||
                    state == PeerConnection.IceConnectionState.COMPLETED
                ) {
                    fire(CallEvent.MEDIA_UP)
                } else if (state == PeerConnection.IceConnectionState.DISCONNECTED) {
                    // Transient drop: enter RECONNECTING (watchdog bounds it; ICE
                    // recovery or NetworkCallback restart resolves back to CONNECTED).
                    if (fire(CallEvent.ICE_LOST)) {
                        _uiState.update { it.copy(error = "Reconnecting…") }
                    }
                } else if (state == PeerConnection.IceConnectionState.FAILED) {
                    onIceFailed()
                }
                // CLOSED during teardown arrives after the terminal transition and
                // is dropped by the state machine - no special-casing needed.
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                clog(TAG_ICE, "iceGatheringState=$state")
            }
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    localCandidateCount++
                    clog(TAG_ICE, "local candidate #$localCandidateCount mid=${it.sdpMid} idx=${it.sdpMLineIndex}")
                    rtcWebSocket?.sendCandidate(it.sdp, it.sdpMid ?: "", it.sdpMLineIndex)
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
            override fun onAddStream(stream: MediaStream?) {
                clog(TAG_MEDIA, "onAddStream id=${stream?.id}")
                stream?.let { addRemoteStream(it) }
            }
            override fun onRemoveStream(stream: MediaStream?) {
                stream?.let { removeRemoteStream(it.id) }
            }
            override fun onDataChannel(channel: DataChannel?) = Unit
            override fun onRenegotiationNeeded() {
                clog(TAG_SDP, "renegotiation needed (ignored for 1-to-1 calls)")
            }
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<MediaStream>) {
                // Unified Plan path (modern SFU): collect every remote stream for the tiles grid.
                clog(TAG_MEDIA, "onAddTrack kind=${receiver.track()?.kind()} streams=${mediaStreams.size}")
                if (mediaStreams.isEmpty()) {
                    receiver.track()?.let { track ->
                        if (track is VideoTrack) {
                            val s = WebRTCUtils.getPeerConnectionFactory(getApplication())
                                .createLocalMediaStream("remote-${receiver.id()}")
                            s.addTrack(track)
                            addRemoteStream(s)
                        }
                    }
                } else {
                    mediaStreams.forEach { addRemoteStream(it) }
                }
            }
        })
    }

    /**
     * ICE failed: bounded restarts (fresh ufrag + re-offer as caller) before
     * giving up. First failure enters RECONNECTING; a failure while already
     * RECONNECTING (restarts exhausted) is terminal FAILED.
     */
    private fun onIceFailed() {
        if (!_uiState.value.phase.isLive) return
        if (_uiState.value.phase == CallPhase.RECONNECTING) {
            clog(TAG_ICE, "ICE failure after restart, giving up (local=$localCandidateCount remote=$remoteCandidateCount)")
            finishCall(CallEvent.ICE_DOWN, CallStatus.FAILED, CallEndReason.ICE_FAILED, iceFailureMessage())
            return
        }
        fire(CallEvent.ICE_LOST)
        if (!attemptIceRestart()) {
            finishCall(CallEvent.ICE_DOWN, CallStatus.FAILED, CallEndReason.ICE_FAILED, iceFailureMessage())
        }
    }

    /**
     * One ICE restart (restartIce + caller re-offer). Returns false when the
     * restart budget is spent or the restart couldn't be initiated.
     */
    private fun attemptIceRestart(): Boolean {
        if (iceRestarts >= MAX_ICE_RESTARTS) return false
        iceRestarts++
        clog(TAG_ICE, "ICE restart #$iceRestarts (local=$localCandidateCount remote=$remoteCandidateCount)")
        val restarted = try {
            peerConnection?.restartIce()
            true
        } catch (e: Exception) {
            Log.e(TAG_ICE, "[call=$callId] restartIce threw", e)
            false
        }
        if (!restarted) return false
        if (!_uiState.value.isIncoming) {
            // Caller re-offers with the fresh ufrag; callee answers via the
            // normal Offer path. Watchdog still bounds the total attempt.
            answerReceived = false
            offerAttempts = 0
            createOffer()
            scheduleOfferRetry()
        }
        return true
    }

    /** User-facing failure text with the one diagnostic that matters. */
    private fun iceFailureMessage(): String {
        val base = "Connection failed (you: $localCandidateCount network paths, " +
            "peer: $remoteCandidateCount)."
        return if (!hadTurnServer) {
            "$base Your network may block direct calls - a TURN server is required."
        } else {
            "$base Check your internet and try again."
        }
    }

    // ── Watchdog: never sit on a phase forever ────────────────────

    private fun startConnectWatchdog() {
        cancelConnectWatchdog()
        connectWatchdogJob = viewModelScope.launch {
            delay(CONNECT_TIMEOUT_MS)
            val phase = _uiState.value.phase
            if (phase == CallPhase.CONNECTED || phase.isTerminal || phase == CallPhase.IDLE) return@launch
            val pc = peerConnection
            Log.e(
                TAG_CALL,
                "[call=$callId room=${_uiState.value.roomId}] CONNECT TIMEOUT after ${CONNECT_TIMEOUT_MS}ms: " +
                    "phase=$phase " +
                    "ice=${pc?.iceConnectionState()} gather=${pc?.iceGatheringState()} " +
                    "offerAttempts=$offerAttempts answerReceived=$answerReceived acceptedByMe=$acceptedByMe " +
                    "localCand=$localCandidateCount remoteCand=$remoteCandidateCount " +
                    "pendingRemote=${pendingRemoteCandidates.size} turn=$hadTurnServer " +
                    "rejoin=$rejoinAttempted"
            )
            when (phase) {
                CallPhase.INCOMING -> // Nobody (or we) never answered: missed, not failed.
                    finishCall(CallEvent.NO_ANSWER, CallStatus.MISSED, CallEndReason.NO_ANSWER, null)
                CallPhase.RINGING -> // Invite rang, nobody picked up: caller-side no-answer.
                    finishCall(
                        CallEvent.NO_ANSWER, CallStatus.CANCELLED, CallEndReason.NO_ANSWER,
                        "No answer. Try again later."
                    )
                CallPhase.CALLING -> // Never even reached the wire.
                    finishCall(
                        CallEvent.TIMEOUT, CallStatus.FAILED, CallEndReason.TIMEOUT,
                        "Couldn't connect. Check your internet and try again."
                    )
                CallPhase.CONNECTING, CallPhase.RECONNECTING -> {
                    val wasConnected = connectedEpoch > 0
                    finishCall(
                        CallEvent.TIMEOUT, CallStatus.FAILED,
                        CallEndReason.TIMEOUT,
                        if (wasConnected) "Connection lost. Check your internet and try again."
                        else "Couldn't connect. Check your internet and try again.",
                        durationSec = elapsedSeconds(now = System.currentTimeMillis())
                    )
                }
                else -> Unit
            }
        }
    }

    private fun restartConnectWatchdog() = startConnectWatchdog()

    private fun cancelConnectWatchdog() {
        connectWatchdogJob?.cancel()
        connectWatchdogJob = null
        // offerRetryJob is intentionally NOT cancelled here: it self-terminates
        // (checks answerReceived/peerConnection/phase each tick) and cancelling
        // it on a watchdog restart would kill a still-needed offer retry.
    }

    private fun elapsedSeconds(now: Long): Long =
        if (connectedEpoch > 0L) (now - connectedEpoch) / 1000L else 0L

    /** Re-send the offer if the callee joined after it was first sent (server may not buffer). */
    private fun scheduleOfferRetry() {
        offerRetryJob?.cancel()
        offerRetryJob = viewModelScope.launch {
            repeat(MAX_OFFER_ATTEMPTS - 1) { attempt ->
                delay(OFFER_RETRY_MS)
                val phase = _uiState.value.phase
                if (!answerReceived && peerConnection != null &&
                    (phase == CallPhase.CALLING || phase == CallPhase.RINGING ||
                        phase == CallPhase.CONNECTING || phase == CallPhase.RECONNECTING)
                ) {
                    clog(TAG_SDP, "no answer yet, re-sending offer (${attempt + 2}/$MAX_OFFER_ATTEMPTS)")
                    createOffer()
                } else return@launch
            }
        }
    }

    // ── Terminal transitions (single history write + full teardown) ──

    /**
     * Fires [event] into the machine, writes the history record exactly once,
     * tears WebRTC/socket/media down and surfaces [errorMsg]. Safe to call when
     * the call already ended (no-op besides the error message).
     */
    private fun finishCall(
        event: CallEvent,
        status: CallStatus,
        endReason: String,
        errorMsg: String?,
        durationSec: Long = 0L
    ) {
        val phase = _uiState.value.phase
        if (phase.isTerminal) {
            // Already finished - just surface the error if we have one.
            if (errorMsg != null) _uiState.update { it.copy(error = errorMsg) }
            return
        }
        clog(TAG_CALL_STATE, "finish: event=${event.name} status=$status reason=$endReason phase=$phase")
        fire(event)
        writeLog(status, endReason, durationSec)
        cancelConnectWatchdog()
        releaseCallResources()
        _uiState.update { it.copy(error = errorMsg) }
    }

    private fun addRemoteStream(stream: MediaStream) {
        _uiState.update { state ->
            if (state.remoteStreams.any { it.id == stream.id }) state
            else state.copy(remoteStreams = state.remoteStreams + stream)
        }
        fire(CallEvent.MEDIA_UP)
    }

    private fun removeRemoteStream(streamId: String) {
        _uiState.update { state ->
            state.copy(remoteStreams = state.remoteStreams.filter { it.id != streamId })
        }
    }

    /** CLIENT-ONLY history write (fire-and-forget; guarded to one record per call). */
    private fun writeLog(status: CallStatus, endReason: String, durationSec: Long) {
        if (logWritten) return
        val s = _uiState.value
        if (s.roomId.isBlank() || callStartEpoch == 0L) return
        logWritten = true
        val id = if (callId.isNotBlank()) "call-$callId" else "call-${java.util.UUID.randomUUID()}"
        clog(TAG_CALL_STATE, "history status=$status direction=${if (s.isIncoming) "INCOMING" else "OUTGOING"} " +
            "duration=${durationSec}s reason=$endReason")
        val answered = connectedEpoch
        val ended = System.currentTimeMillis()
        viewModelScope.launch {
            val me = runCatching { sessionStore.userId.first() }.getOrNull() ?: ""
            val entry = CallLogEntry(
                id = id,
                roomId = s.roomId,
                peerName = s.peerName,
                direction = if (s.isIncoming) CallDirection.INCOMING else CallDirection.OUTGOING,
                audioOnly = s.audioOnly,
                startedAt = callStartEpoch,
                durationSec = durationSec,
                status = status,
                callId = callId,
                callerId = if (!s.isIncoming) me else "",
                calleeId = if (s.isIncoming) me else "",
                answeredAt = answered,
                endedAt = ended,
                endedReason = endReason
            )
            runCatching { callLogStore.log(entry) }
        }
    }

    // ── Signaling events ──────────────────────────────────────────

    private fun iceUp(): Boolean {
        val s = peerConnection?.iceConnectionState()
        return s == PeerConnection.IceConnectionState.CONNECTED ||
            s == PeerConnection.IceConnectionState.COMPLETED
    }

    private fun handleRtcEvent(event: RtcEvent) {
        when (event) {
            is RtcEvent.Connected -> onSignalingConnected()
            is RtcEvent.Answer -> {
                answerReceived = true
                clog(TAG_SDP, "answer received len=${event.sdp.length} (peer picked up)")
                fire(CallEvent.ANSWER)
                setRemoteDescription(SessionDescription.Type.ANSWER, event.sdp)
                // CONNECTED arrives from ICE/track (MEDIA_UP); the watchdog still
                // bounds a dead handshake into FAILED.
            }
            is RtcEvent.Offer -> {
                clog(TAG_SDP, "offer received len=${event.sdp.length} req=${event.requestId}")
                setRemoteDescription(SessionDescription.Type.OFFER, event.sdp) {
                    addLocalTracks()
                    peerConnection?.let { pc ->
                        pc.createAnswer(object : SimpleSdpObserver() {
                            override fun onCreateSuccess(description: SessionDescription) {
                                clog(TAG_SDP, "answer created len=${description.description.length}")
                                pc.setLocalDescription(object : SimpleSdpObserver() {
                                    override fun onSetSuccess() {
                                        rtcWebSocket?.sendAnswer(description.description, event.requestId)
                                    }
                                    override fun onSetFailure(error: String) {
                                        Log.e(TAG_SDP, "[call=$callId] answer setLocal failed: $error")
                                        _uiState.update { it.copy(error = error) }
                                    }
                                }, description)
                            }
                            override fun onCreateFailure(error: String) {
                                Log.e(TAG_SDP, "[call=$callId] answer create failed: $error")
                                _uiState.update { it.copy(error = error) }
                            }
                        }, MediaConstraints())
                    }
                }
            }
            is RtcEvent.Candidate -> {
                val pc = peerConnection
                if (pc == null) {
                    Log.w(TAG_ICE, "[call=$callId] candidate with no PeerConnection, dropped")
                    return
                }
                remoteCandidateCount++
                if (pc.remoteDescription == null) {
                    // Classic race: ICE beats the SDP. Queue until remote desc is set.
                    pendingRemoteCandidates.addLast(
                        IceCandidate(event.sdpMid, event.sdpMLineIndex, event.candidate)
                    )
                    clog(TAG_ICE, "queued remote candidate (no remote desc yet), pending=${pendingRemoteCandidates.size}")
                } else {
                    val added = try {
                        pc.addIceCandidate(
                            IceCandidate(event.sdpMid, event.sdpMLineIndex, event.candidate)
                        )
                        true
                    } catch (e: Exception) {
                        Log.e(TAG_ICE, "[call=$callId] addIceCandidate threw", e)
                        false
                    }
                    clog(TAG_ICE, "addIceCandidate mid=${event.sdpMid} idx=${event.sdpMLineIndex} ok=$added")
                }
            }
            is RtcEvent.Reconnecting -> {
                fire(CallEvent.SIGNAL_LOST)
                _uiState.update { it.copy(error = "Reconnecting… (${event.attempt}/${event.max})") }
            }
            is RtcEvent.AuthRequired -> {
                clog(TAG_CALL_STATE, "signaling auth rejected, rejoining with a fresh room token")
                onSignalingDead(CallEndReason.AUTH_FAILED, "Call session expired.")
            }
            is RtcEvent.ServerEvent -> onServerEvent(event)
            is RtcEvent.Error -> {
                Log.e(TAG_CALL, "[call=$callId] signaling error: ${event.message}")
                if (_uiState.value.phase.isLive) {
                    _uiState.update { it.copy(error = event.message) }
                }
            }
            is RtcEvent.Disconnected -> {
                clog(TAG_CALL_STATE, "signaling disconnected (retries exhausted)")
                onSignalingDead(CallEndReason.SIGNALING_LOST, "Connection lost. Check your internet and try again.")
            }
        }
    }

    /** Socket (re)opened: resolve RECONNECTING, then (re)start negotiation if we are the caller. */
    private fun onSignalingConnected() {
        rtcConnected = true
        val phase = _uiState.value.phase
        clog(TAG_CALL_STATE, "socket connected, phase=$phase offerOnConnect=$offerOnConnect")
        if (!phase.isLive) return
        if (phase == CallPhase.RECONNECTING) {
            // Media may be fine while only signaling was lost.
            if (iceUp()) fire(CallEvent.MEDIA_UP) else fire(CallEvent.SIGNAL_BACK)
            // Fresh socket: give negotiation a full offer budget again.
            offerAttempts = 0
        }
        val now = _uiState.value.phase
        if (offerOnConnect && !answerReceived && now != CallPhase.INCOMING && now != CallPhase.CONNECTED) {
            createOffer()
            scheduleOfferRetry()
        }
    }

    private fun onServerEvent(event: RtcEvent.ServerEvent) {
        when (event.event) {
            "error" -> {
                Log.e(TAG_CALL, "[call=$callId] server error: ${event.reason}")
                if (_uiState.value.phase.isLive) {
                    _uiState.update { it.copy(error = event.reason ?: "Call error") }
                }
            }
            "ended", "call_ended" -> {
                clog(TAG_CALL_STATE, "remote ended")
                finishAfterRemoteHangup()
            }
            "busy", "call_busy" -> {
                clog(TAG_CALL_STATE, "remote busy")
                finishCall(CallEvent.REMOTE_BUSY, CallStatus.BUSY, CallEndReason.BUSY, "They're busy.")
            }
            "declined", "rejected", "call_declined" -> {
                clog(TAG_CALL_STATE, "remote declined")
                finishCall(
                    CallEvent.REMOTE_DECLINE, CallStatus.DECLINED, CallEndReason.REMOTE_DECLINED,
                    "Call declined."
                )
            }
            else -> clog(TAG_CALL_STATE, "server event ignored: ${event.event}")
        }
    }

    /** Remote hung up / cancelled: history per truth table (never silent). */
    private fun finishAfterRemoteHangup() {
        val phase = _uiState.value.phase
        val wasConnected = connectedEpoch > 0L
        val duration = elapsedSeconds(System.currentTimeMillis())
        when {
            wasConnected -> finishCall(
                CallEvent.REMOTE_HANGUP, CallStatus.COMPLETED, CallEndReason.REMOTE_END,
                null, durationSec = duration
            )
            phase == CallPhase.INCOMING -> // Caller cancelled while we were ringing.
                finishCall(CallEvent.REMOTE_HANGUP, CallStatus.MISSED, CallEndReason.NO_ANSWER, null)
            phase == CallPhase.RINGING || phase == CallPhase.CALLING -> // Our invite was cancelled remotely.
                finishCall(CallEvent.REMOTE_HANGUP, CallStatus.CANCELLED, CallEndReason.REMOTE_END, null)
            else -> finishCall(CallEvent.REMOTE_HANGUP, CallStatus.FAILED, CallEndReason.REMOTE_END, null)
        }
    }

    /**
     * Signaling dead (socket retries exhausted / auth rejected): one full
     * rejoin with a fresh room token. If that is spent too, keep the call only
     * while media is actually still up (documented degraded mode), else fail.
     */
    private fun onSignalingDead(endReason: String, errorMsg: String) {
        if (!_uiState.value.phase.isLive) return
        if (!rejoinAttempted) {
            rejoinAttempted = true
            rejoinSignaling(endReason, errorMsg)
        } else if (iceUp()) {
            // Media path still fine - keep the call, be honest about the state.
            clog(TAG_CALL_STATE, "signaling dead, media up: continuing degraded")
            fire(CallEvent.MEDIA_UP)
            _uiState.update { it.copy(error = "Signaling unavailable - call continues on current media.") }
        } else {
            finishCall(CallEvent.FAIL, CallStatus.FAILED, endReason, errorMsg)
        }
    }

    private fun rejoinSignaling(endReason: String, errorMsg: String) {
        val roomId = _uiState.value.roomId
        viewModelScope.launch {
            val token = sessionStore.sessionToken.first()
            if (token == null) {
                finishCall(CallEvent.FAIL, CallStatus.FAILED, CallEndReason.AUTH_FAILED, "Not authenticated")
                return@launch
            }
            val deviceId = "android-call-${java.util.UUID.randomUUID()}"
            directoryRepo.getRoomToken(token, roomId, deviceId)
                .onSuccess { response ->
                    clog(TAG_CALL_STATE, "rejoining signaling with fresh room token")
                    runCatching { rtcWebSocket?.disconnect() }
                    signalingGen++
                    openSignaling(response.rtcWebsocketUrl, response.accessToken)
                }
                .onFailure {
                    onSignalingDead(endReason, errorMsg)
                }
        }
    }

    // ── Network recovery ──────────────────────────────────────────

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemServiceSafe() ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { onNetworkRecovered() }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onSuccess { networkCallback = cb }
            .onFailure { Log.w(TAG_ICE, "registerDefaultNetworkCallback failed: ${it.message}") }
    }

    private fun getSystemServiceSafe(): ConnectivityManager? = runCatching {
        getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }.getOrNull()

    /** A usable network came back: restart ICE if we are recovering (or look dead). */
    private fun onNetworkRecovered() {
        val phase = _uiState.value.phase
        if (!phase.isLive) return
        val ice = peerConnection?.iceConnectionState() ?: return
        val needsRestart = phase == CallPhase.RECONNECTING ||
            ice == PeerConnection.IceConnectionState.DISCONNECTED ||
            ice == PeerConnection.IceConnectionState.FAILED
        if (!needsRestart) return
        val now = System.currentTimeMillis()
        if (now - lastNetworkRestartAt < NETWORK_RESTART_MIN_GAP_MS) return
        lastNetworkRestartAt = now
        clog(TAG_ICE, "network recovered while phase=$phase ice=$ice -> ICE restart")
        attemptIceRestart()
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { cb ->
            runCatching { getSystemServiceSafe()?.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
    }

    // ── Duration + quality ────────────────────────────────────────

    private fun startDurationTicker() {
        if (durationJob != null) return
        durationJob = viewModelScope.launch {
            while (isActive) {
                val c = connectedEpoch
                if (c > 0L) {
                    _uiState.update { it.copy(elapsedSec = (System.currentTimeMillis() - c) / 1000L) }
                }
                delay(1000L)
            }
        }
    }

    private fun startQualityStats() {
        if (statsJob != null) return
        statsJob = viewModelScope.launch {
            while (isActive) {
                delay(QUALITY_STATS_MS)
                val pc = peerConnection ?: continue
                runCatching {
                    pc.getStats(RTCStatsCollectorCallback { report: RTCStatsReport ->
                        val curr = qualitySample(report) ?: return@RTCStatsCollectorCallback
                        val level = CallQuality.classify(lastQualitySample, curr)
                        lastQualitySample = curr
                        if (_uiState.value.quality != level) {
                            _uiState.update { it.copy(quality = level) }
                        }
                    })
                }
            }
        }
    }

    /** Sum inbound-rtp audio+video packets across the report (stats thread only). */
    private fun qualitySample(report: RTCStatsReport): CallQuality.Sample? {
        var received = 0L
        var lost = 0L
        var seen = false
        report.statsMap.values.forEach { stats ->
            if (stats.type == "inbound-rtp") {
                val kind = stats.members["kind"] ?: stats.members["mediaType"]
                if (kind == "audio" || kind == "video") {
                    (stats.members["packetsReceived"] as? Number)?.let {
                        received += it.toLong(); seen = true
                    }
                    (stats.members["packetsLost"] as? Number)?.let { lost += it.toLong() }
                }
            }
        }
        return if (seen) CallQuality.Sample(received, lost) else null
    }

    private fun cancelDurationAndStats() {
        durationJob?.cancel(); durationJob = null
        statsJob?.cancel(); statsJob = null
        lastQualitySample = null
        if (_uiState.value.quality != CallQualityLevel.GOOD) {
            _uiState.update { it.copy(quality = CallQualityLevel.GOOD, elapsedSec = 0L) }
        }
    }

    // ── In-call controls ──────────────────────────────────────────

    fun toggleMute() {
        val muted = !_uiState.value.isMuted
        localStream?.audioTracks?.forEach { it.setEnabled(!muted) }
        _uiState.update { it.copy(isMuted = muted) }
    }

    fun toggleVideo() {
        val enabled = !_uiState.value.isVideoEnabled
        (screenTrack ?: cameraVideoTrack)?.setEnabled(enabled)
        _uiState.update { it.copy(isVideoEnabled = enabled, localVideoTrack = screenTrack ?: cameraVideoTrack) }
    }

    fun toggleSpeaker() {
        val on = !_uiState.value.isSpeakerOn
        runCatching {
            audioManager().apply {
                mode = AudioManager.MODE_IN_COMMUNICATION
                isSpeakerphoneOn = on
            }
        }
        _uiState.update { it.copy(isSpeakerOn = on) }
    }

    fun switchCamera() {
        // Never pass a null handler: CameraCapturer invokes it on completion.
        val handler = object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(front: Boolean) = Unit
            override fun onCameraSwitchError(message: String) {
                _uiState.update { it.copy(error = "Camera switch failed: $message") }
            }
        }
        runCatching { cameraCapturer?.switchCamera(handler) }
    }

    // ── Screen share (real MediaProjection; backend re-uses the same video sender) ──

    fun startScreenShare(resultCode: Int, data: Intent) {
        if (_uiState.value.isScreenSharing) return
        val app = getApplication<Application>()
        try {
            val factory = WebRTCUtils.getPeerConnectionFactory(app)
            val capturer = ScreenCapturerAndroid(
                data,
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        super.onStop()
                        stopScreenShare()
                    }
                }
            )
            val egl = eglBase?.eglBaseContext ?: return
            screenHelper = SurfaceTextureHelper.create("ScreenShareThread", egl)
            val source: VideoSource = factory.createVideoSource(true)
            capturer.initialize(screenHelper, app, source.capturerObserver)
            capturer.startCapture(720, 1280, 30)
            screenCapturer = capturer
            screenTrack = factory.createVideoTrack("SCREENv0", source)
            // Replace the outgoing video (same m-line, no renegotiation needed).
            videoSender?.setTrack(screenTrack, true)
            CallForegroundService.setScreenShare(app, true)
            _uiState.update { it.copy(isScreenSharing = true, localVideoTrack = screenTrack) }
        } catch (e: Exception) {
            _uiState.update { it.copy(error = "Screen share failed: ${e.message}") }
        }
    }

    fun stopScreenShare() {
        runCatching { screenCapturer?.stopCapture() }
        runCatching { screenCapturer?.dispose() }
        runCatching { screenHelper?.dispose() }
        screenCapturer = null
        screenHelper = null
        // Restore camera on the same sender.
        cameraVideoTrack?.let { cam ->
            runCatching { videoSender?.setTrack(cam, true) }
        }
        screenTrack = null
        CallForegroundService.setScreenShare(getApplication(), false)
        if (_uiState.value.isScreenSharing) {
            _uiState.update { it.copy(isScreenSharing = false, localVideoTrack = cameraVideoTrack) }
        }
    }

    // Legacy toggle kept for compat: stopping only (starting needs permission result via startScreenShare).
    fun toggleScreenShare() {
        if (_uiState.value.isScreenSharing) stopScreenShare()
    }

    /**
     * User ended the call (or the call screen closed it). History truth table:
     * CONNECTED -> COMPLETED w/ duration; incoming never-answered -> MISSED;
     * outgoing never-connected -> CANCELLED; terminal -> no second record.
     */
    fun endCall() {
        val s = _uiState.value
        when {
            s.phase == CallPhase.IDLE -> {
                releaseCallResources()
                _uiState.update { CallUiState() }
            }
            s.phase.isTerminal -> {
                // Already recorded - just reset the screen.
                cancelConnectWatchdog()
                releaseCallResources()
                _uiState.update { CallUiState() }
            }
            s.phase == CallPhase.INCOMING -> {
                // Ending while ringing == rejecting.
                declineIncoming()
                _uiState.update { CallUiState() }
            }
            else -> {
                val wasConnected = connectedEpoch > 0L
                val duration = elapsedSeconds(System.currentTimeMillis())
                fire(CallEvent.LOCAL_HANGUP)
                if (wasConnected) {
                    writeLog(CallStatus.COMPLETED, CallEndReason.COMPLETED, duration)
                } else if (s.isIncoming) {
                    writeLog(CallStatus.MISSED, CallEndReason.NO_ANSWER, 0L)
                } else {
                    writeLog(CallStatus.CANCELLED, CallEndReason.LOCAL_END, 0L)
                }
                cancelConnectWatchdog()
                releaseCallResources()
                _uiState.update { CallUiState() }
            }
        }
    }

    /** Full WebRTC + socket + media teardown (never before negotiation ends - only called from end/fail paths). */
    private fun releaseCallResources() {
        clog(TAG_CALL, "releasing call resources")
        runCatching { peerConnection?.close() }
        runCatching { rtcWebSocket?.leave() }
        runCatching { rtcWebSocket?.disconnect() }
        runCatching { screenCapturer?.stopCapture() }
        runCatching { screenCapturer?.dispose() }
        runCatching { screenHelper?.dispose() }
        runCatching { localStream?.dispose() }
        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        runCatching { eglBase?.release() }
        runCatching {
            audioManager().apply {
                mode = AudioManager.MODE_NORMAL
                isSpeakerphoneOn = false
            }
        }
        unregisterNetworkCallback()
        peerConnection = null
        rtcWebSocket = null
        localStream = null
        videoCapturer = null
        cameraCapturer = null
        cameraVideoTrack = null
        videoSender = null
        screenCapturer = null
        screenTrack = null
        screenHelper = null
        eglBase = null
        pendingRemoteCandidates.clear()
        rtcConnected = false
        answerReceived = false
        lastNetworkRestartAt = 0L
    }

    // ── Offer / answer plumbing ───────────────────────────────────

    /** Attach local audio (+ camera/screen video unless voice-only); keeps the video sender for screen-share replace. */
    private fun addLocalTracks() {
        val pc = peerConnection ?: return
        val stream = localStream ?: return
        stream.audioTracks.forEach { track ->
            runCatching { pc.addTrack(track, listOf(stream.id)) }
        }
        if (!_uiState.value.audioOnly) {
            val vt = screenTrack ?: cameraVideoTrack
            vt?.let { track ->
                runCatching { videoSender = pc.addTrack(track, listOf(stream.id)) }
            }
        }
    }

    private fun createOffer() {
        val pc = peerConnection ?: return
        if (offerAttempts >= MAX_OFFER_ATTEMPTS) {
            clog(TAG_SDP, "offer attempt cap reached ($MAX_OFFER_ATTEMPTS), waiting on watchdog")
            return
        }
        offerAttempts++
        addLocalTracks()
        clog(TAG_SDP, "creating offer (attempt $offerAttempts/$MAX_OFFER_ATTEMPTS)")
        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                clog(TAG_SDP, "offer created len=${description.description.length}, setting local")
                pc.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        clog(TAG_SDP, "local offer set, sending (ringing)")
                        rtcWebSocket?.sendOffer(description.description)
                        // Invite is on the wire: receiver's phone rings now.
                        fire(CallEvent.OFFER_SENT)
                    }
                    override fun onSetFailure(error: String) {
                        Log.e(TAG_SDP, "[call=$callId] offer setLocal failed: $error")
                        _uiState.update { it.copy(error = error) }
                    }
                }, description)
            }
            override fun onCreateFailure(error: String) {
                Log.e(TAG_SDP, "[call=$callId] offer create failed: $error")
                _uiState.update { it.copy(error = error) }
            }
        }, MediaConstraints())
    }

    private fun setRemoteDescription(type: SessionDescription.Type, sdp: String, onSuccess: (() -> Unit)? = null) {
        clog(TAG_SDP, "setRemote $type len=${sdp.length}")
        peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                clog(TAG_SDP, "remote $type set, flushing ${pendingRemoteCandidates.size} queued candidates")
                val queued = pendingRemoteCandidates.toList()
                pendingRemoteCandidates.clear()
                queued.forEach { c ->
                    try {
                        peerConnection?.addIceCandidate(c)
                    } catch (e: Exception) {
                        Log.e(TAG_ICE, "[call=$callId] queued addIceCandidate threw", e)
                    }
                }
                onSuccess?.invoke()
            }
            override fun onSetFailure(error: String) {
                Log.e(TAG_SDP, "[call=$callId] setRemote $type failed: $error")
                _uiState.update { it.copy(error = error) }
            }
        }, SessionDescription(type, sdp))
    }

    override fun onCleared() {
        endCall()
        // The collector may already be cancelled: stop feedback explicitly.
        CallRinger.stop()
        CallForegroundService.stop(getApplication())
        super.onCleared()
    }
}

private open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String) = Unit
    override fun onSetFailure(error: String) = Unit
}

object WebRTCUtils {
    @Volatile private var factory: PeerConnectionFactory? = null

    fun getPeerConnectionFactory(context: Context): PeerConnectionFactory {
        return factory ?: synchronized(this) {
            factory ?: run {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
                PeerConnectionFactory.builder().createPeerConnectionFactory().also { factory = it }
            }
        }
    }

    /**
     * Local mic (+ front camera unless [audioOnly], so voice calls need no camera permission).
     * Reports both the generic capturer and the typed camera capturer (for switch-camera).
     */
    fun createLocalStream(
        context: Context,
        eglContext: EglBase.Context,
        audioOnly: Boolean = false,
        onCapturerCreated: (VideoCapturer, CameraVideoCapturer?) -> Unit = { _, _ -> }
    ): MediaStream {
        val factory = getPeerConnectionFactory(context)
        val stream = factory.createLocalMediaStream("ARDAMS")
        if (!audioOnly) {
            val capturer = createCameraCapturer(context)
            onCapturerCreated(capturer, capturer as? CameraVideoCapturer)
            val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglContext)
            val videoSource: VideoSource = factory.createVideoSource(capturer.isScreencast)
            capturer.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
            capturer.startCapture(1280, 720, 30)
            val videoTrack: VideoTrack = factory.createVideoTrack("ARDAMSv0", videoSource)
            stream.addTrack(videoTrack)
        }
        val audioSource: AudioSource = factory.createAudioSource(MediaConstraints())
        val audioTrack: AudioTrack = factory.createAudioTrack("ARDAMSa0", audioSource)
        stream.addTrack(audioTrack)
        return stream
    }

    private fun createCameraCapturer(context: Context): VideoCapturer {
        val enumerator = Camera2Enumerator(context)
        val names = enumerator.deviceNames
        val front = names.firstOrNull { enumerator.isFrontFacing(it) }
        val cameraName = front ?: names.firstOrNull() ?: error("No camera available")
        return enumerator.createCapturer(cameraName, null) ?: error("Unable to create camera capturer")
    }
}
