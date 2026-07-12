package app.gamenative.ui.screen.xserver

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gamenative.R
import app.gamenative.ui.component.QuickMenu
import app.gamenative.ui.data.PerformanceHudConfig
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.TelemetryCollector
import app.gamenative.utils.TipsAdvisor
import com.winlator.box86_64.Box86_64Preset
import com.winlator.container.Container
import com.winlator.renderer.GLRenderer
import com.winlator.renderer.VulkanRenderer
import com.winlator.winhandler.ProcessInfo

/**
 * Thin wrapper around [QuickMenu] that owns the "Perfil Máximo" (Box64 MAX_PERFORMANCE) toggle and
 * the "Dicas" (Tips) tab state, then forwards everything to the real menu.
 *
 * Why a wrapper: this extra state (6 remember slots + a LaunchedEffect + a when-expression + two
 * fat lambdas) used to live inline in the [XServerScreen] composable. That pushed the single
 * XServerScreen function past the on-device ART bytecode verifier's limit, producing a VerifyError
 * at class load — the whole app closed on game launch with a clean log. Moving it into its own
 * composable method keeps XServerScreen small while preserving the features. XServerScreen calls
 * this with the same arguments it already passed to QuickMenu, plus [appId].
 */
@Composable
fun GameQuickMenu(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onItemSelected: (Int) -> Boolean,
    appId: String,
    renderer: VulkanRenderer? = null,
    glRenderer: GLRenderer? = null,
    container: Container? = null,
    wineProcesses: List<ProcessInfo> = emptyList(),
    isWineProcessesLoading: Boolean = false,
    onToolsVisibilityChanged: (Boolean) -> Unit = {},
    onEndWineProcess: (ProcessInfo) -> Unit = {},
    isPerformanceHudEnabled: Boolean = false,
    performanceHudConfig: PerformanceHudConfig = PerformanceHudConfig(),
    fpsLimiterEnabled: Boolean = true,
    fpsLimiterTarget: Int = 60,
    fpsLimiterMax: Int = 60,
    onPerformanceHudConfigChanged: (PerformanceHudConfig) -> Unit = {},
    onFpsLimiterEnabledChanged: (Boolean) -> Unit = {},
    onFpsLimiterChanged: (Int) -> Unit = {},
    hasPhysicalController: Boolean = false,
    showLanChatToggle: Boolean = false,
    isTouchscreenModeActive: Boolean = false,
    onTouchGestureSettingsClick: () -> Unit = {},
    activeToggleIds: Set<Int> = emptySet(),
    isLsfgAvailable: Boolean = false,
    lsfgMultiplier: Int = 2,
    lsfgFlowScale: Float = 0.80f,
    lsfgPerformanceMode: Boolean = true,
    onLsfgMultiplierChanged: (Int) -> Unit = {},
    onLsfgFlowScaleChanged: (Float) -> Unit = {},
    onLsfgPerformanceModeChanged: (Boolean) -> Unit = {},
    onAnimationComplete: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current

    // "Perfil Máximo": Box64 MAX_PERFORMANCE preset, toggleable from the sidebar. Dynarec env vars
    // are read at guest launch, so changes apply on the NEXT game start.
    var maxProfileEnabled by rememberSaveable(container?.id) {
        mutableStateOf(container?.box64Preset == Box86_64Preset.MAX_PERFORMANCE)
    }
    // The preset to fall back to when Perfil Máximo is turned OFF, so it restores the user's own
    // choice instead of clobbering it with PERFORMANCE.
    var preMaxProfilePreset by rememberSaveable(container?.id) {
        mutableStateOf(
            if (container?.box64Preset == Box86_64Preset.MAX_PERFORMANCE) {
                Box86_64Preset.PERFORMANCE
            } else {
                container?.box64Preset ?: Box86_64Preset.PERFORMANCE
            },
        )
    }

    // Dicas: the game is paused while the overlay is up, so snapshot the recent FPS and compute the
    // recommendation when the menu opens rather than polling live.
    var dicasRecentFps by remember { mutableStateOf<List<Float>>(emptyList()) }
    var dicasAvgFps by remember { mutableStateOf(0.0) }
    var dicasMinFps by remember { mutableStateOf(0.0) }
    var dicasTip by remember { mutableStateOf<TipsAdvisor.Tip?>(null) }
    LaunchedEffect(isVisible) {
        if (isVisible) {
            dicasRecentFps = TelemetryCollector.recentFps()
            dicasAvgFps = TelemetryCollector.liveAverageFps()
            dicasMinFps = TelemetryCollector.liveMinFps()
            val cap = if (fpsLimiterEnabled) fpsLimiterTarget else 0
            dicasTip = TipsAdvisor.advise(context, appId, dicasAvgFps, cap)
        }
    }
    val dicasTipText = when (dicasTip?.reason) {
        TipsAdvisor.Reason.LOW_FPS -> stringResource(R.string.dicas_tip_low_fps)
        TipsAdvisor.Reason.THERMAL -> stringResource(R.string.dicas_tip_thermal, dicasTip?.fpsCap ?: 0)
        TipsAdvisor.Reason.CRASHES -> stringResource(R.string.dicas_tip_crashes)
        TipsAdvisor.Reason.HEALTHY -> stringResource(R.string.dicas_tip_healthy)
        null -> ""
    }
    val dicasCanApply = dicasTip?.let { it.box64Preset != null || it.fpsCap != null } == true

    QuickMenu(
        isVisible = isVisible,
        onDismiss = onDismiss,
        onItemSelected = onItemSelected,
        renderer = renderer,
        glRenderer = glRenderer,
        container = container,
        wineProcesses = wineProcesses,
        isWineProcessesLoading = isWineProcessesLoading,
        onToolsVisibilityChanged = onToolsVisibilityChanged,
        onEndWineProcess = onEndWineProcess,
        isPerformanceHudEnabled = isPerformanceHudEnabled,
        performanceHudConfig = performanceHudConfig,
        fpsLimiterEnabled = fpsLimiterEnabled,
        fpsLimiterTarget = fpsLimiterTarget,
        fpsLimiterMax = fpsLimiterMax,
        onPerformanceHudConfigChanged = onPerformanceHudConfigChanged,
        onFpsLimiterEnabledChanged = onFpsLimiterEnabledChanged,
        onFpsLimiterChanged = onFpsLimiterChanged,
        hasPhysicalController = hasPhysicalController,
        showLanChatToggle = showLanChatToggle,
        isTouchscreenModeActive = isTouchscreenModeActive,
        onTouchGestureSettingsClick = onTouchGestureSettingsClick,
        activeToggleIds = activeToggleIds,
        // Show the frame-gen / LSFG tab whenever LSFG is enabled OR the GL display renderer (which
        // hosts our own frame-gen engine) is active.
        isLsfgAvailable = isLsfgAvailable || (glRenderer != null),
        lsfgMultiplier = lsfgMultiplier,
        lsfgFlowScale = lsfgFlowScale,
        lsfgPerformanceMode = lsfgPerformanceMode,
        onLsfgMultiplierChanged = onLsfgMultiplierChanged,
        onLsfgFlowScaleChanged = onLsfgFlowScaleChanged,
        onLsfgPerformanceModeChanged = onLsfgPerformanceModeChanged,
        maxProfileEnabled = maxProfileEnabled,
        onMaxProfileChanged = { enabled ->
            maxProfileEnabled = enabled
            if (container != null) {
                if (enabled) {
                    if (container.box64Preset != Box86_64Preset.MAX_PERFORMANCE) {
                        preMaxProfilePreset = container.box64Preset
                    }
                    container.box64Preset = Box86_64Preset.MAX_PERFORMANCE
                } else {
                    container.box64Preset = preMaxProfilePreset
                }
                container.saveData()
                SnackbarManager.show(context.getString(R.string.quickmenu_max_profile_applied))
            }
        },
        dicasRecentFps = dicasRecentFps,
        dicasAvgFps = dicasAvgFps,
        dicasMinFps = dicasMinFps,
        dicasTipText = dicasTipText,
        dicasCanApply = dicasCanApply,
        onApplyDicasTip = {
            val tip = dicasTip
            if (tip != null && container != null) {
                tip.box64Preset?.let { preset ->
                    container.box64Preset = preset
                    maxProfileEnabled = preset == Box86_64Preset.MAX_PERFORMANCE
                    if (!maxProfileEnabled) preMaxProfilePreset = preset
                }
                tip.fpsCap?.let { cap ->
                    if (cap > 0) {
                        onFpsLimiterEnabledChanged(true)
                        onFpsLimiterChanged(cap)
                    }
                }
                container.saveData()
                SnackbarManager.show(context.getString(R.string.dicas_applied))
            }
        },
        onAnimationComplete = onAnimationComplete,
    )
}
