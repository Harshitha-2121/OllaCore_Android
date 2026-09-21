package com.ollacore.app.data.push

import com.ollacore.app.data.model.E2eeConfig

class PushConfigManager {

    private var e2eeConfig = E2eeConfig()

    fun setE2eeConfig(config: E2eeConfig) {
        e2eeConfig = config
    }

    fun isContentFreePushEnabled(): Boolean {
        return e2eeConfig.contentFreePush
    }

    fun isRoomEncrypted(): Boolean {
        return e2eeConfig.encrypted
    }

    fun shouldSuppressContent(): Boolean {
        return e2eeConfig.encrypted || e2eeConfig.contentFreePush
    }

    fun getPushPayload(
        senderName: String?,
        preview: String?,
        roomName: String?
    ): PushPayload {
        if (shouldSuppressContent()) {
            return PushPayload(
                title = "New message",
                body = "You have a new message",
                hasContent = false
            )
        }

        return PushPayload(
            title = senderName ?: "Unknown",
            body = preview ?: "New message",
            hasContent = true
        )
    }

    fun getCallPushPayload(
        initiatorName: String?
    ): PushPayload {
        return PushPayload(
            title = "Incoming call",
            body = "${initiatorName ?: "Someone"} is calling",
            hasContent = true
        )
    }
}

data class PushPayload(
    val title: String,
    val body: String,
    val hasContent: Boolean
)
