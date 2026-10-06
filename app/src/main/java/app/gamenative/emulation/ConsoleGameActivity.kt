package app.gamenative.emulation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.ShaderConfig
import java.io.File
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Runs one console game in-app: a libretro core inside LibretroDroid's GLRetroView, with an
 * on-screen gamepad, physical controller passthrough, save states and battery saves (SRAM).
 */
class ConsoleGameActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_SYSTEM = "system"
        private const val EXTRA_PATH = "path"
        private const val STATE_SLOTS = 4

        fun intent(context: Context, game: ConsoleLibrary.ConsoleGame): Intent =
            Intent(context, ConsoleGameActivity::class.java)
                .putExtra(EXTRA_SYSTEM, game.systemId)
                .putExtra(EXTRA_PATH, game.path)
    }

    private var retroView: GLRetroView? = null
    private lateinit var system: ConsoleSystem
    private lateinit var gameFile: File

    private val sramFile: File get() = File(CoreManager.savesDir(this, system), gameFile.nameWithoutExtension + ".srm")
    private fun stateFile(slot: Int) = File(CoreManager.statesDir(this, system), "${gameFile.nameWithoutExtension}.state$slot")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sys = ConsoleSystem.fromId(intent.getStringExtra(EXTRA_SYSTEM))
        val path = intent.getStringExtra(EXTRA_PATH)
        val core = sys?.let { CoreManager.coreFile(this, it) }
        if (sys == null || path == null || core == null || !core.isFile || !File(path).isFile) {
            Toast.makeText(this, "Jogo ou core não encontrado", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        system = sys
        gameFile = File(path)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val data = GLRetroViewData(this).apply {
            coreFilePath = core.absolutePath
            gameFilePath = gameFile.absolutePath
            systemDirectory = CoreManager.systemDir(this@ConsoleGameActivity).absolutePath
            savesDirectory = CoreManager.savesDir(this@ConsoleGameActivity, system).absolutePath
            saveRAMState = sramFile.takeIf { it.isFile }?.readBytes()
            shader = ShaderConfig.Default
            rumbleEventsEnabled = true
            preferLowLatencyAudio = true
        }
        val view = GLRetroView(this, data)
        retroView = view
        lifecycle.addObserver(view)

        lifecycleScope.launch {
            view.getGLRetroErrors().collect { code ->
                Timber.tag("ConsoleGame").e("libretro error $code for ${system.coreName}")
                Toast.makeText(
                    this@ConsoleGameActivity,
                    "Erro ao iniciar ${system.displayName} (código $code). Verifique o BIOS e o formato do jogo.",
                    Toast.LENGTH_LONG,
                ).show()
                finish()
            }
        }

        val root = FrameLayout(this)
        root.setBackgroundColor(android.graphics.Color.BLACK)
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(
            ComposeView(this).apply {
                setContent {
                    MaterialTheme(colorScheme = darkColorScheme()) { Overlay() }
                }
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        setContentView(root)
    }

    override fun onPause() {
        saveSram()
        super.onPause()
    }

    private fun saveSram() {
        val view = retroView ?: return
        runCatching {
            val sram = view.serializeSRAM()
            if (sram.isNotEmpty()) sramFile.writeBytes(sram)
        }.onFailure { Timber.tag("ConsoleGame").w(it, "SRAM save failed") }
    }

    private fun saveState(slot: Int) {
        val view = retroView ?: return
        runCatching { stateFile(slot).writeBytes(view.serializeState()) }
            .onSuccess { toast("Estado salvo no slot $slot") }
            .onFailure { toast("Este core não suporta salvar estado") }
    }

    private fun loadState(slot: Int) {
        val view = retroView ?: return
        val f = stateFile(slot)
        if (!f.isFile) return toast("Slot $slot vazio")
        val ok = runCatching { view.unserializeState(f.readBytes()) }.getOrDefault(false)
        toast(if (ok) "Estado do slot $slot carregado" else "Falha ao carregar o estado")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // --- Physical controllers / keyboards ---

    private fun isGameInput(source: Int) =
        source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD

    private fun portOf(event: android.view.InputEvent): Int {
        // Distinct controllers map to distinct players (local multiplayer on one device).
        val ids = InputDevice.getDeviceIds().filter { id ->
            InputDevice.getDevice(id)?.let { isGameInput(it.sources) && !it.isVirtual } == true
        }.sorted()
        return ids.indexOf(event.deviceId).coerceAtLeast(0).coerceAtMost(3)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val view = retroView
        if (view != null && isGameInput(event.source) && event.keyCode != KeyEvent.KEYCODE_BACK) {
            view.sendKeyEvent(event.action, event.keyCode, portOf(event))
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val view = retroView
        if (view != null && isGameInput(event.source) && event.action == MotionEvent.ACTION_MOVE) {
            val port = portOf(event)
            view.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_HAT_Y), port)
            view.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y), port)
            view.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, event.getAxisValue(MotionEvent.AXIS_Z), event.getAxisValue(MotionEvent.AXIS_RZ), port)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    // --- On-screen gamepad + menu ---

    @Composable
    private fun Overlay() {
        var showMenu by remember { mutableStateOf(false) }
        var showPad by remember { mutableStateOf(InputDevice.getDeviceIds().none { id -> InputDevice.getDevice(id)?.let { isGameInput(it.sources) && !it.isVirtual } == true }) }
        BackHandler { showMenu = true }

        Box(Modifier.fillMaxSize()) {
            if (showPad) {
                Column(Modifier.align(Alignment.CenterStart).padding(start = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    PadButton("L", KeyEvent.KEYCODE_BUTTON_L1, RoundedCornerShape(8.dp), 64.dp)
                    Box(Modifier.size(150.dp)) {
                        PadButton("▲", KeyEvent.KEYCODE_DPAD_UP, RoundedCornerShape(8.dp), 50.dp, Modifier.align(Alignment.TopCenter))
                        PadButton("▼", KeyEvent.KEYCODE_DPAD_DOWN, RoundedCornerShape(8.dp), 50.dp, Modifier.align(Alignment.BottomCenter))
                        PadButton("◀", KeyEvent.KEYCODE_DPAD_LEFT, RoundedCornerShape(8.dp), 50.dp, Modifier.align(Alignment.CenterStart))
                        PadButton("▶", KeyEvent.KEYCODE_DPAD_RIGHT, RoundedCornerShape(8.dp), 50.dp, Modifier.align(Alignment.CenterEnd))
                    }
                }
                Column(Modifier.align(Alignment.CenterEnd).padding(end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    PadButton("R", KeyEvent.KEYCODE_BUTTON_R1, RoundedCornerShape(8.dp), 64.dp)
                    Box(Modifier.size(150.dp)) {
                        PadButton("X", KeyEvent.KEYCODE_BUTTON_X, CircleShape, 52.dp, Modifier.align(Alignment.TopCenter))
                        PadButton("B", KeyEvent.KEYCODE_BUTTON_B, CircleShape, 52.dp, Modifier.align(Alignment.BottomCenter))
                        PadButton("Y", KeyEvent.KEYCODE_BUTTON_Y, CircleShape, 52.dp, Modifier.align(Alignment.CenterStart))
                        PadButton("A", KeyEvent.KEYCODE_BUTTON_A, CircleShape, 52.dp, Modifier.align(Alignment.CenterEnd))
                    }
                }
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    PadButton("L2", KeyEvent.KEYCODE_BUTTON_L2, RoundedCornerShape(8.dp), 48.dp)
                    PadButton("SELECT", KeyEvent.KEYCODE_BUTTON_SELECT, RoundedCornerShape(16.dp), 64.dp)
                    PadButton("START", KeyEvent.KEYCODE_BUTTON_START, RoundedCornerShape(16.dp), 64.dp)
                    PadButton("R2", KeyEvent.KEYCODE_BUTTON_R2, RoundedCornerShape(8.dp), 48.dp)
                }
            }
            Text(
                "☰",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 22.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.35f))
                    .pointerInput(Unit) { detectTapGestures { showMenu = true } }
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        if (showMenu) {
            AlertDialog(
                onDismissRequest = { showMenu = false },
                title = { Text(system.displayName) },
                text = {
                    Column {
                        for (slot in 1..STATE_SLOTS) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Slot $slot", Modifier.padding(end = 8.dp))
                                TextButton(onClick = { saveState(slot); showMenu = false }) { Text("Salvar") }
                                TextButton(onClick = { loadState(slot); showMenu = false }) { Text("Carregar") }
                            }
                        }
                        TextButton(onClick = { showPad = !showPad; showMenu = false }) {
                            Text(if (showPad) "Ocultar controle na tela" else "Mostrar controle na tela")
                        }
                        TextButton(onClick = { retroView?.reset(); showMenu = false }) { Text("Reiniciar jogo") }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { saveSram(); finish() }) { Text("Sair do jogo") }
                },
                dismissButton = { TextButton(onClick = { showMenu = false }) { Text("Voltar") } },
            )
        }
    }

    @Composable
    private fun PadButton(label: String, keyCode: Int, shape: Shape, size: Dp, modifier: Modifier = Modifier) {
        Box(
            modifier
                .size(size)
                .clip(shape)
                .background(Color.White.copy(alpha = 0.22f))
                .pointerInput(keyCode) {
                    detectTapGestures(onPress = {
                        retroView?.sendKeyEvent(KeyEvent.ACTION_DOWN, keyCode, 0)
                        tryAwaitRelease()
                        retroView?.sendKeyEvent(KeyEvent.ACTION_UP, keyCode, 0)
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = Color.White, fontSize = if (label.length > 2) 11.sp else 18.sp)
        }
    }
}
