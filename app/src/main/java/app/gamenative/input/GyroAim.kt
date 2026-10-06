package app.gamenative.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Gyro aiming: turns device rotation into right-stick deflection, added on top of the
 * physical/virtual controller's own right stick (WinHandler reads [rx]/[ry]).
 */
object GyroAim : SensorEventListener {

    private const val DEADZONE = 0.02f // rad/s

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _sensitivity = MutableStateFlow(1.0f)
    val sensitivity: StateFlow<Float> = _sensitivity.asStateFlow()

    @Volatile var rx = 0f
        private set
    @Volatile var ry = 0f
        private set

    /** Called after each gyro sample so the controller state gets re-sent to the game. */
    @Volatile var onUpdate: (() -> Unit)? = null

    private var sensorManager: SensorManager? = null
    private var windowManager: WindowManager? = null

    fun isSupported(context: Context): Boolean =
        (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
            ?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null

    fun setEnabled(context: Context, on: Boolean) {
        if (on == _enabled.value) return
        _enabled.value = on
        if (on) start(context) else stop()
    }

    fun setSensitivity(value: Float) {
        _sensitivity.value = value.coerceIn(0.2f, 4f)
    }

    /** Re-registers after the activity resumes (sensors are released on pause). */
    fun resume(context: Context) { if (_enabled.value) start(context) }
    fun pause() { unregister() }

    private fun start(context: Context) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return
        unregister()
        sensorManager = sm
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stop() {
        unregister()
        rx = 0f
        ry = 0f
        onUpdate?.invoke()
    }

    private fun unregister() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Device-frame angular velocity (rad/s). In landscape, yaw ≈ X axis, pitch ≈ Y axis.
        var yaw = event.values[0]
        var pitch = event.values[1]
        @Suppress("DEPRECATION")
        when (windowManager?.defaultDisplay?.rotation) {
            Surface.ROTATION_90 -> { /* default landscape */ }
            Surface.ROTATION_270 -> { yaw = -yaw; pitch = -pitch }
            else -> { val t = yaw; yaw = -pitch; pitch = t } // portrait
        }
        if (abs(yaw) < DEADZONE) yaw = 0f
        if (abs(pitch) < DEADZONE) pitch = 0f
        val s = _sensitivity.value
        rx = (-pitch * s).coerceIn(-1f, 1f)
        ry = (-yaw * s).coerceIn(-1f, 1f)
        onUpdate?.invoke()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Right-stick value with gyro added, clamped to the stick range. */
    @JvmStatic fun combineX(stick: Float) = if (_enabled.value) (stick + rx).coerceIn(-1f, 1f) else stick
    @JvmStatic fun combineY(stick: Float) = if (_enabled.value) (stick + ry).coerceIn(-1f, 1f) else stick
}
