package app.gamenative.emulation

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** "Consoles" hub: one mode per system — install its core, import games, play in-app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsolesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { ConsoleLibrary.load(context) }
    val games by ConsoleLibrary.games.collectAsState()

    var selected by remember { mutableStateOf(ConsoleSystem.PS1) }
    var coreVersion by remember { mutableIntStateOf(0) } // bump to recompose after install/remove
    var progress by remember { mutableStateOf<Float?>(null) }
    var busy by remember { mutableStateOf(false) }

    val installed = remember(selected, coreVersion) { CoreManager.isInstalled(context, selected) }

    val pickGame = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val system = selected
        busy = true
        scope.launch {
            runCatching { ConsoleLibrary.import(context, system, uri) }
                .onSuccess { Toast.makeText(context, "${it.title} adicionado", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, it.message ?: "Falha ao importar", Toast.LENGTH_LONG).show() }
            busy = false
        }
    }
    val pickBios = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { ConsoleLibrary.importBios(context, uri) }
                .onSuccess { Toast.makeText(context, "BIOS $it instalado", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, it.message ?: "Falha ao importar BIOS", Toast.LENGTH_LONG).show() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Consoles") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ConsoleSystem.entries) { sys ->
                    FilterChip(
                        selected = sys == selected,
                        onClick = { selected = sys },
                        label = { Text(sys.displayName) },
                    )
                }
            }

            Card(Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(selected.displayName, style = MaterialTheme.typography.titleLarge)
                    Text(selected.maker, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.padding(4.dp))
                    if (!selected.available) {
                        Text(selected.unavailableReason ?: "Indisponível no Android", color = MaterialTheme.colorScheme.error)
                    } else {
                        val notes = buildList {
                            add("Emulador: ${selected.coreName}")
                            if (selected.heavy) add("Exige aparelho potente (Snapdragon 8 Gen 1 ou superior recomendado).")
                            if (selected.experimental) add("Experimental: nem todos os jogos funcionam.")
                            if (selected.biosFiles.isNotEmpty()) add("BIOS: ${selected.biosFiles.joinToString()}")
                            add("Formatos: ${selected.extensions.joinToString()}")
                        }
                        notes.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        Spacer(Modifier.padding(4.dp))
                        progress?.let { p ->
                            if (p < 0f) LinearProgressIndicator(Modifier.fillMaxWidth())
                            else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!installed) {
                                Button(enabled = progress == null, onClick = {
                                    val sys = selected
                                    progress = -1f
                                    scope.launch {
                                        runCatching { CoreManager.install(context, sys) { progress = it } }
                                            .onFailure { Toast.makeText(context, "Falha ao baixar o emulador: ${it.message}", Toast.LENGTH_LONG).show() }
                                        progress = null
                                        coreVersion++
                                    }
                                }) { Text("Baixar emulador") }
                            } else {
                                Button(enabled = !busy, onClick = { pickGame.launch(arrayOf("*/*")) }) {
                                    Text(if (busy) "Importando…" else "Adicionar jogo")
                                }
                                OutlinedButton(onClick = {
                                    CoreManager.uninstall(context, selected)
                                    coreVersion++
                                }) { Text("Remover emulador") }
                            }
                            if (selected.biosFiles.isNotEmpty()) {
                                OutlinedButton(onClick = { pickBios.launch(arrayOf("*/*")) }) { Text("BIOS") }
                            }
                        }
                    }
                }
            }

            val systemGames = games.filter { it.systemId == selected.id }.sortedBy { it.title.lowercase() }
            if (selected.available && systemGames.isEmpty()) {
                Text(
                    "Nenhum jogo de ${selected.displayName}. Toque em Adicionar jogo e escolha seu arquivo.",
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(systemGames, key = { it.path }) { game ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = installed) {
                                context.startActivity(ConsoleGameActivity.intent(context, game))
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(game.title, style = MaterialTheme.typography.titleMedium)
                            Text(game.file.extension.uppercase(), style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = { ConsoleLibrary.remove(context, game) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remover")
                        }
                    }
                }
            }
        }
    }
}
