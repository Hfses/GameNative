package app.gamenative.ui.component.dialog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.utils.PhantomProcessLimit
import app.gamenative.utils.WirelessAdbFix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource

/**
 * Turns off Android's child-process limit without a PC, via Wireless debugging (flow from
 * DroidDeck). The user opens Wireless debugging → "Pair device with pairing code" in split screen
 * or a notification, and types the port and 6-digit code shown there.
 */
@Composable
fun WirelessAdbFixDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var port by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    fun run(pairFirst: Boolean) {
        busy = true
        status = context.getString(R.string.adbfix_working)
        scope.launch {
            val result = runCatching {
                if (pairFirst) WirelessAdbFix.pair(context, port.trim().toInt(), code)
                withContext(Dispatchers.IO) { WirelessAdbFix.disableChildProcessLimit(context) }
            }
            busy = false
            status = result.fold(
                onSuccess = { context.getString(R.string.adbfix_done) },
                onFailure = { context.getString(R.string.adbfix_failed, it.message ?: it.javaClass.simpleName) },
            )
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.adbfix_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.adbfix_steps))
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }) { Text(stringResource(R.string.phantom_open_dev_options)) }
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.adbfix_port)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    label = { Text(stringResource(R.string.adbfix_code)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                if (WirelessAdbFix.hasPairing(context)) {
                    TextButton(enabled = !busy, onClick = { run(pairFirst = false) }) {
                        Text(stringResource(R.string.adbfix_use_saved))
                    }
                }
                TextButton(onClick = {
                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("adb", PhantomProcessLimit.adbCommand()))
                }) { Text(stringResource(R.string.phantom_copy_adb)) }
                status?.let { Text(it) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && port.toIntOrNull() in 1..65535 && code.length == 6,
                onClick = { run(pairFirst = true) },
            ) { Text(stringResource(R.string.adbfix_pair_fix)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
