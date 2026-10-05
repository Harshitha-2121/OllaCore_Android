package com.ollacore.app.data.remote

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import okhttp3.*
import java.util.concurrent.TimeUnit

private const val TAG_SIGNALING = "[SIGNALING]"

/** Max signaling reconnect attempts before the ViewModel must rejoin (fresh room token) or fail. */
internal const val RTC_MAX_RECONNECT_ATTEMPTS = 5

/**
 * Exponential backoff (ms) for signaling reconnect attempt [attempt] (1-based):
 * 500, 1000, 2000, 4000, 8000, then capped at 8s. Pure - JVM-testable.
 */
internal fun rtcReconnectDelayMs(attempt: Int): Long {
    if (attempt <= 1) return 500L
    // 500 * 2^(attempt-1), shifted then capped (shift bounded to avoid overflow).
    val exp = (attempt - 1).coerceAtMost(6)
    return (500L shl exp).coerceAtMost(8_000L)
}

/**
 * Payload-free frame summary for logs: JSON keys only, never values
 * (values carry SDP, ICE credentials, candidates - never log them). Pure.
 */
internal fun frameSummary(text: String): String {
    val json = Json { ignoreUnknownKeys = true }
    val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        ?: return "unparseable(len=${text.length})"
    return "keys=" + obj.keys.sorted().joinToString(",")
}

/**
 * Pure RTC frame parser (JVM-testable): accepts every candidate shape the
 * server is known to possibly emit, so a protocol variant can never silently
 * drop ICE again. Returns null only for genuinely unknown frames (logged).
 */
fun parseRtcFrame(text: String): RtcEvent? {
    val json = Json { ignoreUnknownKeys = true }
    val frame = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        ?: return RtcEvent.Error("Parse error")
    val type = frame["type"]?.jsonPrimitive?.contentOrNull
    val event = frame["event"]?.jsonPrimitive?.contentOrNull
    val cmd = frame["cmd"]?.jsonPrimitive?.contentOrNull
    if (event != null && event != "candidate") {
        val reason = frame["reason"]?.jsonPrimitive?.contentOrNull
        return RtcEvent.ServerEvent(event, reason)
    }
    if (type == "answer" || event == "answer") {
        val sdp = frame.stringAt("sdp")
            ?: frame.jsonObjectAt("sdp")?.stringAt("sdp")
            ?: return RtcEvent.Error("Answer without sdp")
        return RtcEvent.Answer(sdp)
    }
    if (type == "offer" || event == "offer") {
        val sdp = frame.stringAt("sdp")
            ?: frame.jsonObjectAt("sdp")?.stringAt("sdp")
            ?: return RtcEvent.Error("Offer without sdp")
        val requestId = frame["request_id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        return RtcEvent.Offer(sdp, requestId)
    }
    if (type == "candidate" || event == "candidate" || cmd == "candidate") {
        val inner = frame.jsonObjectAt("candidate")
        val candidate = inner?.stringAt("candidate") ?: frame.stringAt("candidate")
        val sdpMid = inner?.stringAt("sdpMid") ?: frame.stringAt("sdpMid") ?: ""
        val sdpMLineIndex = inner?.intAt("sdpMLineIndex")
            ?: frame["sdpMLineIndex"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: 0
        if (candidate.isNullOrBlank()) return RtcEvent.Error("Candidate without payload")
        return RtcEvent.Candidate(candidate, sdpMid, sdpMLineIndex)
    }
    if (event != null) return RtcEvent.ServerEvent(event, frame["reason"]?.jsonPrimitive?.contentOrNull)
    return null
}

private fun JsonObject.stringAt(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }

private fun JsonObject.jsonObjectAt(key: String): JsonObject? =
    runCatching { this[key]?.jsonObject }.getOrNull()

private fun JsonObject.intAt(key: String): Int? =
    this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

/**
 * Signaling socket with automatic reconnect (bounded exponential backoff).
 * Emits [RtcEvent.Reconnecting] while retrying, [RtcEvent.Disconnected] when
 * retries are exhausted (or the client closed), and [RtcEvent.AuthRequired]
 * on 401/403 so the owner can rejoin with a fresh room token.
 *
 * Logging hygiene: URLs are query-redacted and frames are summarized to key
 * names/lengths only - SDP, ICE ufrag/pwd, candidates and tokens are never
 * written to logcat.
 */
class RtcWebSocket(
    private val baseUrl: String,
    private val token: String,
    private val scope: CoroutineScope,
    private val onEvent: (RtcEvent) -> Unit
) {
    private var webSocket: WebSocket? = null
    private var open = false
    private val pendingSends = ArrayDeque<String>()
    private var attempts = 0
    private var closedByClient = false

    /** Query strings can carry tokens: log only the path. */
    private fun redactedUrl(): String =
        if ('?' in baseUrl) baseUrl.substringBefore('?') + "?<redacted>" else baseUrl

    fun connect() {
        Log.i(TAG_SIGNALING, "connect url=${redactedUrl()}")
        val request = Request.Builder()
            .url(baseUrl)
            .addHeader("Sec-WebSocket-Protocol", "chatbox, bearer.$token")
            .build()

        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open = true
                attempts = 0
                Log.i(TAG_SIGNALING, "socket OPEN, flushing ${pendingSends.size} queued frames")
                while (pendingSends.isNotEmpty()) {
                    val frame = pendingSends.removeFirst()
                    Log.i(TAG_SIGNALING, "send(queued)")
                    webSocket.send(frame)
                }
                onEvent(RtcEvent.Connected)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val parsed = runCatching { parseRtcFrame(text) }.getOrNull()
                if (parsed == null) {
                    Log.w(TAG_SIGNALING, "UNKNOWN frame shape, dropped: ${frameSummary(text)}")
                    return
                }
                Log.i(TAG_SIGNALING, "recv ${eventSummary(parsed)}")
                onEvent(parsed)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                open = false
                Log.i(TAG_SIGNALING, "socket CLOSED code=$code reason=$reason")
                if (closedByClient) {
                    onEvent(RtcEvent.Disconnected)
                } else {
                    scheduleReconnect("closed code=$code")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                open = false
                val httpCode = response?.code
                Log.e(TAG_SIGNALING, "socket FAILURE: ${t.message} http=$httpCode")
                if (httpCode == 401 || httpCode == 403) {
                    // Room token expired/invalid: stop retrying, owner must rejoin.
                    closedByClient = true
                    onEvent(RtcEvent.AuthRequired)
                } else if (closedByClient) {
                    onEvent(RtcEvent.Error(t.message ?: "Connection failed"))
                } else {
                    scheduleReconnect("failure ${t.message ?: "unknown"}")
                }
            }
        })
    }

    private fun scheduleReconnect(detail: String) {
        if (attempts >= RTC_MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG_SIGNALING, "reconnect exhausted after $attempts attempts ($detail)")
            onEvent(RtcEvent.Disconnected)
            return
        }
        attempts++
        val delayMs = rtcReconnectDelayMs(attempts)
        Log.i(TAG_SIGNALING, "reconnect attempt $attempts/$RTC_MAX_RECONNECT_ATTEMPTS in ${delayMs}ms ($detail)")
        onEvent(RtcEvent.Reconnecting(attempts, RTC_MAX_RECONNECT_ATTEMPTS))
        scope.launch {
            delay(delayMs)
            if (!closedByClient) connect()
        }
    }

    private fun send(frame: String, label: String) {
        val ws = webSocket
        if (open && ws != null) {
            Log.i(TAG_SIGNALING, "send($label)")
            ws.send(frame)
        } else {
            Log.i(TAG_SIGNALING, "queue($label): socket not open yet")
            pendingSends.addLast(frame)
        }
    }

    fun sendOffer(sdp: String) {
        val frame = buildJsonObject {
            put("cmd", "offer")
            put("sdp", buildJsonObject {
                put("type", "offer")
                put("sdp", sdp)
            })
        }
        send(frame.toString(), "offer len=${sdp.length}")
    }

    fun sendAnswer(sdp: String, requestId: Int) {
        val frame = buildJsonObject {
            put("cmd", "answer")
            put("sdp", sdp)
            put("request_id", requestId)
        }
        send(frame.toString(), "answer len=${sdp.length} req=$requestId")
    }

    fun sendCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        val frame = buildJsonObject {
            put("cmd", "candidate")
            put("candidate", buildJsonObject {
                put("candidate", candidate)
                put("sdpMid", sdpMid)
                put("sdpMLineIndex", sdpMLineIndex)
            })
        }
        send(frame.toString(), "candidate mid=$sdpMid idx=$sdpMLineIndex")
    }

    fun leave() {
        val frame = buildJsonObject {
            put("cmd", "leave")
        }
        send(frame.toString(), "leave")
    }

    fun disconnect() {
        Log.i(TAG_SIGNALING, "disconnect (client close)")
        closedByClient = true
        webSocket?.close(1000, "Client disconnect")
    }
}

/** Payload-free one-liner for event logging (SDP content never reaches logcat). */
internal fun eventSummary(e: RtcEvent): String = when (e) {
    RtcEvent.Connected -> "connected"
    RtcEvent.Disconnected -> "disconnected"
    is RtcEvent.Reconnecting -> "reconnecting(${e.attempt}/${e.max})"
    RtcEvent.AuthRequired -> "auth_required"
    is RtcEvent.Answer -> "answer(len=${e.sdp.length})"
    is RtcEvent.Offer -> "offer(len=${e.sdp.length},req=${e.requestId})"
    is RtcEvent.Candidate -> "candidate(mid=${e.sdpMid},idx=${e.sdpMLineIndex})"
    is RtcEvent.ServerEvent -> "server(${e.event},reason=${e.reason?.take(80)})"
    is RtcEvent.Error -> "error(${e.message.take(80)})"
}

sealed class RtcEvent {
    data object Connected : RtcEvent()
    data object Disconnected : RtcEvent()
    /** Reconnect in progress (attempt/max). Signaling-level recovery. */
    data class Reconnecting(val attempt: Int, val max: Int) : RtcEvent()
    /** Room token rejected (401/403): owner must fetch a fresh token and rejoin. */
    data object AuthRequired : RtcEvent()
    data class Answer(val sdp: String) : RtcEvent()
    data class Offer(val sdp: String, val requestId: Int) : RtcEvent()
    data class Candidate(val candidate: String, val sdpMid: String, val sdpMLineIndex: Int) : RtcEvent()
    data class ServerEvent(val event: String, val reason: String? = null) : RtcEvent()
    data class Error(val message: String) : RtcEvent()
}
