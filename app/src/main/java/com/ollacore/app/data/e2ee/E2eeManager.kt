package com.ollacore.app.data.e2ee

import com.ollacore.app.data.model.EncryptedMessageKind
import com.ollacore.app.data.remote.OllacoreApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

class E2eeManager(private val api: OllacoreApi) {

    suspend fun uploadDeviceKeys(
        roomToken: String,
        deviceId: String,
        deviceKeys: Map<String, JsonElement>,
        oneTimeKeys: Map<String, JsonElement>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            api.uploadKeys(roomToken, deviceId, deviceKeys, oneTimeKeys)
            Unit
        }
    }

    suspend fun queryDeviceKeys(
        roomToken: String,
        principals: Map<String, List<String>>
    ): Result<Map<String, List<com.ollacore.app.data.model.DeviceKeyInfo>>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = api.queryKeys(roomToken, principals)
            response.deviceKeys
        }
    }

    suspend fun claimOneTimeKey(
        roomToken: String,
        claims: Map<String, Map<String, String>>
    ): Result<Map<String, Map<String, com.ollacore.app.data.model.DeviceKeyInfo>>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = api.claimKeys(roomToken, claims)
            response.oneTimeKeys
        }
    }

    suspend fun sendToDeviceMessage(
        roomToken: String,
        eventType: String,
        messages: Map<String, Map<String, JsonElement>>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            api.sendToDevice(roomToken, eventType, messages)
        }
    }

    suspend fun fetchPendingToDeviceMessages(
        roomToken: String
    ): Result<List<com.ollacore.app.data.model.ToDeviceMessage>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = api.fetchToDeviceMessages(roomToken)
            response.messages
        }
    }

    suspend fun uploadKeyPackages(
        roomToken: String,
        deviceId: String,
        packages: List<String>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            api.uploadKeyPackages(roomToken, deviceId, packages)
        }
    }

    suspend fun getKeyPackageCount(
        roomToken: String
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val response = api.getKeyPackageCount(roomToken)
            response.count
        }
    }

    suspend fun consumeKeyPackage(
        roomToken: String,
        roomId: String,
        principalId: String
    ): Result<com.ollacore.app.data.model.KeyPackageConsumeResponse> = withContext(Dispatchers.IO) {
        runCatching {
            api.consumeKeyPackages(roomToken, roomId, principalId)
        }
    }

    fun isEncryptedKind(kind: String): Boolean {
        return EncryptedMessageKind.isEncryptedKind(kind)
    }

    fun getEncryptedKind(kind: String): EncryptedMessageKind? {
        return EncryptedMessageKind.fromValue(kind)
    }

    suspend fun rotateSession(
        roomToken: String,
        roomId: String,
        deviceId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // Fetch new key packages and upload
            val count = getKeyPackageCount(roomToken).getOrNull() ?: 0
            if (count < KEY_PACKAGE_THRESHOLD) {
                val packages = generateKeyPackages(deviceId)
                uploadKeyPackages(roomToken, deviceId, packages)
            }
        }
    }

    private fun generateKeyPackages(deviceId: String): List<String> {
        // In a real implementation, this would generate MLS key packages
        // using a crypto library like libsodium or Bouncy Castle
        return listOf("key_package_${deviceId}_${System.currentTimeMillis()}")
    }

    companion object {
        private const val KEY_PACKAGE_THRESHOLD = 10
    }
}
