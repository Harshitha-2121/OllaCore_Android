package com.ollacore.app.ui.call

/** Call media quality derived from WebRTC stats (packet loss over time). */
enum class CallQualityLevel { GOOD, FAIR, POOR }

/**
 * Pure packet-loss classifier (JVM-testable): compares two successive
 * inbound-rtp samples and classifies the loss ratio of the delta window.
 *
 * ratio >= 10% -> POOR (banner shown), >= 3% -> FAIR (silent note), else GOOD.
 * No data (first sample) is GOOD - unknown is not bad.
 */
object CallQuality {

    data class Sample(val received: Long, val lost: Long)

    const val POOR_THRESHOLD = 0.10
    const val FAIR_THRESHOLD = 0.03

    fun classify(prev: Sample?, curr: Sample): CallQualityLevel {
        if (prev == null) return CallQualityLevel.GOOD
        val dReceived = (curr.received - prev.received).coerceAtLeast(0L)
        val dLost = (curr.lost - prev.lost).coerceAtLeast(0L)
        val total = dReceived + dLost
        if (total <= 0L) return CallQualityLevel.GOOD
        val ratio = dLost.toDouble() / total.toDouble()
        return when {
            ratio >= POOR_THRESHOLD -> CallQualityLevel.POOR
            ratio >= FAIR_THRESHOLD -> CallQualityLevel.FAIR
            else -> CallQualityLevel.GOOD
        }
    }
}

/** mm:ss call duration label ("00:07", "12:34", "1:02:03"). Pure - JVM-testable. */
fun formatCallDuration(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0L)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec)
    else "%02d:%02d".format(m, sec)
}
