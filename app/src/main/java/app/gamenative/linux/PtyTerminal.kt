package app.gamenative.linux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import timber.log.Timber

/**
 * Real terminal (stage 2): a PTY-backed Termux TerminalSession running the Ubuntu login shell
 * inside proot, rendered by Termux's TerminalView (xterm-256color: vim, htop, nano, tmux work).
 * Extra-keys row supplies ESC/CTRL/ALT/TAB/arrows that phone keyboards lack.
 */
@Composable
fun PtyTerminal(onExit: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var fontSize by remember { mutableIntStateOf(28) }
    var generation by remember { mutableIntStateOf(0) } // bump to restart
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }

    val session = remember(generation) {
        val sc = LinuxEnvironment.shellCommand(context, term = "xterm-256color", interactiveLogin = true)
        val client = object : TerminalSessionClient {
            override fun onTextChanged(changedSession: TerminalSession) { terminalView?.onScreenUpdated() }
            override fun onTitleChanged(changedSession: TerminalSession) = Unit
            override fun onSessionFinished(finishedSession: TerminalSession) = Unit
            override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("terminal", text))
            }
            override fun onPasteTextFromClipboard(session: TerminalSession?) {
                val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                val text = clip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
                session?.emulator?.paste(text)
            }
            override fun onBell(session: TerminalSession) = Unit
            override fun onColorsChanged(session: TerminalSession) = Unit
            override fun onTerminalCursorStateChange(state: Boolean) = Unit
            override fun getTerminalCursorStyle(): Int? = null
            override fun logError(tag: String?, message: String?) { Timber.tag("Pty").e(message) }
            override fun logWarn(tag: String?, message: String?) { Timber.tag("Pty").w(message) }
            override fun logInfo(tag: String?, message: String?) = Unit
            override fun logDebug(tag: String?, message: String?) = Unit
            override fun logVerbose(tag: String?, message: String?) = Unit
            override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Timber.tag("Pty").e(e, message) }
            override fun logStackTrace(tag: String?, e: Exception?) { Timber.tag("Pty").e(e) }
        }
        TerminalSession(
            sc.executable,
            LinuxEnvironment.rootDir(context).path,
            (listOf(sc.executable) + sc.args).toTypedArray(),
            sc.env.map { "${it.key}=${it.value}" }.toTypedArray(),
            5000,
            client,
        )
    }

    DisposableEffect(session) {
        onDispose { session.finishIfRunning() }
    }

    fun sendKey(seq: String) {
        session.write(seq)
    }

    Column(modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                TerminalView(ctx, null).also { view ->
                    terminalView = view
                    view.setTerminalViewClient(object : TerminalViewClient {
                        override fun onScale(scale: Float): Float {
                            if (scale < 0.9f || scale > 1.1f) {
                                fontSize = (fontSize * scale).toInt().coerceIn(12, 72)
                                view.setTextSize(fontSize)
                                return 1f
                            }
                            return scale
                        }
                        override fun onSingleTapUp(e: MotionEvent) {
                            view.requestFocus()
                            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                                .showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
                        }
                        override fun shouldBackButtonBeMappedToEscape() = false
                        override fun shouldEnforceCharBasedInput() = true
                        override fun shouldUseCtrlSpaceWorkaround() = false
                        override fun isTerminalViewSelected() = true
                        override fun copyModeChanged(copyMode: Boolean) = Unit
                        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
                            if (keyCode == KeyEvent.KEYCODE_ENTER && !session.isRunning) {
                                onExit()
                                return true
                            }
                            return false
                        }
                        override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
                        override fun onLongPress(event: MotionEvent) = false
                        override fun readControlKey() = ctrl.also { if (it) ctrl = false }
                        override fun readAltKey() = alt.also { if (it) alt = false }
                        override fun readShiftKey() = false
                        override fun readFnKey() = false
                        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = false
                        override fun onEmulatorSet() = Unit
                        override fun logError(tag: String?, message: String?) { Timber.tag("Pty").e(message) }
                        override fun logWarn(tag: String?, message: String?) = Unit
                        override fun logInfo(tag: String?, message: String?) = Unit
                        override fun logDebug(tag: String?, message: String?) = Unit
                        override fun logVerbose(tag: String?, message: String?) = Unit
                        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
                        override fun logStackTrace(tag: String?, e: Exception?) = Unit
                    })
                    view.setTextSize(fontSize)
                    view.isFocusable = true
                    view.isFocusableInTouchMode = true
                    view.attachSession(session)
                    view.requestFocus()
                }
            },
            update = { view ->
                if (view.currentSession !== session) view.attachSession(session)
            },
        )
        // Extra keys
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
            TextButton(onClick = { sendKey("\u001b") }) { Text("ESC") }
            FilterChip(selected = ctrl, onClick = { ctrl = !ctrl }, label = { Text("CTRL") })
            FilterChip(selected = alt, onClick = { alt = !alt }, label = { Text("ALT") })
            TextButton(onClick = { sendKey("\t") }) { Text("TAB") }
            TextButton(onClick = { sendKey("\u001b[D") }) { Text("←") }
            TextButton(onClick = { sendKey("\u001b[B") }) { Text("↓") }
            TextButton(onClick = { sendKey("\u001b[A") }) { Text("↑") }
            TextButton(onClick = { sendKey("\u001b[C") }) { Text("→") }
            TextButton(onClick = { sendKey("\u0003") }) { Text("^C") }
            TextButton(onClick = { sendKey("\u0004") }) { Text("^D") }
            TextButton(onClick = { sendKey("|") }) { Text("|") }
            TextButton(onClick = { sendKey("/") }) { Text("/") }
            TextButton(onClick = { sendKey("-") }) { Text("-") }
            TextButton(onClick = { generation++ }) { Text("Reiniciar") }
        }
    }
}
