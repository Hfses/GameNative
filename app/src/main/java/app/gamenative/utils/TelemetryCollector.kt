package app.gamenative.utils

import android.content.Context
import android.os.SystemClock
import app.gamenative.R
import app.gamenative.ui.util.SnackbarManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Local, automatic and silent per-game telemetry. Samples FPS during a game
 * session and stores a short session history per game, entirely on-device
 * (files/telemetry/<appId>.json — nothing is ever uploaded). A ".running"
 * marker detects sessions that died without a clean exit (crash suspected).
 * Once enough sessions exist, a one-line suggestion is surfaced at launch.
 */
object TelemetryCollector {

    private const val SAMPLE_INTERVAL_MS = 2_000L
    private const val MAX_SESSIONS_KEPT = 20
    private const val MIN_SESSIONS_FOR_SUGGESTION = 3
    private const val LOW_FPS_THRESHOLD = 25.0
    private const val CRASHES_FOR_SUGGESTION = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var samplerJob: Job? = null

    private var currentAppId: String? = null
    private var sessionStartMs: Long = 0
    private var fpsSum = 0.0
    private var fpsSampleCount = 0
    private var fpsMin = Double.MAX_VALUE

    @Synchronized
    fun start(context: Context, appId: String, fpsProvider: () -> Float) {
        if (currentAppId != null) return // already running
        currentAppId = appId
        sessionStartMs = SystemClock.elapsedRealtime()
        fpsSum = 0.0
        fpsSampleCount = 0
        fpsMin = Double.MAX_VALUE

        val appContext = context.applicationContext
        scope.launch {
            try {
                // A leftover marker means the previous session never exited cleanly.
                val marker = markerFile(appContext, appId)
                if (marker.exists()) {
                    recordCrash(appContext, appId)
                    Timber.tag("Telemetry").w("Previous session of $appId ended without clean exit (crash suspected)")
                }
                marker.parentFile?.mkdirs()
                marker.writeText(System.currentTimeMillis().toString())

                maybeSuggest(appContext, appId)
            } catch (e: Exception) {
                Timber.tag("Telemetry").w(e, "Failed to initialize telemetry for $appId")
            }
        }

        samplerJob = scope.launch {
            while (isActive) {
                delay(SAMPLE_INTERVAL_MS)
                val fps = try {
                    fpsProvider().toDouble()
                } catch (e: Exception) {
                    0.0
                }
                if (fps > 0.5) {
                    synchronized(this@TelemetryCollector) {
                        fpsSum += fps
                        fpsSampleCount++
                        if (fps < fpsMin) fpsMin = fps
                    }
                }
            }
        }
    }

    @Synchronized
    fun stop(context: Context) {
        val appId = currentAppId ?: return
        currentAppId = null
        samplerJob?.cancel()
        samplerJob = null

        val durationSec = (SystemClock.elapsedRealtime() - sessionStartMs) / 1000
        val avgFps = if (fpsSampleCount > 0) fpsSum / fpsSampleCount else 0.0
        val minFps = if (fpsSampleCount > 0) fpsMin else 0.0
        val samples = fpsSampleCount

        val appContext = context.applicationContext
        scope.launch {
            try {
                markerFile(appContext, appId).delete()

                // Sessions shorter than 30s carry no useful signal.
                if (durationSec < 30) return@launch

                val stats = readStats(appContext, appId)
                val sessions = stats.optJSONArray("sessions") ?: JSONArray()
                sessions.put(
                    JSONObject()
                        .put("timestamp", System.currentTimeMillis())
                        .put("durationSec", durationSec)
                        .put("avgFps", (avgFps * 10).toInt() / 10.0)
                        .put("minFps", (minFps * 10).toInt() / 10.0)
                        .put("samples", samples),
                )
                // Keep only the most recent sessions.
                while (sessions.length() > MAX_SESSIONS_KEPT) sessions.remove(0)
                stats.put("sessions", sessions)
                writeStats(appContext, appId, stats)
                Timber.tag("Telemetry").i(
                    "Session saved for $appId: ${durationSec}s, avg %.1f fps (%d samples)".format(avgFps, samples),
                )
            } catch (e: Exception) {
                Timber.tag("Telemetry").w(e, "Failed to save telemetry session for $appId")
            }
        }
    }

    private fun maybeSuggest(context: Context, appId: String) {
        try {
            val stats = readStats(context, appId)
            val crashCount = stats.optInt("crashCount", 0)
            if (crashCount >= CRASHES_FOR_SUGGESTION && !stats.optBoolean("crashSuggested", false)) {
                stats.put("crashSuggested", true)
                writeStats(context, appId, stats)
                SnackbarManager.show(context.getString(R.string.telemetry_crash_suggestion, crashCount))
                return
            }

            val sessions = stats.optJSONArray("sessions") ?: return
            if (sessions.length() < MIN_SESSIONS_FOR_SUGGESTION) return
            if (stats.optBoolean("lowFpsSuggested", false)) return

            var sum = 0.0
            for (i in 0 until sessions.length()) {
                sum += sessions.getJSONObject(i).optDouble("avgFps", 0.0)
            }
            val overallAvg = sum / sessions.length()
            if (overallAvg > 0 && overallAvg < LOW_FPS_THRESHOLD) {
                stats.put("lowFpsSuggested", true)
                writeStats(context, appId, stats)
                SnackbarManager.show(context.getString(R.string.telemetry_low_fps_suggestion, overallAvg.toInt()))
            }
        } catch (e: Exception) {
            Timber.tag("Telemetry").w(e, "Failed to evaluate suggestions for $appId")
        }
    }

    private fun recordCrash(context: Context, appId: String) {
        try {
            val stats = readStats(context, appId)
            stats.put("crashCount", stats.optInt("crashCount", 0) + 1)
            // A new crash re-arms the crash suggestion.
            stats.put("crashSuggested", false)
            writeStats(context, appId, stats)
        } catch (e: Exception) {
            Timber.tag("Telemetry").w(e, "Failed to record crash for $appId")
        }
    }

    data class Summary(
        val sessionCount: Int,
        val avgFps: Double,
        val crashCount: Int,
        val totalMinutes: Long,
    )

    /** Aggregated on-device stats for a game, or null when nothing was recorded yet. */
    fun summary(context: Context, appId: String): Summary? {
        return try {
            val stats = readStats(context.applicationContext, appId)
            val sessions = stats.optJSONArray("sessions") ?: JSONArray()
            val crashCount = stats.optInt("crashCount", 0)
            if (sessions.length() == 0 && crashCount == 0) return null
            var fpsSum = 0.0
            var fpsCount = 0
            var seconds = 0L
            for (i in 0 until sessions.length()) {
                val s = sessions.getJSONObject(i)
                val avg = s.optDouble("avgFps", 0.0)
                if (avg > 0) {
                    fpsSum += avg
                    fpsCount++
                }
                seconds += s.optLong("durationSec", 0)
            }
            Summary(
                sessionCount = sessions.length(),
                avgFps = if (fpsCount > 0) fpsSum / fpsCount else 0.0,
                crashCount = crashCount,
                totalMinutes = seconds / 60,
            )
        } catch (e: Exception) {
            Timber.tag("Telemetry").w(e, "Failed to summarize telemetry for $appId")
            null
        }
    }

    private fun telemetryDir(context: Context): File = File(context.filesDir, "telemetry")

    private fun statsFile(context: Context, appId: String): File =
        File(telemetryDir(context), sanitize(appId) + ".json")

    private fun markerFile(context: Context, appId: String): File =
        File(telemetryDir(context), sanitize(appId) + ".running")

    private fun sanitize(appId: String): String = appId.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun readStats(context: Context, appId: String): JSONObject {
        val file = statsFile(context, appId)
        return if (file.exists()) {
            try {
                JSONObject(file.readText())
            } catch (e: Exception) {
                JSONObject()
            }
        } else {
            JSONObject()
        }
    }

    private fun writeStats(context: Context, appId: String, stats: JSONObject) {
        val file = statsFile(context, appId)
        file.parentFile?.mkdirs()
        file.writeText(stats.toString())
    }
}
