package com.ollacore.app.data.remote

import android.util.Log
import kotlinx.serialization.json.*
import okhttp3.*
import java.util.concurrent.TimeUnit

private const val TAG_SIGNALING = "[SIGNALING]"

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

class RtcWebSocket(
    private val baseUrl: String,
    private val token: String,
    private val onEvent: (RtcEvent) -> Unit
) {
    private var webSocket: WebSocket? = null
    private var open = false
    private val pendingSends = ArrayDeque<String>()

    fun connect() {
        val request = Request.Builder()
            .url(baseUrl)
            .addHeader("Sec-WebSocket-Protocol", "chatbox, bearer.$token")
            .build()

        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        Log.i(TAG_SIGNALING, "connect url=$baseUrl")
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open = true
                Log.i(TAG_SIGNALING, "socket OPEN, flushing ${pendingSends.size} queued frames")
                while (pendingSends.isNotEmpty()) {
                    val frame = pendingSends.removeFirst()
                    Log.i(TAG_SIGNALING, "send(queued): ${frame.take(120)}")
                    webSocket.send(frame)
                }
                onEvent(RtcEvent.Connected)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.i(TAG_SIGNALING, "recv: ${text.take(160)}")
                val parsed = runCatching { parseRtcFrame(text) }.getOrNull()
                if (parsed == null) {
                    Log.w(TAG_SIGNALING, "UNKNOWN frame shape, dropped: ${text.take(200)}")
                    return
                }
                onEvent(parsed)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                open = false
                Log.i(TAG_SIGNALING, "socket CLOSED code=$code reason=$reason")
                onEvent(RtcEvent.Disconnected)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                open = false
                Log.e(TAG_SIGNALING, "socket FAILURE: ${t.message}")
                onEvent(RtcEvent.Error(t.message ?: "Connection failed"))
            }
        })
    }

    private fun send(frame: String, label: String) {
        val ws = webSocket
        if (open && ws != null) {
            Log.i(TAG_SIGNALING, "send($label): ${frame.take(120)}")
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
        webSocket?.close(1000, "Client disconnect")
    }
}

sealed class RtcEvent {
    data object Connected : RtcEvent()
    data object Disconnected : RtcEvent()
    data class Answer(val sdp: String) : RtcEvent()
    data class Offer(val sdp: String, val requestId: Int) : RtcEvent()
    data class Candidate(val candidate: String, val sdpMid: String, val sdpMLineIndex: Int) : RtcEvent()
    data class ServerEvent(val event: String, val reason: String? = null) : RtcEvent()
    data class Error(val message: String) : RtcEvent()
}
