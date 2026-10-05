package com.ollacore.app.data.remote

import android.util.Log
import com.ollacore.app.data.model.IceServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

private const val TAG_CS = "[CALL_SIG]"

/**
 * App-scoped WebSocket client for the OllaCore signaling server (WebSocket +
 * WebRTC for 1-to-1 calls; no database on the server, in-memory state only).
 *
 * Lifecycle: [connect] with the session token when it appears (AppContainer
 * observes SessionStore), [disconnect] on logout. The socket outlives single
 * calls so incoming invites arrive while the app is open; it re-authenticates
 * on every (re)connect, which also re-binds presence and cancels the server's
 * disconnect-grace cleanup of a call in progress.
 *
 * Logging hygiene mirrors [RtcWebSocket]: frame summaries are key names only -
 * SDP, ICE credentials and tokens never reach logcat.
 */

/** A ringing invite delivered by the local signaling server (drives navigation). */
data class IncomingCallRequest(
    val callId: String,
    val callerId: String,
    val callerName: String,
    val roomId: String,
    val media: String
) {
    val audioOnly: Boolean get() = media != "video"
}

enum class SignalingConnState { DISABLED, DISCONNECTED, CONNECTING, READY, RECONNECTING }

/** Server -> client frames (pure codec: parseable and testable on the JVM). */
sealed class SignalingFrame {
    data class AuthOk(val userId: String, val iceServers: List<IceServer>) : SignalingFrame()
    data class AuthError(val reason: String) : SignalingFrame()
    data class Incoming(
        val callId: String,
        val callerId: String,
        val callerName: String,
        val roomId: String,
        val media: String
    ) : SignalingFrame()
    data class Ringing(val callId: String) : SignalingFrame()
    data class Accepted(val callId: String) : SignalingFrame()
    data class Rejected(val callId: String, val reason: String) : SignalingFrame()
    data class Busy(val callId: String) : SignalingFrame()
    data class Failed(val callId: String, val reason: String) : SignalingFrame()
    data class Timeout(val callId: String) : SignalingFrame()
    data class Offer(val callId: String, val sdp: String) : SignalingFrame()
    data class Answer(val callId: String, val sdp: String) : SignalingFrame()
    data class Candidate(
        val callId: String,
        val candidate: String,
        val sdpMid: String,
        val sdpMLineIndex: Int
    ) : SignalingFrame()
    data class Hangup(val callId: String, val reason: String) : SignalingFrame()
    data class ErrorMsg(val reason: String) : SignalingFrame()
    data object Pong : SignalingFrame()
}

private val parseJson = Json { ignoreUnknownKeys = true }

// jsonPrimitive THROWS on non-primitive elements (e.g. object-form sdp):
// every access is guarded so object values simply resolve to null instead.
private fun JsonObject.str(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf { it.isNotEmpty() }

private fun JsonObject.intOrNull(key: String): Int? =
    runCatching { this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull() }.getOrNull()

private fun sdpAt(obj: JsonObject): String? =
    obj.str("sdp") ?: runCatching { obj["sdp"]?.jsonObject?.str("sdp") }.getOrNull()

private fun parseIceServers(el: JsonElement?): List<IceServer> {
    val arr = el as? JsonArray ?: return emptyList()
    return arr.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val urls: List<String> = when (val urlsEl = obj["urls"]) {
            is JsonArray -> urlsEl.mapNotNull { it.jsonPrimitive.contentOrNull }
            else -> listOfNotNull(urlsEl?.jsonPrimitive?.contentOrNull)
        }
        if (urls.isEmpty()) null
        else IceServer(urls = urls, username = obj.str("username"), credential = obj.str("credential"))
    }
}

private fun parseCandidate(obj: JsonObject): SignalingFrame.Candidate? {
    val callId = obj.str("callId") ?: return null
    val inner = runCatching { obj["candidate"]?.jsonObject }.getOrNull()
    val candidate = inner?.str("candidate") ?: obj.str("candidate")
    val sdpMid = inner?.str("sdpMid") ?: obj.str("sdpMid") ?: ""
    val sdpMLineIndex = inner?.intOrNull("sdpMLineIndex") ?: obj.intOrNull("sdpMLineIndex") ?: 0
    if (candidate.isNullOrBlank()) return null
    return SignalingFrame.Candidate(callId, candidate, sdpMid, sdpMLineIndex)
}

/** Pure inbound-frame parser: null only for unknown/garbled frames (logged, never crashed). */
fun parseSignalingFrame(text: String): SignalingFrame? {
    val obj = runCatching { parseJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    return when (val type = obj.str("type")) {
        "auth:ok" -> SignalingFrame.AuthOk(
            userId = obj.str("userId") ?: return null,
            iceServers = parseIceServers(obj["iceServers"])
        )
        "auth:error" -> SignalingFrame.AuthError(obj.str("reason") ?: "auth_error")
        "call:incoming" -> SignalingFrame.Incoming(
            callId = obj.str("callId") ?: return null,
            callerId = obj.str("callerId") ?: "",
            callerName = obj.str("callerName") ?: "",
            roomId = obj.str("roomId") ?: "",
            media = obj.str("media") ?: "audio"
        )
        "call:ringing" -> SignalingFrame.Ringing(obj.str("callId") ?: return null)
        "call:accept" -> SignalingFrame.Accepted(obj.str("callId") ?: return null)
        "call:reject" -> SignalingFrame.Rejected(
            obj.str("callId") ?: return null,
            obj.str("reason") ?: "declined"
        )
        "call:busy" -> SignalingFrame.Busy(obj.str("callId") ?: return null)
        "call:failed" -> SignalingFrame.Failed(
            obj.str("callId") ?: return null,
            obj.str("reason") ?: "failed"
        )
        "call:timeout" -> SignalingFrame.Timeout(obj.str("callId") ?: return null)
        "call:hangup" -> SignalingFrame.Hangup(
            obj.str("callId") ?: return null,
            obj.str("reason") ?: "hangup"
        )
        "call:offer" -> SignalingFrame.Offer(
            callId = obj.str("callId") ?: return null,
            sdp = sdpAt(obj) ?: return null
        )
        "call:answer" -> SignalingFrame.Answer(
            callId = obj.str("callId") ?: return null,
            sdp = sdpAt(obj) ?: return null
        )
        "call:ice-candidate" -> parseCandidate(obj)
        "error" -> SignalingFrame.ErrorMsg(obj.str("reason") ?: "error")
        "pong" -> SignalingFrame.Pong
        else -> null
    }
}

/**
 * Maps server frames onto the existing [RtcEvent] vocabulary so CallViewModel's
 * established handlers (busy/declined/ended/offer/answer/candidate/...) are
 * reused unchanged for the local-signaling path. Null = handled elsewhere
 * (auth, navigation-level incoming, pong).
 */
internal fun SignalingFrame.toRtcEvent(): RtcEvent? = when (this) {
    is SignalingFrame.Ringing -> RtcEvent.ServerEvent("ringing")
    is SignalingFrame.Accepted -> RtcEvent.ServerEvent("accepted")
    is SignalingFrame.Rejected ->
        if (reason == "busy") RtcEvent.ServerEvent("busy")
        else RtcEvent.ServerEvent("declined", reason)
    is SignalingFrame.Busy -> RtcEvent.ServerEvent("busy")
    is SignalingFrame.Failed -> RtcEvent.ServerEvent("failed", reason)
    is SignalingFrame.Timeout -> RtcEvent.ServerEvent("timeout")
    is SignalingFrame.Hangup -> RtcEvent.ServerEvent("ended", reason)
    is SignalingFrame.Offer -> RtcEvent.Offer(sdp, 0)
    is SignalingFrame.Answer -> RtcEvent.Answer(sdp)
    is SignalingFrame.Candidate -> RtcEvent.Candidate(candidate, sdpMid, sdpMLineIndex)
    is SignalingFrame.ErrorMsg -> RtcEvent.Error(reason)
    is SignalingFrame.AuthOk, is SignalingFrame.AuthError, is SignalingFrame.Incoming,
    SignalingFrame.Pong -> null
}

// ── Outbound frame builders (pure) ─────────────────────────────────────────

internal fun buildAuthFrame(token: String): String = buildJsonObject {
    put("type", "auth")
    put("token", token)
}.toString()

internal fun buildInitiateFrame(
    callId: String,
    receiverId: String,
    media: String,
    roomId: String,
    callerName: String
): String = buildJsonObject {
    put("type", "call:initiate")
    put("callId", callId)
    put("receiverId", receiverId)
    put("media", media)
    put("roomId", roomId)
    put("callerName", callerName)
}.toString()

internal fun buildRingingFrame(callId: String): String = buildJsonObject {
    put("type", "call:ringing")
    put("callId", callId)
}.toString()

internal fun buildAcceptFrame(callId: String): String = buildJsonObject {
    put("type", "call:accept")
    put("callId", callId)
}.toString()

internal fun buildRejectFrame(callId: String, reason: String): String = buildJsonObject {
    put("type", "call:reject")
    put("callId", callId)
    put("reason", reason)
}.toString()

internal fun buildOfferFrame(callId: String, sdp: String): String = buildJsonObject {
    put("type", "call:offer")
    put("callId", callId)
    put("sdp", buildJsonObject {
        put("type", "offer")
        put("sdp", sdp)
    })
}.toString()

internal fun buildAnswerFrame(callId: String, sdp: String): String = buildJsonObject {
    put("type", "call:answer")
    put("callId", callId)
    put("sdp", buildJsonObject {
        put("type", "answer")
        put("sdp", sdp)
    })
}.toString()

internal fun buildCandidateFrame(
    callId: String,
    candidate: String,
    sdpMid: String,
    sdpMLineIndex: Int
): String = buildJsonObject {
    put("type", "call:ice-candidate")
    put("callId", callId)
    put("candidate", buildJsonObject {
        put("candidate", candidate)
        put("sdpMid", sdpMid)
        put("sdpMLineIndex", sdpMLineIndex)
    })
}.toString()

internal fun buildHangupFrame(callId: String, reason: String): String = buildJsonObject {
    put("type", "call:hangup")
    put("callId", callId)
    put("reason", reason)
}.toString()

internal fun buildPingFrame(): String = """{"type":"ping"}"""

/** Frame types the server sends that mean "stop retrying" (session-level failures). */
private val TERMINAL_ERROR_REASONS = setOf("not_authenticated", "auth_timeout", "invalid_token")

/**
 * The app-scoped signaling client. One socket per app process, authenticated
 * with the user's session token (identity comes from the SERVER's response,
 * never from a client-supplied id). Send calls queue while the socket is down
 * and flush after re-auth, so brief blips don't drop the invite/hangup stream.
 */
class CallSignalingClient(
    private val baseUrl: String,
    private val scope: CoroutineScope
) {
    val isEnabled: Boolean get() = baseUrl.isNotBlank()

    private val _connectionState = MutableStateFlow(
        if (isEnabled) SignalingConnState.DISCONNECTED else SignalingConnState.DISABLED
    )
    val connectionState: StateFlow<SignalingConnState> = _connectionState.asStateFlow()

    private val _iceServers = MutableStateFlow<List<IceServer>>(emptyList())
    /** STUN/TURN from the server's auth:ok (env-configured, never hard-coded in the app). */
    val iceServers: StateFlow<List<IceServer>> = _iceServers.asStateFlow()

    private val _incomingCall = MutableStateFlow<IncomingCallRequest?>(null)
    val incomingCall: StateFlow<IncomingCallRequest?> = _incomingCall.asStateFlow()

    private val _events = MutableSharedFlow<RtcEvent>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    /** Pre-mapped [RtcEvent]s for the active call; no subscribers = dropped (no replay). */
    val events: SharedFlow<RtcEvent> = _events.asSharedFlow()

    /** True while a call is active on this device: new invites are auto-rejected as busy. */
    @Volatile
    var callActive: Boolean = false

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
    private val lock = Any()
    private val pendingSends = ArrayDeque<String>()
    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    @Volatile private var token: String? = null
    @Volatile private var closedByClient = false
    @Volatile private var authed = false
    private var attempts = 0

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /** Connect (or re-connect with a rotated token). No-op when already live with the same token. */
    fun connect(newToken: String) {
        if (!isEnabled) return
        if (newToken.isBlank()) {
            disconnect()
            return
        }
        synchronized(lock) {
            if (token == newToken && socket != null) return
            token = newToken
            closedByClient = false
            attempts = 0
            reconnectJob?.cancel()
            reconnectJob = null
        }
        openSocket()
    }

    fun disconnect() {
        if (!isEnabled) return
        closedByClient = true
        reconnectJob?.cancel()
        reconnectJob = null
        token = null
        authed = false
        synchronized(lock) { pendingSends.clear() }
        val ws = socket
        socket = null
        ws?.close(1000, "Client disconnect")
        _connectionState.value = SignalingConnState.DISCONNECTED
        emitEvent(RtcEvent.Disconnected)
    }

    /** Suspend until authenticated (or [timeoutMs]); true when READY. */
    suspend fun awaitReady(timeoutMs: Long = 10_000L): Boolean {
        if (!isEnabled) return false
        if (_connectionState.value == SignalingConnState.READY) return true
        return withTimeoutOrNull(timeoutMs) {
            _connectionState.first { it == SignalingConnState.READY }
            true
        } ?: false
    }

    private fun openSocket() {
        val tok = token ?: return
        _connectionState.value = SignalingConnState.CONNECTING
        Log.i(TAG_CS, "connecting url=${baseUrl.substringBefore('?')}")
        val request = Request.Builder().url(baseUrl).build()
        socket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Auth must be the first frame on every (re)connect; the server
                // closes sockets that send anything else first.
                webSocket.send(buildAuthFrame(tok))
                flushPending(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = runCatching { parseSignalingFrame(text) }.getOrNull()
                if (frame == null) {
                    Log.w(TAG_CS, "unknown frame dropped: ${frameSummary(text)}")
                    return
                }
                handleFrame(frame)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG_CS, "closed code=$code reason=${reason.take(60)}")
                socket = null
                authed = false
                if (closedByClient) {
                    _connectionState.value = SignalingConnState.DISCONNECTED
                    emitEvent(RtcEvent.Disconnected)
                } else {
                    scheduleReconnect("closed $code")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG_CS, "failure: ${t.message} http=${response?.code}")
                socket = null
                authed = false
                if (closedByClient) {
                    _connectionState.value = SignalingConnState.DISCONNECTED
                    emitEvent(RtcEvent.Disconnected)
                } else {
                    scheduleReconnect("failure ${t.message ?: "unknown"}")
                }
            }
        })
    }

    private fun scheduleReconnect(detail: String) {
        if (closedByClient || token == null) {
            _connectionState.value = SignalingConnState.DISCONNECTED
            return
        }
        attempts++
        val delayMs = rtcReconnectDelayMs(attempts) // 500..8000ms, capped
        Log.i(TAG_CS, "reconnect #$attempts in ${delayMs}ms ($detail)")
        _connectionState.value = SignalingConnState.RECONNECTING
        emitEvent(RtcEvent.Reconnecting(attempts, 0))
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!closedByClient) openSocket()
        }
    }

    // ── Frame dispatch ────────────────────────────────────────────────────

    private fun handleFrame(frame: SignalingFrame) {
        when (frame) {
            is SignalingFrame.AuthOk -> {
                authed = true
                attempts = 0
                _iceServers.value = frame.iceServers
                _connectionState.value = SignalingConnState.READY
                synchronized(lock) { flushPendingLocked() }
                emitEvent(RtcEvent.Connected)
                Log.i(TAG_CS, "READY user=${frame.userId} ice=${frame.iceServers.size}")
            }
            is SignalingFrame.AuthError -> {
                Log.e(TAG_CS, "auth rejected: ${frame.reason}")
                fatalStop()
                emitEvent(RtcEvent.AuthRequired)
            }
            is SignalingFrame.ErrorMsg -> {
                if (frame.reason in TERMINAL_ERROR_REASONS) {
                    Log.e(TAG_CS, "terminal server error: ${frame.reason}")
                    fatalStop()
                    emitEvent(RtcEvent.AuthRequired)
                } else {
                    Log.w(TAG_CS, "server error: ${frame.reason}")
                    emitEvent(RtcEvent.Error(frame.reason))
                }
            }
            is SignalingFrame.Incoming -> onIncoming(frame)
            is SignalingFrame.Hangup -> {
                clearIncomingIf(frame.callId)
                frame.toRtcEvent()?.let(::emitEvent)
            }
            is SignalingFrame.Timeout -> {
                clearIncomingIf(frame.callId)
                frame.toRtcEvent()?.let(::emitEvent)
            }
            else -> frame.toRtcEvent()?.let(::emitEvent)
        }
    }

    private fun onIncoming(frame: SignalingFrame.Incoming) {
        if (callActive) {
            // Already in a call (any path): reject immediately so the caller
            // gets an honest busy/decline instead of a silent ring.
            Log.i(TAG_CS, "invite while in call, auto-reject ${frame.callId}")
            send(buildRejectFrame(frame.callId, "busy"), "reject(busy)")
            return
        }
        Log.i(TAG_CS, "incoming call ${frame.callId} from=${frame.callerId} media=${frame.media}")
        _incomingCall.value = IncomingCallRequest(
            callId = frame.callId,
            callerId = frame.callerId,
            callerName = frame.callerName,
            roomId = frame.roomId,
            media = frame.media
        )
    }

    private fun clearIncomingIf(callId: String) {
        if (_incomingCall.value?.callId == callId) _incomingCall.value = null
    }

    /** The call screen consumed this invite (navigated / accepted / declined). */
    fun consumeIncoming(callId: String) = clearIncomingIf(callId)

    private fun fatalStop() {
        closedByClient = true
        authed = false
        reconnectJob?.cancel()
        val ws = socket
        socket = null
        ws?.close(4401, "auth_error")
        _connectionState.value = SignalingConnState.DISCONNECTED
    }

    private fun emitEvent(event: RtcEvent) {
        if (!_events.tryEmit(event)) Log.w(TAG_CS, "event buffer full, dropped ${eventSummary(event)}")
    }

    // ── Sending (queued while down; flushed after auth) ───────────────────

    fun sendInitiate(callId: String, receiverId: String, media: String, roomId: String, callerName: String) =
        send(buildInitiateFrame(callId, receiverId, media, roomId, callerName), "initiate")

    fun sendRinging(callId: String) = send(buildRingingFrame(callId), "ringing")

    fun sendAccept(callId: String) = send(buildAcceptFrame(callId), "accept")

    fun sendReject(callId: String, reason: String) = send(buildRejectFrame(callId, reason), "reject($reason)")

    fun sendOffer(callId: String, sdp: String) = send(buildOfferFrame(callId, sdp), "offer len=${sdp.length}")

    fun sendAnswer(callId: String, sdp: String) = send(buildAnswerFrame(callId, sdp), "answer len=${sdp.length}")

    fun sendCandidate(callId: String, candidate: String, sdpMid: String, sdpMLineIndex: Int) =
        send(buildCandidateFrame(callId, candidate, sdpMid, sdpMLineIndex), "candidate mid=$sdpMid")

    fun sendHangup(callId: String, reason: String) = send(buildHangupFrame(callId, reason), "hangup($reason)")

    fun sendPing() = send(buildPingFrame(), "ping")

    private fun send(frame: String, label: String) {
        if (!isEnabled) return
        val ws = socket
        if (ws != null && authed) {
            Log.i(TAG_CS, "send($label)")
            ws.send(frame)
        } else {
            synchronized(lock) { pendingSends.addLast(frame) }
            Log.i(TAG_CS, "queue($label): not ready")
        }
    }

    private fun flushPending(webSocket: WebSocket) {
        synchronized(lock) { flushPendingLocked(webSocket) }
    }

    private fun flushPendingLocked(webSocket: WebSocket? = socket) {
        if (webSocket == null) return
        while (pendingSends.isNotEmpty()) {
            val frame = pendingSends.removeFirst()
            Log.i(TAG_CS, "flush queued frame")
            webSocket.send(frame)
        }
    }
}
