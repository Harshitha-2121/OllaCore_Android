package com.ollacore.app.ui.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

/**
 * Process-wide call feedback: outgoing ringback, incoming ringtone + vibration.
 *
 * Singleton so both call sites (in-app ringing dialog in ChatViewModel and the
 * call screen in CallViewModel) drive one audio stream - accept/decline in
 * either place stops the sound for good. Uses the system ringtone (user's own
 * ring volume/pattern, no bundled assets) and ToneGenerator ringback over the
 * voice-call stream; vibrate repeats while ringing.
 */
object CallRinger {

    enum class Mode { NONE, RINGBACK, INCOMING }

    private const val TAG = "[CALL_RINGER]"
    private const val RINGBACK_REPEAT_MS = 4_000L
    private const val RINGBACK_TONE_MS = 1_000
    private const val API_LOOPING_RINGTONE = 28 // Ringtone.isLooping needs API 28

    private val lock = Any()
    private var mode = Mode.NONE
    private var tone: ToneGenerator? = null
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var handler: Handler? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    /** Idempotent mode switch; safe to call from the main thread only. */
    fun setMode(context: Context, newMode: Mode) {
        synchronized(lock) {
            if (mode == newMode) return
            stopLocked()
            if (newMode != Mode.NONE) startLocked(context.applicationContext, newMode)
            mode = newMode
        }
    }

    fun stop() {
        synchronized(lock) {
            stopLocked()
            mode = Mode.NONE
        }
    }

    /** Current mode (tests/diagnostics). */
    fun currentMode(): Mode = synchronized(lock) { mode }

    // ── internals (always under lock) ─────────────────────────────────

    private fun h(): Handler = handler ?: Handler(Looper.getMainLooper()).also { handler = it }

    private fun am(context: Context): AudioManager {
        audioManager?.let { return it }
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = manager
        return manager
    }

    private fun requestFocus(context: Context, usage: Int) {
        runCatching {
            val attrs = AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(false)
                .build()
            am(context).requestAudioFocus(req)
            focusRequest = req
        }.onFailure { Log.w(TAG, "audio focus request failed: ${it.message}") }
    }

    private fun abandonFocus() {
        val manager = audioManager ?: return
        runCatching {
            focusRequest?.let { manager.abandonAudioFocusRequest(it) }
        }
        focusRequest = null
    }

    private fun startLocked(context: Context, m: Mode) {
        requestFocus(
            context,
            if (m == Mode.INCOMING) AudioAttributes.USAGE_NOTIFICATION_RINGTONE
            else AudioAttributes.USAGE_VOICE_COMMUNICATION
        )
        when (m) {
            Mode.RINGBACK -> startRingbackLocked()
            Mode.INCOMING -> startIncomingLocked(context)
            Mode.NONE -> Unit
        }
    }

    private fun startRingbackLocked() {
        runCatching {
            tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70)
        }.onFailure {
            Log.w(TAG, "ToneGenerator unavailable: ${it.message}")
            tone = null
        }
        val tick = object : Runnable {
            override fun run() {
                synchronized(lock) {
                    if (mode != Mode.RINGBACK) return
                    runCatching { tone?.startTone(ToneGenerator.TONE_SUP_RINGTONE, RINGBACK_TONE_MS) }
                    h().postDelayed(this, RINGBACK_REPEAT_MS)
                }
            }
        }
        h().post(tick)
    }

    private fun startIncomingLocked(context: Context) {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            if (uri != null) {
                ringtone = RingtoneManager.getRingtone(context, uri)
                ringtone?.play()
                if (Build.VERSION.SDK_INT >= API_LOOPING_RINGTONE) {
                    ringtone?.isLooping = true
                } else {
                    // API 26-27: no looping API - restart while still ringing.
                    val replay = object : Runnable {
                        override fun run() {
                            synchronized(lock) {
                                if (mode != Mode.INCOMING) return
                                runCatching { ringtone?.play() }
                                h().postDelayed(this, RINGBACK_REPEAT_MS)
                            }
                        }
                    }
                    h().postDelayed(replay, RINGBACK_REPEAT_MS)
                }
            }
        }.onFailure { Log.w(TAG, "ringtone start failed: ${it.message}") }

        runCatching {
            vibrator = context.getSystemService(Vibrator::class.java)
            if (vibrator?.hasVibrator() == true) {
                // 500ms buzz / 1500ms pause, repeating until stopped.
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 500, 1500), 0)
                )
            }
        }.onFailure { Log.w(TAG, "vibration failed: ${it.message}") }
    }

    private fun stopLocked() {
        runCatching { handler?.removeCallbacksAndMessages(null) }
        runCatching { tone?.stopTone() }
        runCatching { tone?.release() }
        tone = null
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { vibrator?.cancel() }
        vibrator = null
        abandonFocus()
    }
}
