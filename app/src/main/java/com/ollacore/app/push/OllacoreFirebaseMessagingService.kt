package com.ollacore.app.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.Lifecycle
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.ollacore.app.MainActivity
import com.ollacore.app.R
import com.ollacore.app.data.local.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class OllacoreFirebaseMessagingService : FirebaseMessagingService() {

    private lateinit var pushManager: ContentFreePushManager
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        pushManager = ContentFreePushManager(this)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Token will be registered when user logs in
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val data = message.data
        val type = data["type"] ?: return

        // Check if app is in foreground
        val isForeground = isAppInForeground()

        when (type) {
            // Message pushes carry both notification + data, display even if app does nothing
            "message" -> {
                val roomId = data["room_id"] ?: return
                // data keys per updated docs: type, room_id, call_id, sender, encrypted
                val sender = data["sender"] ?: data["sender_id"] ?: "Someone"
                val preview = data["preview"] ?: "New message"
                val isEncrypted = data["encrypted"]?.toBoolean() == true ||
                                  pushManager.isRoomEncrypted(roomId)

                pushManager.handleMessageNotification(
                    roomId = roomId,
                    senderId = sender,
                    preview = preview,
                    isEncrypted = isEncrypted,
                    isForeground = isForeground
                )
            }
            // Legacy alias
            "message.created" -> {
                val roomId = data["room_id"] ?: return
                val sender = data["sender"] ?: data["sender_id"] ?: "Someone"
                val preview = data["preview"] ?: "New message"
                val isEncrypted = data["encrypted"]?.toBoolean() == true ||
                                  pushManager.isRoomEncrypted(roomId)
                pushManager.handleMessageNotification(roomId, sender, preview, isEncrypted, isForeground)
            }
            // Incoming-call push: NO notification block, only data - must post own full-screen notification
            // Keys: type=call.started, room_id, call_id, sender, encrypted
            "call.started" -> {
                val roomId = data["room_id"] ?: return
                val callId = data["call_id"] ?: roomId
                val sender = data["sender"] ?: data["initiator"] ?: "Someone"
                val isEncrypted = data["encrypted"]?.toBoolean() == true ||
                                  pushManager.isRoomEncrypted(roomId)
                // Rings expire after 45s - drop if device was offline and push is stale
                val sentAt = data["sent_at"]?.toLongOrNull() ?: System.currentTimeMillis()
                if (System.currentTimeMillis() - sentAt > 45_000) return

                pushManager.handleCallNotification(
                    roomId = roomId,
                    initiatorName = sender,
                    isEncrypted = isEncrypted,
                    callId = callId
                )
            }
            // Silent cancel: remove notification posted for that call_id, or phone keeps ringing
            "call.ended" -> {
                val callId = data["call_id"] ?: data["room_id"] ?: return
                pushManager.cancelCallNotification(callId)
            }
        }
    }

    private fun isAppInForeground(): Boolean {
        val app = application as? com.ollacore.app.OllacoreApp
        return ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    }

    private fun showNotification(title: String, body: String, roomId: String, isCall: Boolean = false) {
        val channelId = if (isCall) "call_channel" else "message_channel"
        val channelName = if (isCall) "Calls" else "Messages"

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            channelId,
            channelName,
            if (isCall) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT
        )
        notificationManager.createNotificationChannel(channel)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("room_id", roomId)
            putExtra("open_chat", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(if (isCall) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
