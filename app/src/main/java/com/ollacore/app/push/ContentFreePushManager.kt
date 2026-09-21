package com.ollacore.app.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.ollacore.app.MainActivity
import com.ollacore.app.R
import com.ollacore.app.data.local.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ContentFreePushManager(private val context: Context) {

    private val sessionStore = SessionStore(context)
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val scope = CoroutineScope(Dispatchers.IO)

    // Track E2EE room states (roomId -> isEncrypted)
    private val encryptedRooms = mutableSetOf<String>()

    // Notification channels
    companion object {
        const val CHANNEL_MESSAGES = "message_channel"
        const val CHANNEL_MESSAGES_E2EE = "message_channel_e2ee"
        const val CHANNEL_CALLS = "call_channel"
        const val CHANNEL_CALLS_E2EE = "call_channel_e2ee"
    }

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        // Regular message channel
        val messageChannel = NotificationChannel(
            CHANNEL_MESSAGES,
            "Messages",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Regular chat messages"
        }

        // E2EE message channel (no preview)
        val e2eeMessageChannel = NotificationChannel(
            CHANNEL_MESSAGES_E2EE,
            "Encrypted Messages",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Encrypted messages - content hidden"
            setShowBadge(true)
        }

        // Regular call channel
        val callChannel = NotificationChannel(
            CHANNEL_CALLS,
            "Calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming calls"
        }

        // E2EE call channel
        val e2eeCallChannel = NotificationChannel(
            CHANNEL_CALLS_E2EE,
            "Encrypted Calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming encrypted calls"
        }

        notificationManager.createNotificationChannels(listOf(
            messageChannel, e2eeMessageChannel, callChannel, e2eeCallChannel
        ))
    }

    fun markRoomEncrypted(roomId: String) {
        synchronized(encryptedRooms) {
            encryptedRooms.add(roomId)
        }
    }

    fun markRoomDecrypted(roomId: String) {
        synchronized(encryptedRooms) {
            encryptedRooms.remove(roomId)
        }
    }

    fun isRoomEncrypted(roomId: String): Boolean {
        synchronized(encryptedRooms) {
            return roomId in encryptedRooms
        }
    }

    fun handleMessageNotification(
        roomId: String,
        senderId: String,
        preview: String,
        isEncrypted: Boolean = false,
        isForeground: Boolean = false
    ) {
        // CLIENT-ONLY mute (ChatPrefsStore): muted chats stay silent (calls still ring).
        runCatching {
            kotlinx.coroutines.runBlocking {
                com.ollacore.app.data.local.ChatPrefsStore(context).isMuted(roomId)
            }
        }.onSuccess { muted ->
            if (muted) return
        }
        // Spec 28 categories (CLIENT-ONLY, enforced here): messages toggle +
        // mentions-only mode (best-effort "@" match on the data payload preview).
        val prefs = com.ollacore.app.data.local.ChatPrefsStore(context)
        runCatching {
            kotlinx.coroutines.runBlocking { prefs.getNotif("messages", true) }
        }.onSuccess { enabled ->
            if (!enabled) return
        }
        runCatching {
            kotlinx.coroutines.runBlocking { prefs.getNotif("mentions_only", false) }
        }.onSuccess { mentionsOnly ->
            if (mentionsOnly && !preview.contains("@")) return
        }
        val channel = if (isEncrypted) CHANNEL_MESSAGES_E2EE else CHANNEL_MESSAGES

        // For content-free mode: strip all sensitive content
        val (title, body) = if (isEncrypted) {
            // Never expose content for E2EE rooms
            Pair(
                "New message",
                "You have a new encrypted message"
            )
        } else {
            // Regular rooms can show preview
            Pair(senderId, preview)
        }

        // Don't show notification if in foreground and not encrypted
        if (isForeground && !isEncrypted) {
            return
        }

        showNotification(
            title = title,
            body = body,
            roomId = roomId,
            channelId = channel,
            isCall = false
        )
    }

    fun handleCallNotification(
        roomId: String,
        initiatorName: String,
        isEncrypted: Boolean = false,
        callId: String? = null
    ) {
        // Spec 28: calls toggle (CLIENT-ONLY).
        runCatching {
            kotlinx.coroutines.runBlocking {
                com.ollacore.app.data.local.ChatPrefsStore(context).getNotif("calls", true)
            }
        }.onSuccess { enabled ->
            if (!enabled) return
        }
        val channel = if (isEncrypted) CHANNEL_CALLS_E2EE else CHANNEL_CALLS
        val effectiveCallId = callId ?: roomId

        val (title, body) = if (isEncrypted) {
            Pair("Incoming call", "You have an incoming encrypted call")
        } else {
            Pair("Incoming call", "$initiatorName is calling")
        }

        // Full-screen call notification - required because call pushes are data-only
        showNotification(
            title = title,
            body = body,
            roomId = roomId,
            channelId = channel,
            isCall = true,
            callId = effectiveCallId
        )
    }

    fun cancelCallNotification(callId: String) {
        notificationManager.cancel(callId.hashCode())
        // Also cancel by room fallback
        notificationManager.cancel(callId.hashCode() + 100000)
    }

    private fun showNotification(
        title: String,
        body: String,
        roomId: String,
        channelId: String,
        isCall: Boolean = false,
        callId: String? = null
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("room_id", roomId)
            putExtra("is_encrypted", isRoomEncrypted(roomId))
            if (isCall) {
                // Ringing tap opens the call screen directly (incoming phase).
                putExtra("open_call", true)
                putExtra("incoming", true)
                callId?.let { putExtra("call_id", it) }
            } else {
                putExtra("open_chat", true)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            roomId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationId = if (isCall) {
            (callId ?: roomId).hashCode()
        } else {
            roomId.hashCode()
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(
                if (isCall) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setGroup("chat_notifications")
            .setSilent(!isCall)

        // Full-screen intent for incoming calls (data-only push requires own notification)
        if (isCall) {
            builder.setCategory(NotificationCompat.CATEGORY_CALL)
                 .setFullScreenIntent(pendingIntent, true)
                 .setOngoing(true)
        }

        notificationManager.notify(notificationId, builder.build())
    }

    fun cancelNotification(roomId: String) {
        notificationManager.cancel(roomId.hashCode())
    }

    fun cancelAllNotifications() {
        notificationManager.cancelAll()
    }

    fun getActiveNotifications(): List<StatusBarNotification> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.activeNotifications.toList()
        } else {
            emptyList()
        }
    }

    fun loadEncryptedRooms() {
        scope.launch {
            // Load encrypted rooms from local storage or API
            // This would typically come from the room metadata
        }
    }
}
