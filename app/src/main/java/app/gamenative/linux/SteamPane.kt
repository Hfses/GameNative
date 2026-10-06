package app.gamenative.linux

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Linux mode stage 5: install the Steam ARM runtime and open a GPU session (experimental). */
@Composable
fun SteamPane() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf(SteamRuntime.isInstalled(context)) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Steam ARM (experimental)", style = MaterialTheme.typography.titleLarge)
        Text(
            "Cliente Steam ARM nativo da Valve + Proton ARM64, com GPU, pelo runtime do DroidDeck " +
                "(Arch Linux ARM, ~790 MB; o Steam é baixado da Valve no primeiro uso). " +
                "Requer Adreno 730 ou mais novo (Snapdragon 8 Gen 2+). Ainda sem áudio e sem controle físico.",
        )
        if (!installed) {
            progress?.let { p ->
                Text(status)
                if (p < 0f) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = progress == null, onClick = {
                error = null
                progress = -1f
                scope.launch {
                    runCatching { SteamRuntime.install(context) { p, s -> progress = p; status = s } }
                        .onSuccess { installed = true }
                        .onFailure { error = it.message }
                    progress = null
                }
            }) { Text("Instalar runtime Steam") }
        } else {
            Text("Runtime instalado: ${SteamRuntime.installedRelease(context)}")
            Button(onClick = { context.startActivity(SteamSessionActivity.intent(context)) }) { Text("Abrir Steam") }
            Text(
                "Se não abrir, o log fica em ${SteamSessionActivity.logFile(context).path}",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { SteamRuntime.uninstall(context) }
                    installed = false
                }
            }) { Text("Remover runtime Steam") }
        }
    }
}
