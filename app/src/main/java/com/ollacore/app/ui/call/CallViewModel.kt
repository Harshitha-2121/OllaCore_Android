package com.ollacore.app.ui.call

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ollacore.app.OllacoreApp
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
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack


/** Call lifecycle: Outgoing dialing -> Ringing (offer sent) -> Connected on
 * answer; Incoming accept/decline; Ended. UI text: OUTGOING = "Connecting…"
 * (reaching the other side), RINGING = "Ringing…" (invite sent, waiting for
 * pickup), CONNECTED on pickup/media.
 * Terminal history truth table (writeLog): CONNECTED->COMPLETED; incoming-side
 * never-connected->MISSED; outgoing-side never-connected->CANCELLED;
 * explicit decline->DECLINED; media failure->FAILED. */
enum class CallPhase { IDLE, OUTGOING, INCOMING, RINGING, CONNECTING, CONNECTED, ENDED }

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
    val error: String? = null
)

class CallViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as OllacoreApp).container
    private val directoryRepo = container.directoryRepository
    private val sessionStore = container.sessionStore

    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    private val callLogStore = container.callLogStore

    // CLIENT-ONLY history bookkeeping (no backend): ring start + connect time.
    private var callStartEpoch: Long = 0L
    private var connectedEpoch: Long = 0L

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
    /** Incremented per call session; stale socket events from older sessions are ignored. */
    private var signalingGen: Int = 0
    /** Remote ICE received before setRemoteDescription: queued, flushed after. */
    private val pendingRemoteCandidates = ArrayDeque<IceCandidate>()
    private var answerReceived: Boolean = false
    private var offerAttempts: Int = 0
    private var rtcConnected: Boolean = false
    private var watchdogJob: Job? = null
    private var offerRetryJob: Job? = null
    /** ICE diagnostics surfaced in failure messages (no payload logged). */
    private var localCandidateCount: Int = 0
    private var remoteCandidateCount: Int = 0
    private var iceRestarts: Int = 0
    private var hadTurnServer: Boolean = false

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

    // ── Entry points ──────────────────────────────────────────────

    /** Outgoing call from chat (voice = audioOnly, video = full). */
    fun joinCall(roomId: String, audioOnly: Boolean = false, incoming: Boolean = false, peerName: String = "") {
        callStartEpoch = System.currentTimeMillis()
        connectedEpoch = 0L
        callId = java.util.UUID.randomUUID().toString()
        signalingGen++
        answerReceived = false
        offerAttempts = 0
        rtcConnected = false
        localCandidateCount = 0
        remoteCandidateCount = 0
        iceRestarts = 0
        hadTurnServer = false
        pendingRemoteCandidates.clear()
        clog(TAG_CALL_STATE, "joinCall incoming=$incoming audioOnly=$audioOnly peer=$peerName")
        _uiState.update {
            CallUiState(
                roomId = roomId,
                peerName = peerName,
                audioOnly = audioOnly,
                isIncoming = incoming,
                isVideoEnabled = !audioOnly,
                isSpeakerOn = !audioOnly, // video defaults to speaker, voice to earpiece
                phase = if (incoming) CallPhase.INCOMING else CallPhase.OUTGOING
            )
        }
        if (!incoming) {
            offerOnConnect = true
            startWatchdog()
            connectAsPeer(roomId, audioOnly)
        }
    }

    /** Accept an incoming call: join RTC as callee and wait for the offer. */
    fun acceptIncoming() {
        val roomId = _uiState.value.roomId
        if (roomId.isBlank()) return
        if (callId.isBlank()) callId = java.util.UUID.randomUUID().toString()
        signalingGen++
        answerReceived = false
        offerAttempts = 0
        rtcConnected = false
        localCandidateCount = 0
        remoteCandidateCount = 0
        iceRestarts = 0
        pendingRemoteCandidates.clear()
        clog(TAG_CALL_STATE, "acceptIncoming")
        offerOnConnect = false
        _uiState.update { it.copy(phase = CallPhase.CONNECTING, error = null) }
        startWatchdog()
        connectAsPeer(roomId, _uiState.value.audioOnly)
    }

    /** Decline an incoming call (no RTC joined). Logged as DECLINED, never missed. */
    fun declineIncoming() {
        val s = _uiState.value
        clog(TAG_CALL_STATE, "declineIncoming phase=${s.phase}")
        if (s.phase == CallPhase.INCOMING || s.phase == CallPhase.CONNECTING) {
            writeLog(
                com.ollacore.app.data.local.CallStatus.DECLINED,
                direction = com.ollacore.app.data.local.CallDirection.INCOMING,
                durationSec = 0L
            )
        }
        _uiState.update { it.copy(phase = CallPhase.ENDED) }
    }

    /** Abort before media start (e.g. camera/mic permission denied): no history, just cleanup. */
    fun abortCall(reason: String) {
        Log.w(TAG_CALL, "[call=$callId room=${_uiState.value.roomId}] abortCall: $reason")
        cancelWatchdog()
        releaseCallResources()
        _uiState.update { CallUiState(phase = CallPhase.ENDED, error = reason) }
    }

    private fun connectAsPeer(roomId: String, audioOnly: Boolean) {
        viewModelScope.launch {
            val token = sessionStore.sessionToken.first()
            if (token == null) {
                _uiState.update { it.copy(phase = CallPhase.ENDED, error = "Not authenticated") }
                return@launch
            }
            val deviceId = "android-call-${java.util.UUID.randomUUID()}"
            directoryRepo.getRoomToken(token, roomId, deviceId)
                .onSuccess { response ->
                    connectRtc(response.rtcWebsocketUrl, response.accessToken, response.iceServers, audioOnly)
                }
                .onFailure { e ->
                    _uiState.update { it.copy(phase = CallPhase.ENDED, error = e.message ?: "Failed to join call") }
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

            val gen = signalingGen
            rtcConnected = false
            rtcWebSocket = RtcWebSocket(url, token) { event ->
                viewModelScope.launch {
                    if (gen == signalingGen) handleRtcEvent(event)
                    else Log.w(TAG_CALL, "[call=$callId] ignoring stale event from older session")
                }
            }
            rtcWebSocket?.connect()
        } catch (e: Exception) {
            Log.e(TAG_CALL, "[call=$callId room=${_uiState.value.roomId}] connectRtc failed", e)
            failCall("Couldn't start the call: ${e.message ?: "camera/mic unavailable"}")
        }
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
                    markConnected()
                } else if (state == PeerConnection.IceConnectionState.DISCONNECTED ||
                    state == PeerConnection.IceConnectionState.CLOSED
                ) {
                    clog(TAG_ICE, "ice down while phase=${_uiState.value.phase}")
                    if (_uiState.value.phase == CallPhase.CONNECTED) {
                        _uiState.update {
                            it.copy(error = "Connection interrupted…")
                        }
                    }
                } else if (state == PeerConnection.IceConnectionState.FAILED) {
                    onIceFailed()
                }
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
                markConnected()
            }
        })
    }

    /**
     * ICE failed: one restart (fresh ufrag + re-offer as caller) before giving
     * up. Rescues transient NAT-mapping failures; a second FAILED is terminal.
     */
    private fun onIceFailed() {
        val phase = _uiState.value.phase
        if (phase == CallPhase.IDLE || phase == CallPhase.ENDED) return
        if (iceRestarts >= 1) {
            clog(TAG_ICE, "second ICE failure, giving up (local=$localCandidateCount remote=$remoteCandidateCount)")
            failCall(iceFailureMessage())
            return
        }
        iceRestarts++
        clog(TAG_ICE, "first ICE failure, restarting ICE (local=$localCandidateCount remote=$remoteCandidateCount)")
        _uiState.update { it.copy(error = "Reconnecting…") }
        val restarted = try {
            peerConnection?.restartIce()
            true
        } catch (e: Exception) {
            Log.e(TAG_ICE, "[call=$callId] restartIce threw", e)
            false
        }
        if (!restarted) {
            failCall(iceFailureMessage())
            return
        }
        if (!_uiState.value.isIncoming) {
            // Caller re-offers with the fresh ufrag; callee answers via the
            // normal Offer path. Watchdog still bounds the total attempt.
            answerReceived = false
            offerAttempts = 0
            createOffer()
            scheduleOfferRetry()
        }
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
    /** Watchdog: never sit on "Connecting…" forever. Logs exact state, cleans up, writes FAILED. */
    private fun startWatchdog() {
        cancelWatchdog()
        watchdogJob = viewModelScope.launch {
            delay(CONNECT_TIMEOUT_MS)
            val phase = _uiState.value.phase
            if (phase != CallPhase.CONNECTED && phase != CallPhase.ENDED && phase != CallPhase.IDLE) {
                val pc = peerConnection
                Log.e(
                    TAG_CALL,
                    "[call=$callId room=${_uiState.value.roomId}] CONNECT TIMEOUT after ${CONNECT_TIMEOUT_MS}ms: " +
                        "phase=$phase " +
                        "ice=${pc?.iceConnectionState()} gather=${pc?.iceGatheringState()} " +
                        "offerAttempts=$offerAttempts answerReceived=$answerReceived " +
                        "localCand=$localCandidateCount remoteCand=$remoteCandidateCount " +
                        "pendingRemote=${pendingRemoteCandidates.size} turn=$hadTurnServer"
                )
                if (phase == CallPhase.RINGING && !answerReceived) {
                    // Invite rang but nobody picked up: not a media failure.
                    failCall(
                        "No answer. Try again later.",
                        com.ollacore.app.data.local.CallStatus.CANCELLED
                    )
                } else {
                    failCall("Couldn't connect. Check your internet and try again.")
                }
            }
        }
    }

    private fun cancelWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
        offerRetryJob?.cancel()
        offerRetryJob = null
    }

    /** Re-send the offer if the callee joined after it was first sent (server may not buffer). */
    private fun scheduleOfferRetry() {
        offerRetryJob?.cancel()
        offerRetryJob = viewModelScope.launch {
            repeat(MAX_OFFER_ATTEMPTS - 1) { attempt ->
                delay(OFFER_RETRY_MS)
                val phase = _uiState.value.phase
                if (!answerReceived && peerConnection != null &&
                    (phase == CallPhase.OUTGOING || phase == CallPhase.RINGING ||
                        phase == CallPhase.CONNECTING)
                ) {
                    clog(TAG_SDP, "no answer yet, re-sending offer (${attempt + 2}/$MAX_OFFER_ATTEMPTS)")
                    createOffer()
                } else return@launch
            }
        }
    }

    private fun failCall(
        reason: String,
        status: com.ollacore.app.data.local.CallStatus =
            com.ollacore.app.data.local.CallStatus.FAILED
    ) {
        val s = _uiState.value
        if (s.phase == CallPhase.IDLE || s.phase == CallPhase.ENDED) return
        Log.e(TAG_CALL, "[call=$callId room=${_uiState.value.roomId}] failCall: $reason phase=${s.phase}")
        val direction = if (s.isIncoming) com.ollacore.app.data.local.CallDirection.INCOMING
        else com.ollacore.app.data.local.CallDirection.OUTGOING
        writeLog(status, direction, 0L)
        cancelWatchdog()
        releaseCallResources()
        _uiState.update { it.copy(phase = CallPhase.ENDED, error = reason) }
    }

    private fun addRemoteStream(stream: MediaStream) {
        _uiState.update { state ->
            if (state.remoteStreams.any { it.id == stream.id }) state
            else state.copy(remoteStreams = state.remoteStreams + stream)
        }
        markConnected()
    }

    private fun removeRemoteStream(streamId: String) {
        _uiState.update { state ->
            state.copy(remoteStreams = state.remoteStreams.filter { it.id != streamId })
        }
    }

    private fun markConnected() {
        var became = false
        _uiState.update {
            if (it.phase == CallPhase.OUTGOING || it.phase == CallPhase.RINGING ||
                it.phase == CallPhase.CONNECTING || it.phase == CallPhase.INCOMING
            ) {
                became = true
                it.copy(phase = CallPhase.CONNECTED, error = null)
            } else it
        }
        if (became) {
            if (connectedEpoch == 0L) connectedEpoch = System.currentTimeMillis()
            clog(TAG_CALL_STATE, "CONNECTED")
            cancelWatchdog()
        }
    }

    /** CLIENT-ONLY history write (fire-and-forget; scope may not survive process kill - acceptable v1). */
    private fun writeLog(
        status: com.ollacore.app.data.local.CallStatus,
        direction: com.ollacore.app.data.local.CallDirection,
        durationSec: Long
    ) {
        val s = _uiState.value
        if (s.roomId.isBlank() || callStartEpoch == 0L) return
        val id = if (callId.isNotBlank()) "call-$callId" else "call-${java.util.UUID.randomUUID()}"
        clog(TAG_CALL_STATE, "history status=$status direction=$direction duration=${durationSec}s")
        val entry = com.ollacore.app.data.local.CallLogEntry(
            id = id,
            roomId = s.roomId,
            peerName = s.peerName,
            direction = direction,
            audioOnly = s.audioOnly,
            startedAt = callStartEpoch,
            durationSec = durationSec,
            status = status
        )
        viewModelScope.launch { runCatching { callLogStore.log(entry) } }
    }

    private suspend fun handleRtcEvent(event: RtcEvent) {
        when (event) {
            is RtcEvent.Connected -> {
                if (rtcConnected) {
                    clog(TAG_CALL_STATE, "duplicate socket Connected ignored")
                    return
                }
                rtcConnected = true
                clog(TAG_CALL_STATE, "socket connected, offerOnConnect=$offerOnConnect")
                if (offerOnConnect) {
                    _uiState.update { it.copy(phase = CallPhase.CONNECTING) }
                    createOffer()
                    scheduleOfferRetry()
                }
                // Callee waits for the offer here (phase stays CONNECTING).
            }
            is RtcEvent.Answer -> {
                answerReceived = true
                clog(TAG_SDP, "answer received len=${event.sdp.length} (peer picked up)")
                setRemoteDescription(SessionDescription.Type.ANSWER, event.sdp)
                // Pickup shows Connected; ICE still handshakes underneath and
                // onIceFailed() still converts a dead handshake into FAILED.
                markConnected()
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
            is RtcEvent.ServerEvent -> when (event.event) {
                "error" -> {
                    Log.e(TAG_CALL, "[call=$callId] server error: ${event.reason}")
                    _uiState.update { it.copy(error = event.reason ?: "Call error") }
                }
                "ended", "call_ended" -> {
                    clog(TAG_CALL_STATE, "remote ended")
                    _uiState.update { it.copy(phase = CallPhase.ENDED) }
                }
                else -> clog(TAG_CALL_STATE, "server event ignored: ${event.event}")
            }
            is RtcEvent.Error -> {
                Log.e(TAG_CALL, "[call=$callId] signaling error: ${event.message}")
                _uiState.update { it.copy(error = event.message) }
            }
            is RtcEvent.Disconnected -> {
                // Media can survive a signaling drop mid-call: keep it alive with a
                // note instead of killing CONNECTED calls. Pre-connect drops end it.
                if (_uiState.value.phase == CallPhase.CONNECTED) {
                    Log.w(TAG_CALL, "[call=$callId] signaling lost mid-call, media kept alive")
                    _uiState.update { it.copy(error = "Connection interrupted…") }
                } else if (_uiState.value.phase != CallPhase.IDLE && _uiState.value.phase != CallPhase.ENDED) {
                    clog(TAG_CALL_STATE, "socket dropped before connect, ending")
                    _uiState.update { it.copy(phase = CallPhase.ENDED) }
                }
            }
        }
    }

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
                        _uiState.update {
                            if (it.phase == CallPhase.OUTGOING || it.phase == CallPhase.CONNECTING) {
                                it.copy(phase = CallPhase.RINGING)
                            } else it
                        }
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
        if (_uiState.value.isScreenSharing) {
            _uiState.update { it.copy(isScreenSharing = false, localVideoTrack = cameraVideoTrack) }
        }
    }

    // Legacy toggle kept for compat: stopping only (starting needs permission result via startScreenShare).
    fun toggleScreenShare() {
        if (_uiState.value.isScreenSharing) stopScreenShare()
    }

    fun endCall() {
        // Truth table (never MISSED for the caller):
        // CONNECTED -> COMPLETED w/ duration; incoming-side never-connected ->
        // MISSED; outgoing-side never-connected -> CANCELLED.
        val s = _uiState.value
        if (s.phase != CallPhase.IDLE && s.phase != CallPhase.ENDED) {
            val direction = if (s.isIncoming) com.ollacore.app.data.local.CallDirection.INCOMING
            else com.ollacore.app.data.local.CallDirection.OUTGOING
            if (s.phase == CallPhase.CONNECTED && connectedEpoch > 0L) {
                writeLog(
                    com.ollacore.app.data.local.CallStatus.COMPLETED,
                    direction,
                    (System.currentTimeMillis() - connectedEpoch) / 1000L
                )
            } else if (s.isIncoming &&
                (s.phase == CallPhase.INCOMING || s.phase == CallPhase.CONNECTING)
            ) {
                writeLog(com.ollacore.app.data.local.CallStatus.MISSED, direction, 0L)
            } else {
                writeLog(com.ollacore.app.data.local.CallStatus.CANCELLED, direction, 0L)
            }
        }
        cancelWatchdog()
        releaseCallResources()
        _uiState.update { CallUiState() }
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
    }

    override fun onCleared() {
        endCall()
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

