package com.ollacore.app.data.repository

import com.ollacore.app.data.model.*
import com.ollacore.app.data.remote.OllacoreApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DirectoryRepository(private val api: OllacoreApi) {

    suspend fun requestOtp(phone: String): Result<OtpResponse> = withContext(Dispatchers.IO) {
        runCatching { api.requestOtp(phone) }
    }

    suspend fun verifyOtp(phone: String, code: String): Result<OtpVerifyResponse> = withContext(Dispatchers.IO) {
        runCatching { api.verifyOtp(phone, code) }
    }

    suspend fun getProfile(token: String): Result<UserProfile> = withContext(Dispatchers.IO) {
        runCatching { api.getProfile(token) }
    }

    suspend fun updateProfile(token: String, displayName: String): Result<UserProfile> = withContext(Dispatchers.IO) {
        runCatching { api.updateProfile(token, displayName) }
    }

    suspend fun lookupContacts(token: String, phones: List<String>): Result<ContactLookupResponse> = withContext(Dispatchers.IO) {
        runCatching { api.lookupContacts(token, phones) }
    }

    suspend fun listConversations(token: String): Result<ConversationListResponse> = withContext(Dispatchers.IO) {
        runCatching { api.listConversations(token) }
    }

    suspend fun openDirectConversation(token: String, peerUserId: String): Result<ConversationResponse> = withContext(Dispatchers.IO) {
        runCatching { api.openDirectConversation(token, peerUserId) }
    }

    suspend fun createGroupConversation(token: String, memberUserIds: List<String>, name: String): Result<ConversationResponse> = withContext(Dispatchers.IO) {
        runCatching { api.createGroupConversation(token, memberUserIds, name) }
    }

    suspend fun getRoomToken(token: String, roomId: String, deviceId: String): Result<RoomTokenResponse> = withContext(Dispatchers.IO) {
        runCatching { api.getRoomToken(token, roomId, deviceId) }
    }

    suspend fun getInbox(token: String, limit: Int = 30): Result<InboxResponse> = withContext(Dispatchers.IO) {
        runCatching { api.getInbox(token, limit) }
    }

    suspend fun registerDevice(token: String, platform: String, pushToken: String): Result<DeviceResponse> = withContext(Dispatchers.IO) {
        runCatching { api.registerDevice(token, platform, pushToken) }
    }

    suspend fun deleteDevice(token: String, pushToken: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { api.deleteDevice(token, pushToken) }
    }

    suspend fun listDevices(token: String): Result<DeviceListResponse> = withContext(Dispatchers.IO) {
        runCatching { api.listDevices(token) }
    }
}
