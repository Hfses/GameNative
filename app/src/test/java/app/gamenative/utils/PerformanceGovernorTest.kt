package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the thermal FPS-cap decision logic in [PerformanceGovernor.suggestedCap].
 * The platform-dependent parts (getThermalHeadroom, PerformanceHintManager) are guarded and
 * not exercised here.
 */
class PerformanceGovernorTest {

    @Test
    fun `no thermal signal leaves the cap unchanged`() {
        assertEquals(60, PerformanceGovernor.suggestedCap(60, Float.NaN))
    }

    @Test
    fun `comfortable temperature leaves the cap unchanged`() {
        assertEquals(60, PerformanceGovernor.suggestedCap(60, 0.5f))
        assertEquals(60, PerformanceGovernor.suggestedCap(60, PerformanceGovernor.HEADROOM_WARN - 0.01f))
    }

    @Test
    fun `warm temperature scales the cap to eighty percent`() {
        // 60 * 0.8 = 48
        assertEquals(48, PerformanceGovernor.suggestedCap(60, 0.90f))
    }

    @Test
    fun `hot temperature scales the cap to sixty percent`() {
        // 60 * 0.6 = 36
        assertEquals(36, PerformanceGovernor.suggestedCap(60, 0.97f))
    }

    @Test
    fun `cap never drops below the floor`() {
        // 40 * 0.6 = 24, floored to MIN_CAP (30)
        assertEquals(PerformanceGovernor.MIN_CAP, PerformanceGovernor.suggestedCap(40, 1.0f))
    }

    @Test
    fun `uncapped stays uncapped regardless of temperature`() {
        assertEquals(0, PerformanceGovernor.suggestedCap(0, 1.2f))
    }

    @Test
    fun `suggested cap never exceeds the base cap`() {
        val base = 45
        for (h in intArrayOf(0, 50, 84, 85, 90, 95, 100, 120)) {
            val cap = PerformanceGovernor.suggestedCap(base, h / 100f)
            assertTrue("headroom=$h produced cap=$cap > base=$base", cap <= base)
        }
    }
}
