package app.gamenative.utils

import android.content.Context
import com.winlator.box86_64.Box86_64Preset

/**
 * Turns the on-device telemetry ([TelemetryCollector]) and thermal signal ([PerformanceGovernor])
 * into a single, actionable performance tip for the in-game "Dicas" (Tips) tab. Pure decision logic
 * plus two guarded reads — it never touches the container; applying the tip is the caller's job.
 */
object TipsAdvisor {

    enum class Reason { LOW_FPS, THERMAL, CRASHES, HEALTHY }

    /**
     * @param box64Preset preset id to apply, or null to leave the current one.
     * @param fpsCap suggested FPS cap, or null to leave the current limiter.
     */
    data class Tip(
        val reason: Reason,
        val box64Preset: String?,
        val fpsCap: Int?,
    )

    private const val LOW_FPS = 30.0
    private const val CRASHES_FOR_STABILITY = 2

    /**
     * @param liveAvgFps average FPS of the live session (0 falls back to the historical average).
     * @param currentFpsCap the container's current FPS limiter (0 = uncapped).
     */
    fun advise(
        context: Context,
        appId: String,
        liveAvgFps: Double,
        currentFpsCap: Int,
    ): Tip {
        val summary = TelemetryCollector.summary(context, appId)
        val headroom = PerformanceGovernor.thermalHeadroom(context)
        val avg = if (liveAvgFps > 0.0) liveAvgFps else (summary?.avgFps ?: 0.0)

        // Repeated crashes → prioritise stability over speed.
        if ((summary?.crashCount ?: 0) >= CRASHES_FOR_STABILITY) {
            return Tip(Reason.CRASHES, Box86_64Preset.COMPATIBILITY, null)
        }
        // Running hot → cap FPS before the SoC throttles hard (the usual "drops after a few minutes").
        if (!headroom.isNaN() && headroom >= PerformanceGovernor.HEADROOM_WARN) {
            val base = if (currentFpsCap > 0) currentFpsCap else 60
            return Tip(Reason.THERMAL, null, PerformanceGovernor.suggestedCap(base, headroom))
        }
        // Consistently low FPS → push the aggressive Box64 profile.
        if (avg in 0.1..LOW_FPS) {
            return Tip(Reason.LOW_FPS, Box86_64Preset.MAX_PERFORMANCE, null)
        }
        return Tip(Reason.HEALTHY, null, null)
    }
}
