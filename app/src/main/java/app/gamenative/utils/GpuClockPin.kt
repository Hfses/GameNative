package app.gamenative.utils

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Holds the Adreno GPU at its top clock during a game (opt-in: costs battery and heat).
 * Ported from DroidDeck's session/GpuClockPin.kt (GPL-3.0). The KGSL property is device-wide and
 * survives an app kill, so a marker file lets the next app start undo a pin we left behind.
 *
 * Needs native/gpu_turbo.c in libwinlator.so; with an older prebuilt lib [available] is false
 * and everything is a no-op.
 */
object GpuClockPin {
    private const val MARKER = "gpu-clock-pinned"

    @JvmStatic private external fun nativeSetGpuTurbo(on: Boolean): Boolean

    val available: Boolean by lazy {
        File("/dev/kgsl-3d0").exists() && runCatching {
            System.loadLibrary("winlator")
            nativeSetGpuTurbo(false) // probe: harmless "governed" write
        }.getOrDefault(false)
    }

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Volatile private var pinned = false

    @Synchronized
    fun setEnabled(context: Context, on: Boolean) {
        _enabled.value = on
        if (on) start(context) else stop(context)
    }

    @Synchronized
    fun start(context: Context) {
        if (!_enabled.value || !available || pinned) return
        File(context.filesDir, MARKER).createNewFile()
        pinned = runCatching { nativeSetGpuTurbo(true) }.getOrDefault(false)
        Timber.i("GpuClockPin: pinned=$pinned")
    }

    @Synchronized
    fun stop(context: Context) {
        if (pinned) clear(context)
    }

    /** Call at app start: undo a pin left by a killed process. */
    @Synchronized
    fun clearLeftover(context: Context) {
        if (File(context.filesDir, MARKER).exists()) clear(context)
    }

    private fun clear(context: Context) {
        if (available) runCatching { nativeSetGpuTurbo(false) }
        pinned = false
        File(context.filesDir, MARKER).delete()
    }
}
