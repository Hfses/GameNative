package app.gamenative.linux

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Linux mode, stage 3: graphical Linux apps. An XFCE desktop runs inside the Ubuntu rootfs on
 * TigerVNC (software rendered, like DroidDeck's desktop) and is shown in-app through noVNC in a
 * WebView — no native compositor needed, works under proot. AppImages run from this desktop with
 * `gn-appimage file.AppImage` (extract-and-run, since FUSE isn't available to apps).
 */
object LinuxDesktop {
    const val NOVNC_PORT = 6080

    private val INSTALL_SCRIPT = """#!/bin/bash
set -e
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends \
  xfce4 xfce4-terminal xfce4-taskmanager mousepad thunar dbus-x11 \
  tigervnc-standalone-server tigervnc-tools novnc python3-websockify \
  fonts-dejavu-core adwaita-icon-theme ca-certificates file
echo "== desktop instalado =="
"""

    private val START_SCRIPT = """#!/bin/bash
export USER=root HOME=/root
mkdir -p /root/.vnc
cat > /root/.vnc/xstartup <<'X'
#!/bin/sh
unset SESSION_MANAGER DBUS_SESSION_BUS_ADDRESS
export XDG_RUNTIME_DIR=/tmp/runtime-root
mkdir -p "${'$'}XDG_RUNTIME_DIR"; chmod 700 "${'$'}XDG_RUNTIME_DIR"
exec dbus-launch --exit-with-session startxfce4
X
chmod +x /root/.vnc/xstartup
tigervncserver -kill :1 >/dev/null 2>&1
rm -f /tmp/.X1-lock /tmp/.X11-unix/X1
tigervncserver :1 -localhost yes -SecurityTypes None --I-KNOW-THIS-IS-INSECURE \
  -geometry "${'$'}{GN_GEOMETRY:-1280x720}" -depth 24 -xstartup /root/.vnc/xstartup
exec websockify --web /usr/share/novnc 6080 localhost:5901
"""

    private val APPIMAGE_SCRIPT = """#!/bin/bash
# Runs an AppImage without FUSE (unavailable to Android apps): extract-and-run.
[ -z "${'$'}1" ] && { echo "uso: gn-appimage arquivo.AppImage [args]"; exit 1; }
f="${'$'}1"; shift
chmod +x "${'$'}f"
if file "${'$'}f" | grep -q x86-64; then
  echo "Este AppImage é x86_64; o Ubuntu do app é ARM64. Procure a versão aarch64/arm64."; exit 1
fi
export DISPLAY="${'$'}{DISPLAY:-:1}"
exec "${'$'}f" --appimage-extract-and-run "${'$'}@"
"""

    fun isInstalled(context: Context) = File(LinuxEnvironment.rootDir(context), "usr/bin/startxfce4").exists()

    /** Writes helper scripts into the rootfs (/usr/local/bin). */
    fun writeScripts(context: Context) {
        val bin = File(LinuxEnvironment.rootDir(context), "usr/local/bin").apply { mkdirs() }
        fun put(name: String, body: String) {
            File(bin, name).apply { writeText(body); setExecutable(true, false) }
        }
        put("gn-desktop-install", INSTALL_SCRIPT)
        put("gn-desktop-start", START_SCRIPT)
        put("gn-appimage", APPIMAGE_SCRIPT)
    }

    /** Runs a guest command on a pipe shell; returns the process (stdout+stderr merged). */
    fun run(context: Context, command: String, extraEnv: String = ""): Process {
        writeScripts(context)
        val p = LinuxEnvironment.startShell(context)
        p.outputStream.bufferedWriter().apply {
            write("$extraEnv $command; echo \"__GN_EXIT__${'$'}?\"\n")
            write("exit\n")
            flush()
        }
        return p
    }

    fun portOpen(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
        true
    }.getOrDefault(false)
}

@Composable
fun LinuxDesktopPane(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf(LinuxDesktop.isInstalled(context)) }
    var installing by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf<Process?>(null) }
    var ready by remember { mutableStateOf(false) }
    val log = remember { mutableStateListOf<String>() }

    fun pump(p: Process, onLine: (String) -> Unit) = scope.launch(Dispatchers.IO) {
        p.inputStream.bufferedReader().useLines { seq ->
            seq.forEach { line -> scope.launch(Dispatchers.Main) { onLine(line) } }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Stop the desktop when leaving the screen.
            running?.destroy()
            runCatching { LinuxDesktop.run(context, "tigervncserver -kill :1") }
        }
    }

    Column(modifier.fillMaxSize()) {
        when {
            !installed -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Instale o desktop XFCE para rodar programas Linux com janela (~600 MB, só uma vez).")
                if (installing) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button(enabled = !installing, onClick = {
                    installing = true
                    log.clear()
                    val p = LinuxDesktop.run(context, "gn-desktop-install")
                    pump(p) { line ->
                        if (line.startsWith("__GN_EXIT__")) {
                            installing = false
                            installed = LinuxDesktop.isInstalled(context)
                            log += if (installed) "Pronto!" else "Falhou (código ${line.removePrefix("__GN_EXIT__")})."
                        } else {
                            log += line
                            if (log.size > 2000) log.removeAt(0)
                        }
                    }
                }) { Text(if (installing) "Instalando…" else "Instalar desktop") }
                LogView(log, Modifier.weight(1f))
            }
            running == null -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Desktop XFCE instalado. Toque em Iniciar para abrir. AppImages ARM64: no terminal do desktop use gn-appimage arquivo.AppImage")
                Button(onClick = {
                    log.clear()
                    ready = false
                    val dm = context.resources.displayMetrics
                    val w = maxOf(dm.widthPixels, dm.heightPixels).coerceAtMost(1920)
                    val h = minOf(dm.widthPixels, dm.heightPixels).coerceAtMost(1080)
                    val p = LinuxDesktop.run(context, "gn-desktop-start", extraEnv = "GN_GEOMETRY=${w}x$h")
                    running = p
                    pump(p) { line -> log += line }
                    scope.launch {
                        repeat(60) {
                            if (withContext(Dispatchers.IO) { LinuxDesktop.portOpen(LinuxDesktop.NOVNC_PORT) }) {
                                ready = true
                                return@launch
                            }
                            delay(500)
                        }
                        log += "O desktop não respondeu em 30 s. Veja o log acima."
                    }
                }) { Text("Iniciar desktop") }
                LogView(log, Modifier.weight(1f))
            }
            !ready -> Column(Modifier.padding(16.dp)) {
                Text("Iniciando o desktop…")
                LinearProgressIndicator(Modifier.fillMaxWidth())
                LogView(log, Modifier.weight(1f))
            }
            else -> Column(Modifier.fillMaxSize()) {
                Row {
                    TextButton(onClick = {
                        running?.destroy()
                        running = null
                        ready = false
                        LinuxDesktop.run(context, "tigervncserver -kill :1")
                    }) { Text("Parar desktop") }
                }
                NoVncView(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun LogView(lines: List<String>, modifier: Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) state.scrollToItem(lines.lastIndex) }
    LazyColumn(state = state, modifier = modifier.fillMaxWidth().background(Color(0xFF101014)).padding(8.dp)) {
        items(lines) { Text(it, color = Color(0xFFE0E0E0), fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun NoVncView(modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                webViewClient = WebViewClient()
                loadUrl(
                    "http://127.0.0.1:${LinuxDesktop.NOVNC_PORT}/vnc.html" +
                        "?autoconnect=true&resize=remote&reconnect=true&show_dot=true",
                )
            }
        },
        onRelease = { it.destroy() },
    )
}
