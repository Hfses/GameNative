package app.gamenative.utils

import android.app.Activity
import android.app.GameManager
import android.app.GameState
import android.os.Build
import timber.log.Timber

/**
 * Tells Android a game is running so the SoC is driven for a game, not an app.
 * Adapted from DroidDeck's session/PerfMode.kt (GPL-3.0, github.com/Droid-Deck/DroidDeck):
 *  - the panel's fastest display mode at the current resolution (120 Hz phones stay at 120 Hz
 *    even when the game doesn't vote for it);
 *  - GameManager game state "in gameplay" (Android 13+), so OEM frameworks keep their boost.
 */
object GameSessionPerf {

    /** Previous display mode id, to restore on exit (0 = untouched). */
    data class Snapshot(val previousModeId: Int)

    fun apply(activity: Activity): Snapshot {
        var previous = 0
        try {
            val display = if (Build.VERSION.SDK_INT >= 30) activity.display
            else @Suppress("DEPRECATION") activity.windowManager.defaultDisplay
            if (display != null) {
                val cur = display.mode
                val best = display.supportedModes
                    .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                    .maxByOrNull { it.refreshRate }
                if (best != null && best.modeId != cur.modeId && best.refreshRate > cur.refreshRate + 0.5f) {
                    previous = activity.window.attributes.preferredDisplayModeId
                    activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = best.modeId }
                    Timber.i("GameSessionPerf: display ${cur.refreshRate.toInt()} -> ${best.refreshRate.toInt()} Hz")
                }
            }
        } catch (t: Throwable) {
            Timber.w(t, "GameSessionPerf: display mode")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                activity.getSystemService(GameManager::class.java)
                    ?.setGameState(GameState(false, GameState.MODE_GAMEPLAY_INTERRUPTIBLE))
            } catch (t: Throwable) {
                Timber.w(t, "GameSessionPerf: game state")
            }
        }
        return Snapshot(previous)
    }

    fun restore(activity: Activity, snapshot: Snapshot?) {
        try {
            activity.window.attributes = activity.window.attributes.apply {
                preferredDisplayModeId = snapshot?.previousModeId ?: 0
            }
        } catch (_: Throwable) {
        }
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                activity.getSystemService(GameManager::class.java)
                    ?.setGameState(GameState(false, GameState.MODE_NONE))
            } catch (_: Throwable) {
            }
        }
    }
}
