package app.gamenative.linux

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.Process as AndroidProcess
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.droiddeck.launcher.wayland.CompositorHost
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.File
import java.util.TimeZone
import timber.log.Timber

/**
 * Linux mode stage 5 (milestone C): a Steam ARM session. DroidDeck's in-app Wayland compositor
 * presents onto this activity's Surface with the GPU (Turnip via adrenotools / system Vulkan);
 * the Steam ARM runtime runs under PRoot and its session script starts gamescope + Valve's arm64
 * client against that compositor. Session layout mirrors DroidDeck's SessionService (GPL-3.0).
 *
 * Not yet: audio (PulseAudio), controllers (fake evdev), on-screen pad — later milestones.
 */
class SteamSessionActivity : ComponentActivity(), SurfaceHolder.Callback {

    companion object {
        private const val GUEST_RUNTIME_DIR = "/run/droiddeck"
        private const val SESSION_SCRIPT = "/usr/local/bin/droiddeck-session"
        // The compositor's input space (DroidDeck sends touches as 1920x1080 coordinates).
        private const val INPUT_W = 1919f
        private const val INPUT_H = 1079f

        fun intent(context: Context) = Intent(context, SteamSessionActivity::class.java)
        fun logFile(context: Context) = File(context.filesDir, "linux/steam-session.log")
    }

    private var session: Process? = null
    private lateinit var surfaceView: SurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        surfaceView = SurfaceView(this).apply {
            holder.addCallback(this@SteamSessionActivity)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        setContentView(surfaceView)
        surfaceView.requestFocus()
    }

    private fun runtimeDir() = File(filesDir, "linux/steam-run").apply { mkdirs() }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val refresh = (if (android.os.Build.VERSION.SDK_INT >= 30) display?.refreshRate else null) ?: 60f
        val firstStart = CompositorHost.startOrAttach(
            holder.surface,
            runtimeDir().path,
            null, // driverPath: system Vulkan for now; an imported Turnip is a later milestone
            null,
            applicationInfo.nativeLibraryDir,
            width, height, refresh, 0,
        )
        if (firstStart || session == null) startSession(width, height, refresh)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        CompositorHost.detach(holder.surface)
    }

    private fun startSession(width: Int, height: Int, refresh: Float) {
        if (session != null) return
        val root = SteamRuntime.rootDir(this)
        val proot = File(applicationInfo.nativeLibraryDir, "libprootlinux.so")
        val cmd = ArrayList<String>()
        cmd += proot.path
        cmd += "--kill-on-exit"
        val uid = AndroidProcess.myUid()
        cmd += listOf("-i", "$uid:$uid", "-r", root.path, "-w", "/root")
        fun bind(spec: String) { cmd += "-b"; cmd += spec }
        bind("/dev"); bind("/proc"); bind("/sys")
        bind("/dev/urandom:/dev/random")
        bind("/proc/self/fd:/dev/fd")
        bind("/proc/self/fd/0:/dev/stdin")
        bind("/proc/self/fd/1:/dev/stdout")
        bind("/proc/self/fd/2:/dev/stderr")
        File(root, "etc/droiddeck/empty").takeIf { it.isDirectory }?.let { bind("${it.path}:/sys/fs/selinux") }
        // Android hides these from apps; the runtime ships stand-ins.
        File(root, "etc/droiddeck/proc").listFiles()?.forEach { fake ->
            if (!File("/proc/${fake.name}").canRead()) bind("${fake.path}:/proc/${fake.name}")
        }
        bind(filesDir.path)
        bind(cacheDir.path)
        bind(runtimeDir().path)
        bind("${runtimeDir().path}:$GUEST_RUNTIME_DIR")
        val shm = File(cacheDir, "shm").apply { deleteRecursively(); mkdirs() }
        bind("${shm.path}:/dev/shm")
        Environment.getExternalStorageDirectory().takeIf { it.canRead() }?.let { bind(it.path) }

        val log = logFile(this).apply { parentFile?.mkdirs(); delete() }
        cmd += listOf(
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "PATH=/usr/local/bin:/usr/bin:/bin",
            "TERM=xterm-256color", "LANG=C.UTF-8", "TZ=" + TimeZone.getDefault().id,
            "XDG_RUNTIME_DIR=$GUEST_RUNTIME_DIR", "XDG_SESSION_TYPE=wayland", "WAYLAND_DISPLAY=wayland-0",
            "GAMESCOPE_FORCE_GENERAL_QUEUE=1",
            // Steam's CEF needs GL; the runtime has no native GL driver, so route it through Zink.
            "MESA_LOADER_DRIVER_OVERRIDE=zink", "GALLIUM_DRIVER=zink", "LIBGL_KOPPER_DRI2=true",
            "MESA_DISK_CACHE_DATABASE=1",
            "GLIBC_TUNABLES=glibc.pthread.rseq=0:glibc.malloc.top_pad=16777216",
            "BL_STEAMDECK=0", "BL_MANGOAPP=0",
            "BL_WIDTH=$width", "BL_HEIGHT=$height", "BL_FPS=0", "BL_REFRESH=${Math.round(refresh)}",
            "BL_LOG=${log.path}", "BL_DEBUG_DIR=${log.parentFile!!.path}",
            "BL_LAUNCH_DIR=${runtimeDir().path}",
        )
        File(root, "usr/share/vulkan/icd.d").listFiles { f -> f.name.endsWith(".json") }
            ?.let { icds -> (icds.firstOrNull { it.name.contains("freedreno") } ?: icds.firstOrNull()) }
            ?.let { cmd.add(cmd.indexOf("BL_STEAMDECK=0"), "VK_ICD_FILENAMES=/" + it.relativeTo(root).path) }
        cmd += listOf(SESSION_SCRIPT, "steam")

        Timber.tag("SteamSession").i("starting: %s", cmd.joinToString(" "))
        val pb = ProcessBuilder(cmd).directory(root).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        pb.environment().apply {
            clear()
            put("PROOT_LOADER", File(applicationInfo.nativeLibraryDir, "libprootlinux-loader.so").path)
            put("PROOT_TMP_DIR", cacheDir.path)
            put("PATH", "/system/bin")
        }
        session = runCatching { pb.start() }
            .onFailure { Timber.tag("SteamSession").e(it, "session failed to start") }
            .getOrNull()
        CompositorHost.newSession()
    }

    // ── Input → compositor ──────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = surfaceView.width.toFloat().coerceAtLeast(1f)
        val h = surfaceView.height.toFloat().coerceAtLeast(1f)
        fun send(action: Int, i: Int) {
            val x = (event.getX(i) / w).coerceIn(0f, 1f)
            val y = (event.getY(i) / h).coerceIn(0f, 1f)
            WaylandCompositor.nativeSendTouch(action, event.getPointerId(i), (x * INPUT_W).toInt(), (y * INPUT_H).toInt())
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { WaylandCompositor.nativeSendTouch(3, -1, 0, 0); send(0, event.actionIndex) }
            MotionEvent.ACTION_POINTER_DOWN -> send(0, event.actionIndex)
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) send(1, i)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> send(2, event.actionIndex)
            MotionEvent.ACTION_CANCEL -> WaylandCompositor.nativeSendTouch(3, -1, 0, 0)
            else -> return false
        }
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        val code = EvdevKeys.fromAndroid(event.keyCode) ?: return super.dispatchKeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) WaylandCompositor.nativeSendKey(code, 1)
            KeyEvent.ACTION_UP -> WaylandCompositor.nativeSendKey(code, 0)
        }
        return true
    }

    override fun onDestroy() {
        session?.destroy()
        session = null
        super.onDestroy()
    }
}

/** Android key codes → Linux evdev codes (input-event-codes.h), for keyboards in the session. */
internal object EvdevKeys {
    private val map: Map<Int, Int> = buildMap {
        val letters = "QWERTYUIOP" to 16; val home = "ASDFGHJKL" to 30; val bottom = "ZXCVBNM" to 44
        for ((row, start) in listOf(letters, home, bottom)) {
            row.forEachIndexed { i, c -> put(KeyEvent.KEYCODE_A + (c - 'A'), start + i) }
        }
        put(KeyEvent.KEYCODE_1, 2); put(KeyEvent.KEYCODE_2, 3); put(KeyEvent.KEYCODE_3, 4)
        put(KeyEvent.KEYCODE_4, 5); put(KeyEvent.KEYCODE_5, 6); put(KeyEvent.KEYCODE_6, 7)
        put(KeyEvent.KEYCODE_7, 8); put(KeyEvent.KEYCODE_8, 9); put(KeyEvent.KEYCODE_9, 10)
        put(KeyEvent.KEYCODE_0, 11)
        put(KeyEvent.KEYCODE_ESCAPE, 1); put(KeyEvent.KEYCODE_MINUS, 12); put(KeyEvent.KEYCODE_EQUALS, 13)
        put(KeyEvent.KEYCODE_DEL, 14); put(KeyEvent.KEYCODE_TAB, 15); put(KeyEvent.KEYCODE_ENTER, 28)
        put(KeyEvent.KEYCODE_CTRL_LEFT, 29); put(KeyEvent.KEYCODE_SHIFT_LEFT, 42); put(KeyEvent.KEYCODE_SHIFT_RIGHT, 54)
        put(KeyEvent.KEYCODE_ALT_LEFT, 56); put(KeyEvent.KEYCODE_SPACE, 57); put(KeyEvent.KEYCODE_CTRL_RIGHT, 97)
        put(KeyEvent.KEYCODE_ALT_RIGHT, 100); put(KeyEvent.KEYCODE_LEFT_BRACKET, 26); put(KeyEvent.KEYCODE_RIGHT_BRACKET, 27)
        put(KeyEvent.KEYCODE_SEMICOLON, 39); put(KeyEvent.KEYCODE_APOSTROPHE, 40); put(KeyEvent.KEYCODE_GRAVE, 41)
        put(KeyEvent.KEYCODE_BACKSLASH, 43); put(KeyEvent.KEYCODE_COMMA, 51); put(KeyEvent.KEYCODE_PERIOD, 52)
        put(KeyEvent.KEYCODE_SLASH, 53)
        put(KeyEvent.KEYCODE_DPAD_UP, 103); put(KeyEvent.KEYCODE_DPAD_LEFT, 105)
        put(KeyEvent.KEYCODE_DPAD_RIGHT, 106); put(KeyEvent.KEYCODE_DPAD_DOWN, 108)
        put(KeyEvent.KEYCODE_FORWARD_DEL, 111); put(KeyEvent.KEYCODE_MOVE_HOME, 102); put(KeyEvent.KEYCODE_MOVE_END, 107)
        put(KeyEvent.KEYCODE_PAGE_UP, 104); put(KeyEvent.KEYCODE_PAGE_DOWN, 109)
        for (i in 0..9) put(KeyEvent.KEYCODE_F1 + i, 59 + i)
        put(KeyEvent.KEYCODE_F11, 87); put(KeyEvent.KEYCODE_F12, 88)
    }

    fun fromAndroid(keyCode: Int): Int? = map[keyCode]
}
