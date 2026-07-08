package app.gamenative.utils

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.PowerManager
import timber.log.Timber

/**
 * Thin, fully-guarded wrapper around Android's Dynamic Performance Framework (ADPF).
 *
 * Two independent capabilities, both no-ops on unsupported devices/OS levels:
 *
 *  1. Thermal-aware FPS guidance: [thermalHeadroom] reads PowerManager.getThermalHeadroom
 *     (API 30+) and [suggestedCap] turns that into a transient FPS cap so the game backs off
 *     *before* the SoC throttles hard (which is what produces the sudden frame drops after a
 *     few minutes of play). The decision logic is pure and unit-tested.
 *
 *  2. Performance hint session: [createSession] wraps PerformanceHintManager (API 31+) so the
 *     OS can raise clocks for the game's hot threads when frames run long and relax them when
 *     they finish early. Optional; only used when thread ids are supplied.
 *
 * Every platform call is wrapped so a device with a broken/absent implementation can never
 * crash or destabilize the caller — the worst case is "governor does nothing".
 */
object PerformanceGovernor {

    /** getThermalHeadroom returns ~1.0 when throttling is imminent/occurring. */
    const val HEADROOM_WARN = 0.85f
    const val HEADROOM_CRITICAL = 0.95f
    const val MIN_CAP = 30

    /**
     * Reads the thermal headroom forecast [forecastSeconds] into the future.
     * Returns [Float.NaN] when unavailable (old OS, unsupported device, or error), which
     * callers must treat as "no thermal signal — do not change anything".
     */
    fun thermalHeadroom(context: Context, forecastSeconds: Int = 10): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Float.NaN
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return Float.NaN
            val value = pm.getThermalHeadroom(forecastSeconds)
            // The API returns NaN when it has no forecast yet; propagate it unchanged.
            value
        } catch (e: Exception) {
            Timber.d(e, "PerformanceGovernor: getThermalHeadroom unavailable")
            Float.NaN
        }
    }

    /**
     * Pure decision: given the user's [baseCap] (0 = uncapped) and a thermal [headroom],
     * returns the cap to apply right now.
     *
     * - No thermal signal (NaN) or a cap already at/under the floor → unchanged.
     * - Comfortable temps (< [HEADROOM_WARN]) → unchanged.
     * - Warm ([HEADROOM_WARN]..[HEADROOM_CRITICAL]) → 80% of base.
     * - Hot (>= [HEADROOM_CRITICAL]) → 60% of base.
     * Never returns below [MIN_CAP] (unless base is uncapped, which stays 0).
     */
    fun suggestedCap(baseCap: Int, headroom: Float): Int {
        if (baseCap <= 0) return 0
        if (headroom.isNaN() || headroom < HEADROOM_WARN) return baseCap
        val factor = if (headroom >= HEADROOM_CRITICAL) 0.6f else 0.8f
        val scaled = (baseCap * factor).toInt()
        return scaled.coerceAtLeast(MIN_CAP).coerceAtMost(baseCap)
    }

    /**
     * Creates a performance hint session for the given [threadIds] with a target frame budget,
     * or null if unsupported. Callers report actual frame durations via [Session.reportActual].
     */
    fun createSession(context: Context, threadIds: IntArray, targetFrameNanos: Long): Session? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || threadIds.isEmpty() || targetFrameNanos <= 0) return null
        return try {
            val phm = context.getSystemService(PerformanceHintManager::class.java) ?: return null
            val session = phm.createHintSession(threadIds, targetFrameNanos) ?: return null
            Session(session, targetFrameNanos)
        } catch (e: Exception) {
            Timber.d(e, "PerformanceGovernor: createHintSession unavailable")
            null
        }
    }

    /** Wraps a PerformanceHintManager.Session so all calls are guarded. */
    class Session internal constructor(
        private val delegate: PerformanceHintManager.Session,
        private var targetNanos: Long,
    ) {
        fun reportActual(actualFrameNanos: Long) {
            if (actualFrameNanos <= 0) return
            try {
                delegate.reportActualWorkDuration(actualFrameNanos)
            } catch (_: Exception) {
            }
        }

        fun updateTarget(newTargetNanos: Long) {
            if (newTargetNanos <= 0 || newTargetNanos == targetNanos) return
            try {
                delegate.updateTargetWorkDuration(newTargetNanos)
                targetNanos = newTargetNanos
            } catch (_: Exception) {
            }
        }

        fun close() {
            try {
                delegate.close()
            } catch (_: Exception) {
            }
        }
    }
}
