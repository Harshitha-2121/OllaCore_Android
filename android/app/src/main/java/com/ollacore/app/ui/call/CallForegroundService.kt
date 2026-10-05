package com.ollacore.app.ui.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ollacore.app.MainActivity
import com.ollacore.app.R

/**
 * Call foreground service: keeps the WebRTC call process alive while the app
 * is backgrounded (Home button) and advertises the microphone/camera (and
 * mediaProjection while screen sharing) foreground-service types that Android
 * 14+ requires for exactly this usage.
 *
 * Started by CallViewModel when a call begins and stopped on teardown. The
 * notification is intentionally low-importance and silent - it is a lifecycle
 * marker, not an alert (ringtone/vibration are handled by [CallRinger]).
 */
class CallForegroundService : Service() {

    companion object {
        private const val TAG = "[CALL_FGS]"
        const val CHANNEL_ID = "call_active"
        const val NOTIFICATION_ID = 4101

        private const val EXTRA_PEER = "extra_peer_name"
        private const val EXTRA_AUDIO_ONLY = "extra_audio_only"
        private const val EXTRA_SCREEN_SHARE = "extra_screen_share"

        fun start(context: Context, peerName: String, audioOnly: Boolean) {
            val intent = Intent(context, CallForegroundService::class.java)
                .putExtra(EXTRA_PEER, peerName)
                .putExtra(EXTRA_AUDIO_ONLY, audioOnly)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "startForegroundService failed: ${it.message}") }
        }

        /** Re-promote with/without the mediaProjection type when screen share toggles. */
        fun setScreenShare(context: Context, active: Boolean) {
            val intent = Intent(context, CallForegroundService::class.java)
                .putExtra(EXTRA_SCREEN_SHARE, active)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "screen-share promote failed: ${it.message}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CallForegroundService::class.java)) }
        }
    }

    private var peerName: String = ""
    private var audioOnly: Boolean = false
    private var screenShare: Boolean = false

    override fun onCreate() {
        super.onCreate()
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Active call",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while a call is in progress"
                setSound(null, null)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            if (it.hasExtra(EXTRA_PEER)) peerName = it.getStringExtra(EXTRA_PEER) ?: peerName
            if (it.hasExtra(EXTRA_AUDIO_ONLY)) {
                audioOnly = it.getBooleanExtra(EXTRA_AUDIO_ONLY, audioOnly)
            }
            if (it.hasExtra(EXTRA_SCREEN_SHARE)) {
                screenShare = it.getBooleanExtra(EXTRA_SCREEN_SHARE, screenShare)
            }
        }
        promoteToForeground()
        // Not sticky: if the process dies the call is gone anyway (ViewModel
        // owns the PeerConnection) - restarting a zombie notification helps nobody.
        return START_NOT_STICKY
    }

    private fun serviceType(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0 // type ignored pre-29
        var type = 0
        // Microphone/camera FGS types exist from API 30. Voice calls only need
        // the microphone type (camera permission is not granted for audio-only).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (!audioOnly) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
        }
        if (screenShare) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        return type
    }

    private fun promoteToForeground() {
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), serviceType())
        } catch (e: Exception) {
            // Permission revoked mid-call, OEM restrictions, etc. - never crash a call over a notification.
            Log.w(TAG, "startForeground failed: ${e.message}")
            runCatching { stopSelf() }
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val label = when {
            peerName.isNotBlank() -> peerName
            audioOnly -> "Voice call in progress"
            else -> "Video call in progress"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Call in progress")
            .setContentText(label)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp)
            .setShowWhen(false)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
