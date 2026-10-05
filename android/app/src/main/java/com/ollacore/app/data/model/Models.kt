package com.ollacore.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class OtpRequest(
    @SerialName("app_id") val appId: String,
    val phone: String
)

@Serializable
data class OtpResponse(
    val dispatched: Boolean,
    @SerialName("expires_at") val expiresAt: String? = null
)

@Serializable
data class OtpVerify(
    @SerialName("app_id") val appId: String,
    val phone: String,
    val code: String
)

@Serializable
data class OtpVerifyResponse(
    @SerialName("session_token") val sessionToken: String,
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String? = null
)

@Serializable
data class UserProfile(
    val id: String,
    val phone: String,
    @SerialName("display_name") val displayName: String? = null,
    val about: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("photo_url") val photoUrl: String? = null
)

@Serializable
data class UpdateProfileRequest(
    @SerialName("display_name") val displayName: String? = null,
    val about: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null
)

@Serializable
data class ContactLookupRequest(val phones: List<String>)

@Serializable
data class ContactLookupResponse(
    val contacts: List<ContactUser>
)

@Serializable
data class ContactUser(
    @SerialName("user_id") val userId: String,
    val phone: String,
    @SerialName("display_name") val displayName: String? = null
)

@Serializable
data class DirectConversationRequest(
    @SerialName("peer_user_id") val peerUserId: String
)

@Serializable
data class GroupConversationRequest(
    @SerialName("member_user_ids") val memberUserIds: List<String>,
    val name: String
)

@Serializable
data class ConversationResponse(
    @SerialName("room_id") val roomId: String,
    val kind: String,
    val name: String? = null
)

@Serializable
data class ConversationListResponse(
    val conversations: List<ConversationSummary>
)

@Serializable
data class ConversationSummary(
    @SerialName("room_id") val roomId: String,
    val kind: String,
    val name: String? = null,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class InboxResponse(
    val conversations: List<InboxItem>
)

@Serializable
data class InboxItem(
    @SerialName("room_id") val roomId: String,
    val kind: String,
    val name: String? = null,
    val peer: InboxPeer? = null,
    @SerialName("last_message") val lastMessage: LastMessage? = null,
    @SerialName("unread_count") val unreadCount: Int = 0
)

@Serializable
data class InboxPeer(
    val phone: String,
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String? = null
)

@Serializable
data class LastMessage(
    val preview: String? = null,
    @SerialName("sender_id") val senderId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("event_seq") val eventSeq: Int? = null,
    val kind: String? = null
)

@Serializable
data class RoomTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("chat_websocket_url") val chatWebsocketUrl: String,
    @SerialName("rtc_websocket_url") val rtcWebsocketUrl: String,
    @SerialName("ice_servers") val iceServers: List<IceServer> = emptyList(),
    val e2ee: E2eeConfig? = null
)

@Serializable
data class IceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null
)

@Serializable
data class SendMessageRequest(
    @SerialName("client_message_id") val clientMessageId: String,
    val kind: String = "text",
    val body: Map<String, kotlinx.serialization.json.JsonElement>,
    @SerialName("reply_to") val replyTo: String? = null,
    @SerialName("attachment_ids") val attachmentIds: List<String> = emptyList()
)

@Serializable
data class MessageResponse(
    val id: String,
    @SerialName("room_id") val roomId: String,
    @SerialName("sender_id") val senderId: String,
    val kind: String,
    // Server sends "body": null on protocol/system rows (proven: Malavika
    // chat $.messages[5].body). A strict Map killed the WHOLE history decode
    // and the error text ("Unexpected JSON token") even misread as expired
    // session. Null coerces to empty; bubbles render a blank placeholder.
    @Serializable(with = NullAsEmptyJsonObject::class)
    val body: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    @SerialName("created_at") val createdAt: String,
    @SerialName("event_seq") val eventSeq: Int,
    @SerialName("client_message_id") val clientMessageId: String? = null,
    @SerialName("reply_to") val replyTo: String? = null,
    @SerialName("edited_at") val editedAt: String? = null,
    val reactions: Map<String, List<String>>? = null,
    // ── Media & attachments (Category 1 - Ollacore API YES) ──
    // Backend returns attachment_ids with the message; body carries mime/filename/caption for rendering.
    @SerialName("attachment_ids") val attachmentIds: List<String> = emptyList(),
    val attachments: List<AttachmentInfo> = emptyList()
)

@Serializable
data class AttachmentInfo(
    // Nullable: the server sometimes emits attachment objects without an id
    // (proven: history decode crashed the whole chat with MissingFieldException
    // at $.messages[].attachments[]). Bubbles key off attachment_ids anyway.
    @SerialName("attachment_id") val attachmentId: String? = null,
    // History items use "id" (not "attachment_id") plus extra metadata.
    val id: String? = null,
    val mime: String? = null,
    val filename: String? = null,
    @SerialName("byte_size") val byteSize: Long? = null
)

/**
 * Coerces explicit JSON null into an empty object for map fields the server
 * sometimes nulls (message bodies on protocol/system rows).
 */
object NullAsEmptyJsonObject : JsonTransformingSerializer<Map<String, JsonElement>>(
    MapSerializer(String.serializer(), JsonElement.serializer())
) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonNull) buildJsonObject {} else element
}

/**
 * Every usable attachment reference for a message: explicit attachment_ids
 * first, then history-style attachments[].id. Server history normalizes sent
 * messages to attachments[] (proven via API), so both sources are needed to
 * resolve download URLs after a reload.
 */
fun attachmentRefIds(message: MessageResponse): List<String> {
    val fromObjects = message.attachments.mapNotNull { it.attachmentId ?: it.id }
    return (message.attachmentIds + fromObjects).distinct().filter { it.isNotBlank() }
}

/**
 * Voice duration from body. Ours sends duration_ms; peer clients (Alice)
 * send duration in SECONDS - reading only duration_ms showed no time and
 * was a playback-diagnosis red herring. Prefer ms, fall back to seconds*1000.
 */
fun voiceDurationMs(body: Map<String, kotlinx.serialization.json.JsonElement>): Long? {
    fun readLong(key: String): Long? = try {
        body[key]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content?.toLongOrNull()
    } catch (_: Exception) { null }
    readLong("duration_ms")?.let { return it }
    readLong("duration")?.let { sec -> if (sec > 0) return sec * 1000 }
    return null
}

/** Group management (Category 1 Create/Participants YES; add/remove/rename/icon/role/invite/leave = backend-check, conventional Ollacore-style paths). */
@Serializable
data class AddMemberRequest(
    @SerialName("user_id") val userId: String
)

@Serializable
data class UpdateGroupRequest(
    val name: String? = null,
    val description: String? = null,
    @SerialName("icon_url") val iconUrl: String? = null
)

@Serializable
data class MemberRoleRequest(val role: String)

@Serializable
data class GroupInviteResponse(
    val code: String? = null,
    val url: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null
)

/** WhatsApp-style media kinds carried over Ollacore `kind` + attachment_ids. No proprietary copy. */
object MessageKinds {
    const val TEXT = "text"
    const val IMAGE = "image"
    const val VIDEO = "video"
    const val AUDIO = "audio"
    const val FILE = "file"
    const val LOCATION = "location" // backend/API check required - client sends, server may reject unknown kind
}

@Serializable
data class MessageListResponse(
    val messages: List<MessageResponse>,
    @SerialName("has_more") val hasMore: Boolean = false
)

@Serializable
data class EditMessageRequest(
    val body: Map<String, kotlinx.serialization.json.JsonElement>
)

@Serializable
data class ReactionRequest(val emoji: String)

@Serializable
data class UnreadResponse(
    @SerialName("unread_count") val unreadCount: Int,
    @SerialName("last_read_seq") val lastReadSeq: Int = 0
)

@Serializable
data class ParticipantsResponse(
    val participants: List<Participant>
)

@Serializable
data class Participant(
    @SerialName("principal_id") val principalId: String,
    val role: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val phone: String? = null
)

@Serializable
data class AttachmentInitResponse(
    @SerialName("attachment_id") val attachmentId: String,
    @SerialName("upload_url") val uploadUrl: String,
    // PROVEN server shape: the single-part init returns "upload_expires_at"
    // (not "expires_at") plus "required_headers". Nullable: expiry is metadata
    // the client never acts on; a missing field must not kill the upload.
    @SerialName("upload_expires_at") val uploadExpiresAt: String? = null,
    @SerialName("required_headers") val requiredHeaders: List<List<String>> = emptyList()
)

@Serializable
data class AttachmentDownloadResponse(
    @SerialName("download_url") val downloadUrl: String,
    @SerialName("expires_at") val expiresAt: String? = null
)

@Serializable
data class DeviceRegistration(
    val platform: String,
    @SerialName("push_token") val pushToken: String
)

@Serializable
data class DeviceResponse(
    val id: String,
    val platform: String,
    @SerialName("push_token") val pushToken: String,
    @SerialName("app_id") val appId: String,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class DeviceListResponse(
    val devices: List<DeviceResponse>
)

@Serializable
data class ApiError(
    val code: String,
    val message: String
)

@Serializable
data class MultipartInitResponse(
    @SerialName("attachment_id") val attachmentId: String,
    @SerialName("upload_id") val uploadId: String,
    @SerialName("part_size") val partSize: Long,
    @SerialName("part_urls") val partUrls: List<String>,
    @SerialName("expires_at") val expiresAt: String
)

@Serializable
data class MultipartPart(
    @SerialName("part_number") val partNumber: Int,
    val etag: String
)

@Serializable
data class E2eeKeyUploadResponse(
    val counts: Map<String, Int>? = null
)

@Serializable
data class E2eeKeyQueryResponse(
    @SerialName("device_keys") val deviceKeys: Map<String, List<DeviceKeyInfo>>
)

@Serializable
data class DeviceKeyInfo(
    @SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: String,
    val algorithms: List<String>? = null,
    val keys: Map<String, String>? = null,
    val signatures: Map<String, Map<String, String>>? = null
)

@Serializable
data class E2eeKeyClaimResponse(
    @SerialName("one_time_keys") val oneTimeKeys: Map<String, Map<String, DeviceKeyInfo>>
)

@Serializable
data class ToDeviceMessagesResponse(
    val messages: List<ToDeviceMessage>
)

@Serializable
data class ToDeviceMessage(
    @SerialName("event_type") val eventType: String,
    val sender: String,
    val content: Map<String, kotlinx.serialization.json.JsonElement>
)

@Serializable
data class KeyPackageCountResponse(
    val count: Int
)

@Serializable
data class KeyPackageConsumeResponse(
    val packages: List<kotlinx.serialization.json.JsonElement>
)

@Serializable
data class E2eeConfig(
    val encrypted: Boolean = false,
    @SerialName("content_free_push") val contentFreePush: Boolean = false
)

enum class EncryptedMessageKind(val value: String) {
    MLS_COMMIT("mls.commit"),
    MLS_PROPOSAL("mls.proposal"),
    MLS_APPLICATION("mls.application"),
    MLS_WELCOME("mls.welcome");

    companion object {
        fun fromValue(value: String): EncryptedMessageKind? = entries.find { it.value == value }
        fun isEncryptedKind(kind: String): Boolean = entries.any { it.value == kind }
    }
}
