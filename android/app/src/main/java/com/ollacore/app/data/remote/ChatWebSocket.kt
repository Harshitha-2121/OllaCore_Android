package com.ollacore.app.data.remote

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import java.util.UUID
import java.util.concurrent.TimeUnit

class ChatWebSocket(
    private val baseUrl: String,
    private val token: String,
    private val onEvent: (WebSocketEvent) -> Unit
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var webSocket: WebSocket? = null
    private var scope: CoroutineScope? = null
    private var isConnected = false
    private var highestSeq: Int = 0

    fun connect(scope: CoroutineScope) {
        this.scope = scope
        val request = Request.Builder()
            .url(baseUrl)
            .addHeader("Sec-WebSocket-Protocol", "chatbox, bearer.$token")
            .build()

        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                onEvent(WebSocketEvent.Connected)
                if (highestSeq > 0) {
                    catchup(highestSeq)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val frame = json.parseToJsonElement(text).jsonObject
                    val type = frame["type"]?.jsonPrimitive?.content ?: return
                    val eventId = frame["event_id"]?.jsonPrimitive?.int ?: 0
                    val roomId = frame["room_id"]?.jsonPrimitive?.content
                    val payload = frame["payload"]?.jsonObject ?: buildJsonObject {}

                    when (type) {
                        "ack" -> {
                            val requestId = frame["request_id"]?.jsonPrimitive?.content
                            onEvent(WebSocketEvent.Ack(requestId ?: ""))
                        }
                        "error" -> {
                            val error = frame["error"]?.jsonObject
                            val code = error?.get("code")?.jsonPrimitive?.content ?: "unknown"
                            val message = error?.get("message")?.jsonPrimitive?.content ?: "Unknown error"
                            // Correlate send failures: server echoes request_id so UI can mark Failed + Retry
                            val requestId = frame["request_id"]?.jsonPrimitive?.content
                            onEvent(WebSocketEvent.Error(code, message, requestId))
                        }
                        "message.created" -> {
                            if (eventId > highestSeq) highestSeq = eventId
                            val msg = parseMessage(payload, roomId ?: "")
                            onEvent(WebSocketEvent.MessageCreated(msg))
                        }
                        "message.updated" -> {
                            if (eventId > highestSeq) highestSeq = eventId
                            val msg = parseMessage(payload, roomId ?: "")
                            onEvent(WebSocketEvent.MessageUpdated(msg))
                        }
                        "message.deleted" -> {
                            if (eventId > highestSeq) highestSeq = eventId
                            val messageId = payload["id"]?.jsonPrimitive?.content ?: ""
                            onEvent(WebSocketEvent.MessageDeleted(roomId ?: "", messageId))
                        }
                        "receipt.delivered" -> {
                            val principalId = payload["principal_id"]?.jsonPrimitive?.content ?: ""
                            val messageId = payload["message_id"]?.jsonPrimitive?.content ?: ""
                            onEvent(WebSocketEvent.ReceiptDelivered(roomId ?: "", principalId, messageId))
                        }
                        "receipt.read" -> {
                            val principalId = payload["principal_id"]?.jsonPrimitive?.content ?: ""
                            val messageId = payload["message_id"]?.jsonPrimitive?.content ?: ""
                            onEvent(WebSocketEvent.ReceiptRead(roomId ?: "", principalId, messageId))
                        }
                        "reaction.added" -> {
                            val principalId = payload["principal_id"]?.jsonPrimitive?.content ?: ""
                            val messageId = payload["message_id"]?.jsonPrimitive?.content ?: ""
                            val emoji = payload["emoji"]?.jsonPrimitive?.content ?: ""
                            onEvent(WebSocketEvent.ReactionAdded(roomId ?: "", messageId, principalId, emoji))
                        }
                        "reaction.removed" -> {
                            val principalId = payload["principal_id"]?.jsonPrimitive?.content ?: ""
                            val messageId = payload["message_id"]?.jsonPrimitive?.content ?: ""
                            val emoji = payload["emoji"]?.jsonPrimitive?.content ?: ""
                            onEvent(WebSocketEvent.ReactionRemoved(roomId ?: "", messageId, principalId, emoji))
                        }
                        "member.added" -> {
                            val principalId = payload["principal_id"]?.jsonPrimitive?.content ?: ""
                            onEvent(WebSocketEvent.MemberAdded(roomId ?: "", principalId))
                        }
                        "member.removed" -> {
                            val principalId = payload["principal_id"]?.jsonPrimitive?.content ?: ""
                            onEvent(WebSocketEvent.MemberRemoved(roomId ?: "", principalId))
                        }
                        "call.started" -> {
                            val callId = payload["call_id"]?.jsonPrimitive?.content ?: ""
                            val initiator = payload["initiator"]?.jsonPrimitive?.content ?: ""
                            onEvent(WebSocketEvent.CallStarted(roomId ?: "", callId, initiator))
                        }
                        "call.ended" -> {
                            val callId = payload["call_id"]?.jsonPrimitive?.content ?: ""
                            val initiator = payload["initiator"]?.jsonPrimitive?.content ?: ""
                            onEvent(WebSocketEvent.CallEnded(roomId ?: "", callId, initiator))
                        }
                        "presence.changed" -> {
                            val online = payload["online"]?.jsonPrimitive?.boolean ?: false
                            val principalId = payload["principal_id"]?.jsonPrimitive?.content ?: ""
                            onEvent(WebSocketEvent.PresenceChanged(roomId ?: "", principalId, online))
                        }
                        "typing.started" -> {
                            val principalId = payload["principal_id"]?.jsonPrimitive?.content ?: ""
                            onEvent(WebSocketEvent.TypingStarted(roomId ?: "", principalId))
                        }
                        "typing.stopped" -> {
                            val principalId = payload["principal_id"]?.jsonPrimitive?.content ?: ""
                            onEvent(WebSocketEvent.TypingStopped(roomId ?: "", principalId))
                        }
                        "attachment.ready" -> {
                            val attachmentId = payload["attachment_id"]?.jsonPrimitive?.content ?: ""
                            val mime = payload["mime"]?.jsonPrimitive?.content ?: ""
                            onEvent(WebSocketEvent.AttachmentReady(roomId ?: "", attachmentId, mime))
                        }
                        "attachment.failed" -> {
                            val attachmentId = payload["attachment_id"]?.jsonPrimitive?.content ?: ""
                            onEvent(WebSocketEvent.AttachmentFailed(roomId ?: "", attachmentId))
                        }
                        "resync" -> {
                            onEvent(WebSocketEvent.Resync(roomId ?: ""))
                        }
                        "pong" -> {
                            onEvent(WebSocketEvent.Pong)
                        }
                    }
                } catch (e: Exception) {
                    onEvent(WebSocketEvent.Error("parse_error", e.message ?: "Parse error"))
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                onEvent(WebSocketEvent.Disconnected(code, reason))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                onEvent(WebSocketEvent.Error("failure", t.message ?: "Connection failed"))
            }
        })
    }

    /** Sends a frame; returns the request_id so callers can match server ack/error receipts. */
    fun send(type: String, payload: JsonObject = buildJsonObject {}, roomId: String? = null): String {
        val requestId = UUID.randomUUID().toString()
        val frame = buildJsonObject {
            put("v", 1)
            put("request_id", requestId)
            put("type", type)
            if (roomId != null) put("room_id", roomId)
            put("payload", payload)
        }
        webSocket?.send(frame.toString())
        return requestId
    }

    /** Sends a chat message; returns the WS request_id for ack/error correlation (message status ticks). */
    fun sendMessage(roomId: String, clientMessageId: String, body: JsonObject, kind: String = "text", replyTo: String? = null, attachmentIds: List<String> = emptyList()): String {
        val payload = buildJsonObject {
            put("client_message_id", clientMessageId)
            put("kind", kind)
            put("body", body)
            if (replyTo != null) put("reply_to", replyTo)
            put("attachment_ids", buildJsonArray {
                attachmentIds.forEach { add(it) }
            })
        }
        return send("message.send", payload, roomId)
    }

    fun editMessage(roomId: String, messageId: String, body: JsonObject) {
        val payload = buildJsonObject {
            put("message_id", messageId)
            put("body", body)
        }
        send("message.edit", payload, roomId)
    }

    fun deleteMessage(roomId: String, messageId: String) {
        val payload = buildJsonObject {
            put("message_id", messageId)
        }
        send("message.delete", payload, roomId)
    }

    fun addReaction(roomId: String, messageId: String, emoji: String) {
        val payload = buildJsonObject {
            put("message_id", messageId)
            put("emoji", emoji)
        }
        send("reaction.add", payload, roomId)
    }

    fun removeReaction(roomId: String, messageId: String, emoji: String) {
        val payload = buildJsonObject {
            put("message_id", messageId)
            put("emoji", emoji)
        }
        send("reaction.remove", payload, roomId)
    }

    fun markDelivered(roomId: String, messageId: String) {
        val payload = buildJsonObject {
            put("message_id", messageId)
        }
        send("receipt.delivered", payload, roomId)
    }

    fun markRead(roomId: String, messageId: String) {
        val payload = buildJsonObject {
            put("message_id", messageId)
        }
        send("receipt.read", payload, roomId)
    }

    fun typingStarted(roomId: String) {
        send("typing.started", roomId = roomId)
    }

    fun typingStopped(roomId: String) {
        send("typing.stopped", roomId = roomId)
    }

    fun catchup(afterSeq: Int, limit: Int = 200) {
        val payload = buildJsonObject {
            put("after_seq", afterSeq)
            put("limit", limit)
        }
        send("catchup", payload)
    }

    fun ping() {
        send("ping")
    }

    fun disconnect() {
        webSocket?.close(1000, "Client disconnect")
        isConnected = false
    }

    private fun parseMessage(payload: JsonObject, roomId: String): WsMessage {
        val attachmentIds = try {
            payload["attachment_ids"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        } catch (_: Exception) { emptyList() }
        return WsMessage(
            id = payload["id"]?.jsonPrimitive?.content ?: "",
            roomId = roomId,
            senderId = payload["sender_id"]?.jsonPrimitive?.content ?: "",
            kind = payload["kind"]?.jsonPrimitive?.content ?: "text",
            body = payload["body"]?.jsonObject ?: buildJsonObject {},
            createdAt = payload["created_at"]?.jsonPrimitive?.content ?: "",
            eventSeq = payload["event_seq"]?.jsonPrimitive?.int ?: 0,
            clientMessageId = payload["client_message_id"]?.jsonPrimitive?.content,
            replyTo = payload["reply_to"]?.jsonPrimitive?.content,
            editedAt = payload["edited_at"]?.jsonPrimitive?.content,
            attachmentIds = attachmentIds
        )
    }
}

sealed class WebSocketEvent {
    data object Connected : WebSocketEvent()
    data class Disconnected(val code: Int, val reason: String) : WebSocketEvent()
    data class Ack(val requestId: String) : WebSocketEvent()
    data class Error(val code: String, val message: String, val requestId: String? = null) : WebSocketEvent()
    data class MessageCreated(val message: WsMessage) : WebSocketEvent()
    data class MessageUpdated(val message: WsMessage) : WebSocketEvent()
    data class MessageDeleted(val roomId: String, val messageId: String) : WebSocketEvent()
    data class ReceiptDelivered(val roomId: String, val principalId: String, val messageId: String) : WebSocketEvent()
    data class ReceiptRead(val roomId: String, val principalId: String, val messageId: String) : WebSocketEvent()
    data class ReactionAdded(val roomId: String, val messageId: String, val principalId: String, val emoji: String) : WebSocketEvent()
    data class ReactionRemoved(val roomId: String, val messageId: String, val principalId: String, val emoji: String) : WebSocketEvent()
    data class MemberAdded(val roomId: String, val principalId: String) : WebSocketEvent()
    data class MemberRemoved(val roomId: String, val principalId: String) : WebSocketEvent()
    data class CallStarted(val roomId: String, val callId: String, val initiator: String) : WebSocketEvent()
    data class CallEnded(val roomId: String, val callId: String, val initiator: String) : WebSocketEvent()
    data class PresenceChanged(val roomId: String, val principalId: String, val online: Boolean) : WebSocketEvent()
    data class TypingStarted(val roomId: String, val principalId: String) : WebSocketEvent()
    data class TypingStopped(val roomId: String, val principalId: String) : WebSocketEvent()
    data class AttachmentReady(val roomId: String, val attachmentId: String, val mime: String) : WebSocketEvent()
    data class AttachmentFailed(val roomId: String, val attachmentId: String) : WebSocketEvent()
    data class Resync(val roomId: String) : WebSocketEvent()
    data object Pong : WebSocketEvent()
}

data class WsMessage(
    val id: String,
    val roomId: String,
    val senderId: String,
    val kind: String,
    val body: JsonObject,
    val createdAt: String,
    val eventSeq: Int,
    val clientMessageId: String? = null,
    val replyTo: String? = null,
    val editedAt: String? = null,
    val attachmentIds: List<String> = emptyList()
)
