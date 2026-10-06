package app.gamenative.linux

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.OutputStreamWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_LINES = 5000

/** Linux mode, stage 1: install Ubuntu and use a root terminal with internet access. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinuxTerminalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf(LinuxEnvironment.isInstalled(context)) }
    val prootOk = remember { LinuxEnvironment.prootAvailable(context) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var progressText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmUninstall by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Linux (Ubuntu)") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
                actions = {
                    if (installed) TextButton(onClick = { confirmUninstall = true }) { Text("Desinstalar") }
                },
            )
        },
    ) { padding ->
        if (confirmUninstall) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmUninstall = false },
                title = { Text("Desinstalar o Linux?") },
                text = { Text("Todos os arquivos e programas instalados no Ubuntu serão apagados.") },
                confirmButton = {
                    OutlinedButton(onClick = {
                        confirmUninstall = false
                        installed = false
                        scope.launch { withContext(Dispatchers.IO) { LinuxEnvironment.uninstall(context) } }
                    }) { Text("Desinstalar") }
                },
                dismissButton = { TextButton(onClick = { confirmUninstall = false }) { Text("Cancelar") } },
            )
        }
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            when {
                !prootOk -> Text(
                    "Este build não inclui o PRoot do modo Linux (libprootlinux.so). " +
                        "Gere-o com tools/linux-proot/build.sh e recompile o app.",
                    Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error,
                )
                !installed -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Instale um Ubuntu 24.04 ARM64 completo (~28 MB para baixar, ~150 MB instalado). " +
                        "Você terá um terminal root com apt, internet e acesso ao armazenamento em /sdcard.")
                    progress?.let { p ->
                        Text(progressText)
                        if (p < 0f) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(enabled = progress == null, onClick = {
                        error = null
                        progress = -1f
                        scope.launch {
                            runCatching {
                                LinuxEnvironment.install(context) { p, t -> progress = p; progressText = t }
                            }.onSuccess { installed = true }
                                .onFailure { error = it.message }
                            progress = null
                        }
                    }) { Text("Instalar Linux") }
                }
                // Real PTY terminal (stage 2). The pipe-based Terminal below stays as a fallback.
                else -> PtyTerminal(onExit = onBack)
            }
        }
    }
}

@Composable
private fun Terminal(onUninstall: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lines = remember { mutableStateListOf<String>() }
    var input by remember { mutableStateOf("") }
    var session by remember { mutableIntStateOf(0) } // bump to restart the shell
    var process by remember { mutableStateOf<Process?>(null) }
    var writer by remember { mutableStateOf<OutputStreamWriter?>(null) }
    val listState = rememberLazyListState()
    val history = remember { mutableStateListOf<String>() }
    var confirmUninstall by remember { mutableStateOf(false) }

    fun append(text: String) {
        lines += text
        if (lines.size > MAX_LINES) lines.removeRange(0, lines.size - MAX_LINES)
    }

    DisposableEffect(session) {
        val p = runCatching { LinuxEnvironment.startShell(context) }
            .onFailure { append("[erro ao iniciar o shell: ${it.message}]") }
            .getOrNull()
        process = p
        writer = p?.let { OutputStreamWriter(it.outputStream, Charsets.UTF_8) }
        if (p != null) {
            append("[Ubuntu pronto — você é root. Ex.: apt update && apt install -y python3 curl git]")
            scope.launch(Dispatchers.IO) {
                // Read raw so prompts without newline (apt "Y/n") still show up.
                val reader = p.inputStream.bufferedReader(Charsets.UTF_8)
                val buf = CharArray(4096)
                val pending = StringBuilder()
                while (true) {
                    val n = runCatching { reader.read(buf) }.getOrDefault(-1)
                    if (n < 0) break
                    pending.append(buf, 0, n)
                    val text = pending.toString().replace("\r\n", "\n").replace('\r', '\n')
                    val parts = text.split('\n')
                    pending.setLength(0)
                    pending.append(parts.last())
                    val complete = parts.dropLast(1)
                    val partial = if (!reader.ready() && pending.isNotEmpty()) pending.toString().also { pending.setLength(0) } else null
                    withContext(Dispatchers.Main) {
                        complete.forEach { append(stripAnsi(it)) }
                        partial?.let { append(stripAnsi(it)) }
                    }
                }
                withContext(Dispatchers.Main) { append("[shell encerrado — toque em Reiniciar]") }
            }
        }
        onDispose {
            runCatching { writer?.close() }
            p?.destroy()
        }
    }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    fun send(cmd: String) {
        val w = writer ?: return
        append("# $cmd")
        if (cmd.isNotBlank()) history += cmd
        scope.launch(Dispatchers.IO) {
            runCatching {
                w.write(cmd + "\n")
                w.flush()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            TextButton(onClick = { session++ }) { Text("Reiniciar / Ctrl+C") }
            TextButton(onClick = { lines.clear() }) { Text("Limpar") }
            TextButton(onClick = { send("apt update") }) { Text("apt update") }
            TextButton(onClick = { history.lastOrNull()?.let { input = it } }) { Text("↑ Último") }
            TextButton(onClick = { confirmUninstall = true }) { Text("Desinstalar") }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF101014))
                .padding(8.dp),
        ) {
            items(lines) { line ->
                Text(line, color = Color(0xFFE0E0E0), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("comando…") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSend = { send(input); input = "" }),
            )
            Button(onClick = { send(input); input = "" }, modifier = Modifier.padding(start = 8.dp)) { Text("Enviar") }
        }
    }

    if (confirmUninstall) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmUninstall = false },
            title = { Text("Desinstalar o Linux?") },
            text = { Text("Todos os arquivos e programas instalados no Ubuntu serão apagados.") },
            confirmButton = {
                OutlinedButton(onClick = {
                    confirmUninstall = false
                    process?.destroy()
                    onUninstall()
                }) { Text("Desinstalar") }
            },
            dismissButton = { TextButton(onClick = { confirmUninstall = false }) { Text("Cancelar") } },
        )
    }
}

private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
private fun stripAnsi(s: String) = ANSI.replace(s, "")
