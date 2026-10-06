package app.gamenative.utils

import android.app.Activity
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Picture-in-picture for a running game (idea from DroidDeck's SessionPipController, GPL-3.0):
 * pressing Home shrinks the game into a floating window that keeps running instead of being
 * suspended, so you can answer a message and come back.
 */
object GamePip {
    private val _enabled = MutableStateFlow(false)
    /** User toggle (Quick Menu): go to PiP on Home instead of pausing the game. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _inPip = MutableStateFlow(false)
    val inPip: StateFlow<Boolean> = _inPip.asStateFlow()

    fun setEnabled(on: Boolean) { _enabled.value = on }

    fun supported(activity: Activity): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            activity.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** Called from onUserLeaveHint while a game is running. */
    fun tryEnter(activity: Activity): Boolean {
        if (!_enabled.value || !supported(activity)) return false
        return try {
            val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            // Mark PiP before onPause runs, so the game and renderer aren't suspended.
            activity.enterPictureInPictureMode(params).also { if (it) _inPip.value = true }
        } catch (t: Throwable) {
            Timber.w(t, "GamePip: enter failed")
            false
        }
    }

    fun onModeChanged(inPip: Boolean) { _inPip.value = inPip }
}
