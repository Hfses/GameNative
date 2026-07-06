package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Upcoming
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.R

/**
 * Changelog data, newest first. Kept as plain data so each release only needs
 * to edit this list.
 */
private data class WhatsNewSection(
    val icon: ImageVector,
    val title: String,
    val items: List<String>,
)

private val WHATS_NEW_SECTIONS = listOf(
    WhatsNewSection(
        icon = Icons.Default.BugReport,
        title = "Problemas encontrados",
        items = listOf(
            "Dois controles conectados brigavam pelo Jogador 1 (qualquer botão ia para o mesmo personagem).",
            "Crash latente no código de multiplayer local: uma estrutura interna nunca era inicializada.",
            "The Last of Us Part I fechava na inicialização: o jogo pede um bloco de memória virtual que o Android não oferece.",
            "Capas de jogos locais dependiam de uma chave do SteamGridDB que não existe em builds feitos fora do projeto original.",
            "Falhas de download/instalação de componentes eram engolidas sem nenhuma mensagem.",
            "Jogos em LAN não achavam salas: o Android descarta pacotes de descoberta sem uma permissão especial.",
        ),
    ),
    WhatsNewSection(
        icon = Icons.Default.Build,
        title = "Correções e novidades",
        items = listOf(
            "Suporte experimental a 2 controles: o primeiro vira Jogador 1, o segundo vira Jogador 2 automaticamente (containers Bionic; conecte os dois antes de abrir o jogo).",
            "Vibração (rumble) agora chega também ao controle do Jogador 2.",
            "Fix automático para The Last of Us Part I — vale para a versão Steam e para cópias locais (tlou-i.exe).",
            "Capas de jogos locais agora baixam da loja Steam quando não há chave do SteamGridDB.",
            "Nova tela Controles no menu: escolha qual controle é o Jogador 1 e qual é o Jogador 2.",
            "Modo de performance sustentada durante o jogo (menos queda de FPS por aquecimento em sessões longas).",
            "Jogos em LAN: descoberta de salas por broadcast liberada (CS 1.6, NFS MW 2005).",
            "Falhas de download/instalação agora geram registro com o motivo.",
            "Novo build de APK automático do fork (aba Actions do GitHub).",
        ),
    ),
    WhatsNewSection(
        icon = Icons.Default.Upcoming,
        title = "O que vem a seguir",
        items = listOf(
            "Aviso na tela (toast) com o motivo quando um download falhar.",
            "Telemetria local automática: medir FPS e crashes por jogo e sugerir ajustes — tudo no aparelho, nada enviado.",
            "Suporte a 4 controles depois que 2 estiverem validados.",
            "Melhorias na tela de salas LAN e guia para redes que bloqueiam jogadores entre si.",
        ),
    ),
)

@Composable
fun WhatsNewDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
        title = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.NewReleases,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(stringResource(R.string.whats_new_title))
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WHATS_NEW_SECTIONS.forEach { section ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = section.icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = section.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            section.items.forEach { item ->
                                Text(
                                    text = "•  $item",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}
