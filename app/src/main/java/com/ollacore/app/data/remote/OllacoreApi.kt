package com.ollacore.app.data.remote

import com.ollacore.app.data.model.*
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class OllacoreApi(
    private val apiBase: String = "https://api.ollacore.com/v1",
    private val appId: String = "da_3a0a2cfaabd34b7dbfb4ecd5033b0c5f"
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun String.jsonBody(): okhttp3.RequestBody = toRequestBody(mediaType)

    private fun Request.Builder.auth(token: String): Request.Builder =
        addHeader("Authorization", "Bearer $token")

    private inline fun <reified T> execute(request: Request): T {
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw ApiException("Empty response")
        if (!response.isSuccessful) {
            val error = try { json.decodeFromString<ApiError>(body) } catch (_: Exception) { null }
            throw ApiException(
                message = error?.message ?: "HTTP ${response.code}",
                code = error?.code,
                httpStatus = response.code
            )
        }
        return json.decodeFromString(body)
    }

    // ── Directory Auth ──────────────────────────────────────────────

    fun requestOtp(phone: String): OtpResponse {
        val body = json.encodeToString(OtpRequest.serializer(), OtpRequest(appId, phone))
        val request = Request.Builder()
            .url("$apiBase/directory/otp/request")
            .post(body.jsonBody())
            .build()
        return execute(request)
    }

    fun verifyOtp(phone: String, code: String): OtpVerifyResponse {
        val body = json.encodeToString(OtpVerify.serializer(), OtpVerify(appId, phone, code))
        val request = Request.Builder()
            .url("$apiBase/directory/otp/verify")
            .post(body.jsonBody())
            .build()
        return execute(request)
    }

    // ── Profile ─────────────────────────────────────────────────────

    fun getProfile(token: String): UserProfile {
        val request = Request.Builder()
            .url("$apiBase/directory/me")
            .get()
            .auth(token)
            .build()
        return execute(request)
    }

    fun logout(token: String, allDevices: Boolean = false) {
        val body = if (allDevices) """{"all_devices":true}""".jsonBody() else """{}""".jsonBody()
        val request = Request.Builder()
            .url("$apiBase/directory/logout")
            .post(body)
            .auth(token)
            .build()
        client.newCall(request).execute().close()
    }

    fun updateProfile(token: String, displayName: String? = null, about: String? = null, avatarUrl: String? = null): UserProfile {
        val req = UpdateProfileRequest(displayName = displayName, about = about, avatarUrl = avatarUrl)
        val body = json.encodeToString(UpdateProfileRequest.serializer(), req)
        val request = Request.Builder()
            .url("$apiBase/directory/me")
            .patch(body.jsonBody())
            .auth(token)
            .build()
        return execute(request)
    }

    // Backcompat overload
    fun updateProfileLegacy(token: String, displayName: String): UserProfile = updateProfile(token, displayName = displayName)

    // ── Contacts ────────────────────────────────────────────────────

    fun lookupContacts(token: String, phones: List<String>): ContactLookupResponse {
        val body = json.encodeToString(ContactLookupRequest.serializer(), ContactLookupRequest(phones))
        val request = Request.Builder()
            .url("$apiBase/directory/contacts/lookup")
            .post(body.jsonBody())
            .auth(token)
            .build()
        return execute(request)
    }

    // ── Conversations ───────────────────────────────────────────────

    fun listConversations(token: String): ConversationListResponse {
        val request = Request.Builder()
            .url("$apiBase/directory/conversations")
            .get()
            .auth(token)
            .build()
        return execute(request)
    }

    fun openDirectConversation(token: String, peerUserId: String): ConversationResponse {
        val body = json.encodeToString(DirectConversationRequest.serializer(), DirectConversationRequest(peerUserId))
        val request = Request.Builder()
            .url("$apiBase/directory/conversations/direct")
            .post(body.jsonBody())
            .auth(token)
            .build()
        return execute(request)
    }

    fun createGroupConversation(token: String, memberUserIds: List<String>, name: String): ConversationResponse {
        val body = json.encodeToString(GroupConversationRequest.serializer(), GroupConversationRequest(memberUserIds, name))
        val request = Request.Builder()
            .url("$apiBase/directory/conversations/group")
            .post(body.jsonBody())
            .auth(token)
            .build()
        return execute(request)
    }

    fun getRoomToken(token: String, roomId: String, deviceId: String): RoomTokenResponse {
        val request = Request.Builder()
            .url("$apiBase/directory/conversations/$roomId/token?device_id=$deviceId")
            .get()
            .auth(token)
            .build()
        return execute(request)
    }

    fun getInbox(token: String, limit: Int = 30): InboxResponse {
        val request = Request.Builder()
            .url("$apiBase/directory/inbox?limit=$limit")
            .get()
            .auth(token)
            .build()
        return execute(request)
    }

    // ── Messages (Client API with room token) ───────────────────────

    fun listMessages(roomToken: String, roomId: String, afterSeq: Int? = null, beforeSeq: Int? = null, limit: Int = 50): MessageListResponse {
        val url = buildString {
            append("$apiBase/rooms/$roomId/messages?limit=$limit")
            if (afterSeq != null) append("&after_seq=$afterSeq")
            if (beforeSeq != null) append("&before_seq=$beforeSeq")
        }
        val request = Request.Builder()
            .url(url)
            .get()
            .auth(roomToken)
            .build()
        return execute(request)
    }

    fun sendMessage(roomToken: String, roomId: String, clientMessageId: String, body: Map<String, kotlinx.serialization.json.JsonElement>, kind: String = "text", replyTo: String? = null, attachmentIds: List<String> = emptyList()): MessageResponse {
        val req = SendMessageRequest(clientMessageId, kind, body, replyTo, attachmentIds)
        val bodyStr = json.encodeToString(SendMessageRequest.serializer(), req)
        val request = Request.Builder()
            .url("$apiBase/rooms/$roomId/messages")
            .post(bodyStr.jsonBody())
            .auth(roomToken)
            .build()
        return execute(request)
    }

    fun editMessage(roomToken: String, roomId: String, messageId: String, body: Map<String, kotlinx.serialization.json.JsonElement>): MessageResponse {
        val req = EditMessageRequest(body)
        val bodyStr = json.encodeToString(EditMessageRequest.serializer(), req)
        val request = Request.Builder()
            .url("$apiBase/rooms/$roomId/messages/$messageId")
            .patch(bodyStr.jsonBody())
            .auth(roomToken)
            .build()
        return execute(request)
    }

    fun deleteMessage(roomToken: String, roomId: String, messageId: String) {
        val request = Request.Builder()
            .url("$apiBase/rooms/$roomId/messages/$messageId")
            .delete()
            .auth(roomToken)
            .build()
        client.newCall(request).execute()
    }

    fun addReaction(roomToken: String, roomId: String, messageId: String, emoji: String) {
        val body = json.encodeToString(ReactionRequest.serializer(), ReactionRequest(emoji))
        val request = Request.Builder()
            .url("$apiBase/rooms/$roomId/messages/$messageId/reactions")
            .post(body.jsonBody())
            .auth(roomToken)
            .build()
        client.newCall(request).execute()
    }

    fun removeReaction(roomToken: String, roomId: String, messageId: String, emoji: String) {
        val body = json.encodeToString(ReactionRequest.serializer(), ReactionRequest(emoji))
        val request = Request.Builder()
            .url("$apiBase/rooms/$roomId/messages/$messageId/reactions")
            .delete(body.jsonBody())
            .auth(roomToken)
            .build()
        client.newCall(request).execute()
    }

    fun markDelivered(roomToken: String, roomId: String, messageId: String) {
        val request = Request.Builder()
            .url("$apiBase/rooms/$roomId/messages/$messageId/delivered")
            .post("".jsonBody())
            .auth(roomToken)
            .build()
        client.newCall(request).execute()
    }

    fun markRead(roomToken: String, roomId: String, messageId: String) {
        val request = Request.Builder()
            .url("$apiBase/rooms/$roomId/messages/$messageId/read")
            .post("".jsonBody())
            .auth(roomToken)
            .build()
        client.newCall(request).execute()
    }

    fun getUnread(roomToken: String, roomId: String): UnreadResponse {
        val request = Request.Builder()
            .url("$apiBase/rooms/$roomId/unread")
            .get()
            .auth(roomToken)
            .build()
        return execute(request)
    }

    fun getParticipants(roomToken: String, roomId: String): ParticipantsResponse {
        val request = Request.Builder()
            .url("$apiBase/rooms/$roomId/participants")
            .get()
            .auth(roomToken)
            .build()
        return execute(request)
    }

    // ── Group management ──────────────────────────────────────────
    // createGroupConversation + getParticipants are confirmed Ollacore endpoints (Category 1 YES).
    // The rest use conventional Ollacore-style room paths; if the backend answers 404/405 the
    // UI surfaces it as "backend check required" (Category 2) instead of failing silently.

    /** Backend-check: add a member to a group. */
    fun addMember(roomToken: String, roomId: String, userId: String) {
        val body = json.encodeToString(AddMemberRequest.serializer(), AddMemberRequest(userId))
        val request = Request.Builder()
            .url("$apiBase/rooms/$roomId/members")
            .post(body.jsonBody())
            .auth(roomToken)
            .build()
        client.newCall(request).execute().close()
    }

    /** Backend-check: remove a member from a group. */
    fun removeMember(roomToken: String, roomId: String, principalId: String) {
        val request = Request.Builder()
            .url("$apiBase/rooms/$roomId/members/$principalId")
            .delete()
            .auth(roomToken)
            .build()
        client.newCall(request).execute().close()
    }

    /** Backend-check: leave the group (removes self). */
    fun leaveGroup(roomToken: String, roomId: String) {
        val request = Request.Builder()
            .url("$apiBase/rooms/$roomId/members/me")
            .delete()
            .auth(roomToken)
            .build()
        client.newCall(request).execute().close()
    }

    /** Backend-check: rename / set description / change icon. */
    fun updateGroup(roomToken: String, roomId: String, name: String? = null, description: String? = null, iconUrl: String? = null) {
        val body = json.encodeToString(UpdateGroupRequest.serializer(), UpdateGroupRequest(name, description, iconUrl))
        val request = Request.Builder()
            .url("$apiBase/rooms/$roomId")
            .patch(body.jsonBody())
            .auth(roomToken)
            .build()
        client.newCall(request).execute().close()
    }

    /** Backend-check: promote/demote member (role = "admin" | "member"). */
    fun setMemberRole(roomToken: String, roomId: String, principalId: String, role: String) {
        val body = json.encodeToString(MemberRoleRequest.serializer(), MemberRoleRequest(role))
        val request = Request.Builder()
            .url("$apiBase/rooms/$roomId/members/$principalId/role")
            .post(body.jsonBody())
            .auth(roomToken)
            .build()
        client.newCall(request).execute().close()
    }

    /** Backend-check: create a shareable invite for the group. */
    fun createInvite(roomToken: String, roomId: String): GroupInviteResponse {
        val request = Request.Builder()
            .url("$apiBase/rooms/$roomId/invites")
            .post("{}".jsonBody())
            .auth(roomToken)
            .build()
        return execute(request)
    }

    // ── Attachments ─────────────────────────────────────────────────

    fun initAttachment(roomToken: String, roomId: String, filename: String, mimeType: String, byteSize: Long): AttachmentInitResponse {
        val body = """{"filename":"$filename","mime":"$mimeType","byte_size":$byteSize}""".jsonBody()
        val request = Request.Builder()
            .url("$apiBase/rooms/$roomId/attachments/init")
            .post(body)
            .auth(roomToken)
            .build()
        return execute(request)
    }

    fun completeAttachment(roomToken: String, roomId: String, attachmentId: String) {
        val request = Request.Builder()
            .url("$apiBase/rooms/$roomId/attachments/$attachmentId/complete")
            .post("".jsonBody())
            .auth(roomToken)
            .build()
        client.newCall(request).execute()
    }

    fun downloadAttachment(roomToken: String, roomId: String, attachmentId: String): AttachmentDownloadResponse {
        val request = Request.Builder()
            .url("$apiBase/rooms/$roomId/attachments/$attachmentId/download")
            .get()
            .auth(roomToken)
            .build()
        return execute(request)
    }

    // ── Devices ─────────────────────────────────────────────────────

    fun registerDevice(token: String, platform: String, pushToken: String): DeviceResponse {
        val body = json.encodeToString(DeviceRegistration.serializer(), DeviceRegistration(platform, pushToken))
        val request = Request.Builder()
            .url("$apiBase/directory/devices")
            .post(body.jsonBody())
            .auth(token)
            .build()
        return execute(request)
    }

    fun listDevices(token: String): DeviceListResponse {
        val request = Request.Builder()
            .url("$apiBase/directory/devices")
            .get()
            .auth(token)
            .build()
        return execute(request)
    }

    fun deleteDevice(token: String, pushToken: String) {
        val body = """{"push_token":"$pushToken"}""".jsonBody()
        val request = Request.Builder()
            .url("$apiBase/directory/devices")
            .delete(body)
            .auth(token)
            .build()
        client.newCall(request).execute()
    }

    // ── Search Messages ─────────────────────────────────────────────

    fun searchMessages(roomToken: String, roomId: String, query: String, limit: Int = 20): MessageListResponse {
        val request = Request.Builder()
            .url("$apiBase/rooms/$roomId/messages/search?q=$query&limit=$limit")
            .get()
            .auth(roomToken)
            .build()
        return execute(request)
    }

    // ── Multipart Upload ────────────────────────────────────────────

    fun initMultipartUpload(roomToken: String, roomId: String, filename: String, mimeType: String, byteSize: Long, partSize: Long = 8388608): MultipartInitResponse {
        val body = """{"original_name":"$filename","declared_mime":"$mimeType","byte_size":$byteSize,"part_size":$partSize}""".jsonBody()
        val request = Request.Builder()
            .url("$apiBase/rooms/$roomId/attachments/init-multipart")
            .post(body)
            .auth(roomToken)
            .build()
        return execute(request)
    }

    fun completeMultipartUpload(roomToken: String, roomId: String, attachmentId: String, parts: List<MultipartPart>) {
        val partsJson = parts.joinToString(",") { """{"part_number":${it.partNumber},"etag":"${it.etag}"}""" }
        val body = """{"parts":[$partsJson]}""".jsonBody()
        val request = Request.Builder()
            .url("$apiBase/rooms/$roomId/attachments/$attachmentId/complete-multipart")
            .post(body)
            .auth(roomToken)
            .build()
        client.newCall(request).execute()
    }

    // ── E2EE Key Management ─────────────────────────────────────────

    fun uploadKeys(roomToken: String, deviceId: String, deviceKeys: Map<String, kotlinx.serialization.json.JsonElement>, oneTimeKeys: Map<String, kotlinx.serialization.json.JsonElement>): E2eeKeyUploadResponse {
        val body = buildString {
            append("""{"device_id":"$deviceId","device_keys":${json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.JsonObject(deviceKeys))},"one_time_keys":${json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.JsonObject(oneTimeKeys))}}""")
        }.jsonBody()
        val request = Request.Builder()
            .url("$apiBase/e2ee/keys/upload")
            .post(body)
            .auth(roomToken)
            .build()
        return execute(request)
    }

    fun queryKeys(roomToken: String, query: Map<String, List<String>>): E2eeKeyQueryResponse {
        val queryJson = query.entries.joinToString(",") { (principal, devices) ->
            val devicesJson = devices.joinToString(",") { "\"$it\"" }
            """\"$principal\":[$devicesJson]"""
        }
        val body = """{"device_keys":{$queryJson}}""".jsonBody()
        val request = Request.Builder()
            .url("$apiBase/e2ee/keys/query")
            .post(body)
            .auth(roomToken)
            .build()
        return execute(request)
    }

    fun claimKeys(roomToken: String, claims: Map<String, Map<String, String>>): E2eeKeyClaimResponse {
        val claimsJson = claims.entries.joinToString(",") { (principal, devices) ->
            val devicesJson = devices.entries.joinToString(",") { (device, algo) -> """\"$device\":\"$algo\"""" }
            """\"$principal\":{$devicesJson}"""
        }
        val body = """{"one_time_keys":{$claimsJson}}""".jsonBody()
        val request = Request.Builder()
            .url("$apiBase/e2ee/keys/claim")
            .post(body)
            .auth(roomToken)
            .build()
        return execute(request)
    }

    fun sendToDevice(roomToken: String, eventType: String, messages: Map<String, Map<String, kotlinx.serialization.json.JsonElement>>) {
        val messagesJson = messages.entries.joinToString(",") { (principal, devices) ->
            val devicesJson = devices.entries.joinToString(",") { (device, content) ->
                """\"$device\":${json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), content)}"""
            }
            """\"$principal\":{$devicesJson}"""
        }
        val body = """{"event_type":"$eventType","messages":{$messagesJson}}""".jsonBody()
        val request = Request.Builder()
            .url("$apiBase/e2ee/todevice")
            .post(body)
            .auth(roomToken)
            .build()
        client.newCall(request).execute()
    }

    fun fetchToDeviceMessages(roomToken: String): ToDeviceMessagesResponse {
        val request = Request.Builder()
            .url("$apiBase/e2ee/todevice")
            .get()
            .auth(roomToken)
            .build()
        return execute(request)
    }

    fun uploadKeyPackages(roomToken: String, deviceId: String, packages: List<String>) {
        val packagesJson = packages.joinToString(",") { "\"$it\"" }
        val body = """{"device_id":"$deviceId","packages":[$packagesJson]}""".jsonBody()
        val request = Request.Builder()
            .url("$apiBase/keypackages")
            .post(body)
            .auth(roomToken)
            .build()
        client.newCall(request).execute()
    }

    fun getKeyPackageCount(roomToken: String): KeyPackageCountResponse {
        val request = Request.Builder()
            .url("$apiBase/keypackages/count")
            .get()
            .auth(roomToken)
            .build()
        return execute(request)
    }

    fun consumeKeyPackages(roomToken: String, roomId: String, principalId: String): KeyPackageConsumeResponse {
        val request = Request.Builder()
            .url("$apiBase/rooms/$roomId/keypackages/$principalId")
            .get()
            .auth(roomToken)
            .build()
        return execute(request)
    }

    fun getJsonParser(): Json = json
}

class ApiException(
    message: String,
    val code: String? = null,
    val httpStatus: Int? = null
) : Exception(message)
