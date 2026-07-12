package app.gamenative.ui.screen.xserver

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import timber.log.Timber

/**
 * Session-scoped side effects that keep a running game healthy, extracted out of the
 * [XServerScreen] composable on purpose: bundling these DisposableEffects here keeps the huge
 * XServerScreen function small enough for the on-device ART bytecode verifier (a too-large
 * composable triggers a VerifyError at class load, and the whole app closes before any UI shows).
 *
 * 1. A partial wakelock scoped strictly to the session, ported from Winlator-Ludashi's keep-alive
 *    service. keepScreenOn stops the screen sleeping on its own, but if the user locks the screen
 *    or switches away, Android suspends the CPU and the guest (shader compiles, LAN room hosting,
 *    in-game downloads) freezes or gets killed. The wakelock keeps the guest running; released on
 *    dispose.
 * 2. Requests the panel's highest refresh-rate mode while a game is on screen. Many devices pin
 *    unknown apps to 60 Hz even on 90/120 Hz panels; without this the FPS limiter targets a rate
 *    the display was never allowed to reach. The preference is cleared on dispose.
 */
@Composable
fun GameSessionEffects(appId: String, activity: Activity?) {
    val context = LocalContext.current

    DisposableEffect(appId) {
        val wakeLock = runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "GameNative:GameSession").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
        onDispose {
            runCatching { wakeLock?.release() }
        }
    }

    DisposableEffect(activity) {
        val window = activity?.window ?: return@DisposableEffect onDispose { }
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay
        }
        val bestMode = display?.supportedModes?.maxByOrNull { it.refreshRate }
        val previousModeId = window.attributes.preferredDisplayModeId
        if (bestMode != null) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = bestMode.modeId }
            Timber.i("Requested display mode ${bestMode.modeId} (${bestMode.refreshRate} Hz) for the game session")
        }
        onDispose {
            runCatching {
                window.attributes = window.attributes.apply { preferredDisplayModeId = previousModeId }
            }
        }
    }
}
