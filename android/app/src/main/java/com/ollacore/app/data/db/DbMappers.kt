package com.ollacore.app.data.db

import com.ollacore.app.data.model.MessageResponse
import com.ollacore.app.data.model.attachmentRefIds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val jsonLenient = Json { ignoreUnknownKeys = true }

/** Serialize message body map -> JSON string for Room. */
fun bodyToJson(body: Map<String, JsonElement>): String = runCatching {
    jsonLenient.encodeToString(JsonObject.serializer(), JsonObject(body))
}.getOrElse { "{}" }

/** Parse stored body JSON back to a map. */
fun bodyFromJson(raw: String): Map<String, JsonElement> = runCatching {
    jsonLenient.decodeFromString(JsonObject.serializer(), raw).entries
        .associate { it.key to it.value }
}.getOrElse { emptyMap() }

fun MessageResponse.toEntity(statusRank: Int = 1): MessageEntity = MessageEntity(
    msgId = id,
    roomId = roomId,
    senderId = senderId,
    kind = kind,
    bodyJson = bodyToJson(body),
    eventSeq = eventSeq,
    clientMessageId = clientMessageId,
    replyTo = replyTo,
    statusRank = statusRank,
    attachmentIdsCsv = attachmentRefIds(this).joinToString(","),
    createdAt = createdAt,
    editedAt = editedAt
)

fun MessageEntity.toModel(): MessageResponse = MessageResponse(
    id = msgId,
    roomId = roomId,
    senderId = senderId,
    kind = kind,
    body = bodyFromJson(bodyJson),
    createdAt = createdAt.ifBlank { "" },
    eventSeq = eventSeq,
    clientMessageId = clientMessageId,
    replyTo = replyTo,
    editedAt = editedAt,
    attachmentIds = attachmentIds()
)

/** Best-effort preview for conversation list rows. */
fun previewOf(kind: String, body: Map<String, JsonElement>): String = when (kind) {
    "image" -> "\uD83D\uDCF7 Photo"
    "video" -> "\uD83C\uDFA5 Video"
    "audio" -> "\uD83C\uDFA4 Voice message"
    "file" -> "\uD83D\uDCCE Document"
    else -> runCatching {
        (body["text"] as? JsonPrimitive)?.content
            ?: (body["caption"] as? JsonPrimitive)?.content
            ?: body.entries.firstOrNull()?.value.toString().take(120)
    }.getOrElse { "" } ?: ""
}

fun draftPreview(kind: String, bodyJson: String): String =
    previewOf(kind, bodyFromJson(bodyJson))

/** Build a JsonObject body helper for queued text sends. */
fun textBody(text: String): Map<String, JsonElement> =
    mapOf("text" to JsonPrimitive(text))
