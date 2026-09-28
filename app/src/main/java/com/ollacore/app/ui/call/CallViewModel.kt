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


/** Call lifecycle: Outgoing ringing -> Connecting -> Connected; Incoming accept/decline; Ended.
 * Terminal history truth table (writeLog): CONNECTED->COMPLETED; incoming-side
 * never-connected->MISSED; outgoing-side never-connected->CANCELLED;
 * explicit decline->DECLINED; timeout/SDP/ICE failure->FAILED. */
enum class CallPhase { IDLE, OUTGOING, INCOMING, CONNECTING, CONNECTED, ENDED }

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
        Log.i(tag, "[call=$callId room=$roomId] $msg")
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
        Log.w(TAG_CALL, "[call=$callId room=$roomId] abortCall: $reason")
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
            val configs = iceServers.map { server ->
                PeerConnection.IceServer.builder(server.urls)
                    .apply {
                        if (!server.username.isNullOrBlank()) setUsername(server.username)
                        if (!server.credential.isNullOrBlank()) setPassword(server.credential)
                    }
                    .createIceServer()
            }
            val rtcConfig = PeerConnection.RTCConfiguration(configs)
            peerConnection = createPeerConnection(rtcConfig)
            // Voice calls skip the camera entirely (no permission needed); video starts front camera.
            localStream = WebRTCUtils.createLocalStream(
                getApplication(), eglBase!!.eglBaseContext, audioOnly = audioOnly
            ) { capturer, camera ->
                videoCapturer = capturer
                cameraCapturer = camera
            }
            cameraVideoTrack = localStream?.videoTracks?.firstOrNull()
            _uiState.update { it.copy(localVideoTrack = cameraVideoTrack) }
            // Earpiece for voice, speaker for video; in-communication mode for both.
            audioManager().apply {
                mode = AudioManager.MODE_IN_COMMUNICATION
                isSpeakerphoneOn = _uiState.value.isSpeakerOn
            }

            rtcWebSocket = RtcWebSocket(url, token) { event ->
                viewModelScope.launch { handleRtcEvent(event) }
            }
            rtcWebSocket?.connect()
        } catch (e: Exception) {
            _uiState.update { it.copy(phase = CallPhase.ENDED, error = e.message ?: "Unable to initialize call") }
        }
    }

    private fun createPeerConnection(config: PeerConnection.RTCConfiguration): PeerConnection? {
        val factory = WebRTCUtils.getPeerConnectionFactory(getApplication())
        return factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                if (state == PeerConnection.IceConnectionState.CONNECTED ||
                    state == PeerConnection.IceConnectionState.COMPLETED
                ) {
                    markConnected()
                } else if (state == PeerConnection.IceConnectionState.DISCONNECTED ||
                    state == PeerConnection.IceConnectionState.CLOSED ||
                    state == PeerConnection.IceConnectionState.FAILED
                ) {
                    if (_uiState.value.phase == CallPhase.CONNECTED) {
                        _uiState.update { it.copy(phase = CallPhase.ENDED) }
                    }
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let { rtcWebSocket?.sendCandidate(it.sdp, it.sdpMid ?: "", it.sdpMLineIndex) }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
            override fun onAddStream(stream: MediaStream?) {
                stream?.let { addRemoteStream(it) }
            }
            override fun onRemoveStream(stream: MediaStream?) {
                stream?.let { removeRemoteStream(it.id) }
            }
            override fun onDataChannel(channel: DataChannel?) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<MediaStream>) {
                // Unified Plan path (modern SFU): collect every remote stream for the tiles grid.
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
            if (it.phase == CallPhase.OUTGOING || it.phase == CallPhase.CONNECTING || it.phase == CallPhase.INCOMING) {
                became = true
                it.copy(phase = CallPhase.CONNECTED)
            } else it
        }
        if (became && connectedEpoch == 0L) connectedEpoch = System.currentTimeMillis()
    }

    /** CLIENT-ONLY history write (fire-and-forget; scope may not survive process kill - acceptable v1). */
    private fun writeLog(
        status: com.ollacore.app.data.local.CallStatus,
        direction: com.ollacore.app.data.local.CallDirection,
        durationSec: Long
    ) {
        val s = _uiState.value
        if (s.roomId.isBlank() || callStartEpoch == 0L) return
        val entry = com.ollacore.app.data.local.CallLogEntry(
            id = "call-${java.util.UUID.randomUUID()}",
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
                if (offerOnConnect) {
                    _uiState.update { it.copy(phase = CallPhase.CONNECTING) }
                    createOffer()
                }
                // Callee waits for the offer here (phase stays CONNECTING).
            }
            is RtcEvent.Answer -> {
                setRemoteDescription(SessionDescription.Type.ANSWER, event.sdp)
                _uiState.update {
                    if (it.phase == CallPhase.OUTGOING || it.phase == CallPhase.CONNECTING) it.copy(phase = CallPhase.CONNECTING)
                    else it
                }
            }
            is RtcEvent.Offer -> {
                setRemoteDescription(SessionDescription.Type.OFFER, event.sdp) {
                    addLocalTracks()
                    peerConnection?.let { pc ->
                        pc.createAnswer(object : SimpleSdpObserver() {
                            override fun onCreateSuccess(description: SessionDescription) {
                                pc.setLocalDescription(object : SimpleSdpObserver() {
                                    override fun onSetSuccess() {
                                        rtcWebSocket?.sendAnswer(description.description, event.requestId)
                                    }
                                }, description)
                            }
                            override fun onCreateFailure(error: String) {
                                _uiState.update { it.copy(error = error) }
                            }
                        }, MediaConstraints())
                    }
                }
            }
            is RtcEvent.ServerEvent -> when (event.event) {
                "error" -> _uiState.update { it.copy(error = event.reason ?: "Call error") }
                "ended", "call_ended" -> _uiState.update { it.copy(phase = CallPhase.ENDED) }
                else -> Unit
            }
            is RtcEvent.Error -> _uiState.update { it.copy(error = event.message) }
            is RtcEvent.Disconnected -> {
                if (_uiState.value.phase != CallPhase.IDLE && _uiState.value.phase != CallPhase.ENDED) {
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
        addLocalTracks()
        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                pc.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        rtcWebSocket?.sendOffer(description.description)
                    }
                    override fun onSetFailure(error: String) {
                        _uiState.update { it.copy(error = error) }
                    }
                }, description)
            }
            override fun onCreateFailure(error: String) {
                _uiState.update { it.copy(error = error) }
            }
        }, MediaConstraints())
    }

    private fun setRemoteDescription(type: SessionDescription.Type, sdp: String, onSuccess: (() -> Unit)? = null) {
        peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() { onSuccess?.invoke() }
            override fun onSetFailure(error: String) { _uiState.update { it.copy(error = error) } }
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
        // CLIENT-ONLY history: connected -> completed w/ duration; rang-but-never-connected -> missed/cancelled.
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
            } else if (s.phase == CallPhase.INCOMING || s.phase == CallPhase.CONNECTING) {
                writeLog(com.ollacore.app.data.local.CallStatus.MISSED, direction, 0L)
            } else {
                writeLog(com.ollacore.app.data.local.CallStatus.CANCELLED, direction, 0L)
            }
        }
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
        _uiState.update { CallUiState() }
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
