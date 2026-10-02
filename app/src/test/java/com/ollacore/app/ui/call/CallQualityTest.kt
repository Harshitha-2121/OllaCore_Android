package com.ollacore.app.ui.call

import org.junit.Assert.*
import org.junit.Test

class CallQualityTest {

    private val good = CallQuality.Sample(received = 1000, lost = 0)
    private val fair = CallQuality.Sample(received = 950, lost = 50)   // 5%
    private val poor = CallQuality.Sample(received = 850, lost = 150)  // 15%

    @Test
    fun `first sample is always good - unknown is not bad`() {
        assertEquals(CallQualityLevel.GOOD, CallQuality.classify(null, poor))
    }

    @Test
    fun `no loss delta is good`() {
        val prev = CallQuality.Sample(100, 5)
        val curr = CallQuality.Sample(200, 5) // no new loss
        assertEquals(CallQualityLevel.GOOD, CallQuality.classify(prev, curr))
    }

    @Test
    fun `fair band classifies as fair`() {
        val curr = CallQuality.Sample(good.received + fair.received, good.lost + fair.lost)
        assertEquals(CallQualityLevel.FAIR, CallQuality.classify(good, curr))
    }

    @Test
    fun `poor band classifies as poor`() {
        val curr = CallQuality.Sample(good.received + poor.received, good.lost + poor.lost)
        assertEquals(CallQualityLevel.POOR, CallQuality.classify(good, curr))
    }

    @Test
    fun `thresholds are inclusive`() {
        // exactly 10% -> POOR
        val atPoor = CallQuality.Sample(received = 900, lost = 100)
        assertEquals(CallQualityLevel.POOR, CallQuality.classify(CallQuality.Sample(0, 0), atPoor))
        // exactly 3% -> FAIR
        val atFair = CallQuality.Sample(received = 970, lost = 30)
        assertEquals(CallQualityLevel.FAIR, CallQuality.classify(CallQuality.Sample(0, 0), atFair))
        // just under 3% -> GOOD
        val under = CallQuality.Sample(received = 971, lost = 29)
        assertEquals(CallQualityLevel.GOOD, CallQuality.classify(CallQuality.Sample(0, 0), under))
    }

    @Test
    fun `counter reset does not produce garbage - deltas clamp to zero`() {
        val prev = CallQuality.Sample(10_000, 500)
        val reset = CallQuality.Sample(10, 1) // stats reset mid-call
        assertEquals(CallQualityLevel.GOOD, CallQuality.classify(prev, reset))
    }

    // ── formatCallDuration ─────────────────────────────────────────

    @Test
    fun `duration formats as mm ss under an hour`() {
        assertEquals("00:00", formatCallDuration(0))
        assertEquals("00:07", formatCallDuration(7))
        assertEquals("01:07", formatCallDuration(67))
        assertEquals("59:59", formatCallDuration(3599))
    }

    @Test
    fun `duration formats as h mm ss from an hour`() {
        assertEquals("1:00:00", formatCallDuration(3600))
        assertEquals("2:02:02", formatCallDuration(7322))
    }

    @Test
    fun `negative duration clamps to zero`() {
        assertEquals("00:00", formatCallDuration(-5))
    }
}
