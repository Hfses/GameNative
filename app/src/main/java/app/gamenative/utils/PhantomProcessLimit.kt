package app.gamenative.utils

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Android 12+ "phantom process" monitor kills an app's child processes (Wine, Box64, wineserver,
 * PulseAudio…) when there are too many or they use CPU in the background. Games then vanish with
 * no log. Detection adapted from DroidDeck (GPL-3.0, github.com/Droid-Deck/DroidDeck), core/PhantomProcessLimit.kt.
 */
object PhantomProcessLimit {

    enum class Status { NOT_APPLICABLE, DISABLED, ENABLED, UNSET, UNREADABLE }

    private const val SETTING = "settings_enable_monitor_phantom_procs"
    private const val OVERRIDE_PROPERTY = "persist.sys.fflag.override.$SETTING"
    private const val MAX_PHANTOM = "2147483647"

    /** Android 12 (API 31) has no feature flag, only the device_config limit (unreadable to apps). */
    private fun usesDeviceConfig(sdk: Int) = sdk == Build.VERSION_CODES.S

    fun read(context: Context, sdk: Int = Build.VERSION.SDK_INT): Status {
        if (sdk < Build.VERSION_CODES.S) return Status.NOT_APPLICABLE
        if (usesDeviceConfig(sdk)) return Status.UNREADABLE
        val global = try {
            Settings.Global.getString(context.contentResolver, SETTING)
        } catch (_: Exception) {
            return Status.UNREADABLE
        }
        // Same precedence as FeatureFlagUtils.isEnabled: global setting, then the override
        // property (which is all the Developer-options toggle writes).
        val value = global?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: systemProperty(OVERRIDE_PROPERTY)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return when (value) {
            "false", "0" -> Status.DISABLED
            null -> Status.UNSET // ROM default: the monitor is on
            else -> Status.ENABLED
        }
    }

    /** Only warn when we know the monitor is (or defaults to) on; Android 12 can't be checked. */
    fun shouldWarn(status: Status) = status == Status.ENABLED || status == Status.UNSET

    /** Android 14+ exposes "Disable child process restrictions" in Developer options. */
    fun hasDeveloperToggle(sdk: Int = Build.VERSION.SDK_INT) = sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** Shell commands that turn the limit off (run over ADB). */
    fun disableShellCommands(sdk: Int = Build.VERSION.SDK_INT): List<String> =
        if (usesDeviceConfig(sdk)) listOf(
            "device_config set_sync_disabled_for_tests persistent",
            "device_config put activity_manager max_phantom_processes $MAX_PHANTOM",
        ) else listOf("settings put global $SETTING false")

    fun verifyCommand(sdk: Int = Build.VERSION.SDK_INT): String =
        if (usesDeviceConfig(sdk)) "device_config get activity_manager max_phantom_processes"
        else "settings get global $SETTING"

    fun verifiedDisabled(output: String, sdk: Int = Build.VERSION.SDK_INT): Boolean =
        if (usesDeviceConfig(sdk)) output.trim() == MAX_PHANTOM else output.trim() == "false"

    fun adbCommand(sdk: Int = Build.VERSION.SDK_INT): String =
        if (usesDeviceConfig(sdk)) {
            "adb shell device_config set_sync_disabled_for_tests persistent && " +
                "adb shell device_config put activity_manager max_phantom_processes $MAX_PHANTOM"
        } else {
            "adb shell settings put global $SETTING false"
        }

    private fun systemProperty(name: String): String? = try {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, name) as? String
    } catch (_: Exception) {
        null
    }
}
