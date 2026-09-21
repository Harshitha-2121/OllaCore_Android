package com.ollacore.app.data.remote

import kotlinx.serialization.json.*
import okhttp3.*
import java.util.concurrent.TimeUnit

class RtcWebSocket(
    private val baseUrl: String,
    private val token: String,
    private val onEvent: (RtcEvent) -> Unit
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var webSocket: WebSocket? = null

    fun connect() {
        val request = Request.Builder()
            .url(baseUrl)
            .addHeader("Sec-WebSocket-Protocol", "chatbox, bearer.$token")
            .build()

        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onEvent(RtcEvent.Connected)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val frame = json.parseToJsonElement(text).jsonObject

                    when {
                        frame.containsKey("event") -> {
                            val event = frame["event"]?.jsonPrimitive?.content ?: ""
                            val reason = frame["reason"]?.jsonPrimitive?.content
                            onEvent(RtcEvent.ServerEvent(event, reason))
                        }
                        frame["type"]?.jsonPrimitive?.content == "answer" -> {
                            val sdp = frame["sdp"]?.jsonPrimitive?.content ?: ""
                            onEvent(RtcEvent.Answer(sdp))
                        }
                        frame["type"]?.jsonPrimitive?.content == "offer" -> {
                            val sdp = frame["sdp"]?.jsonPrimitive?.content ?: ""
                            val requestId = frame["request_id"]?.jsonPrimitive?.int ?: 0
                            onEvent(RtcEvent.Offer(sdp, requestId))
                        }
                    }
                } catch (e: Exception) {
                    onEvent(RtcEvent.Error(e.message ?: "Parse error"))
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onEvent(RtcEvent.Disconnected)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onEvent(RtcEvent.Error(t.message ?: "Connection failed"))
            }
        })
    }

    fun sendOffer(sdp: String) {
        val frame = buildJsonObject {
            put("cmd", "offer")
            put("sdp", buildJsonObject {
                put("type", "offer")
                put("sdp", sdp)
            })
        }
        webSocket?.send(frame.toString())
    }

    fun sendAnswer(sdp: String, requestId: Int) {
        val frame = buildJsonObject {
            put("cmd", "answer")
            put("sdp", sdp)
            put("request_id", requestId)
        }
        webSocket?.send(frame.toString())
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
        webSocket?.send(frame.toString())
    }

    fun leave() {
        val frame = buildJsonObject {
            put("cmd", "leave")
        }
        webSocket?.send(frame.toString())
    }

    fun disconnect() {
        webSocket?.close(1000, "Client disconnect")
    }
}

sealed class RtcEvent {
    data object Connected : RtcEvent()
    data object Disconnected : RtcEvent()
    data class Answer(val sdp: String) : RtcEvent()
    data class Offer(val sdp: String, val requestId: Int) : RtcEvent()
    data class ServerEvent(val event: String, val reason: String? = null) : RtcEvent()
    data class Error(val message: String) : RtcEvent()
}
