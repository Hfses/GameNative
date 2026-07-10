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
        icon = Icons.Default.NewReleases,
        title = "Desempenho e FPS",
        items = listOf(
            "Correção do maior gargalo de FPS recente: o modo de compatibilidade do compositor fazia uma conversão de cor na GPU a cada quadro. Agora vem desligado por padrão (Gráficos > Modo de Compatibilidade se algum jogo ficar com cores invertidas).",
            "Taxa de atualização máxima do painel durante o jogo (destrava FPS acima de 60 em telas de 90/120 Hz).",
            "Cache de shader (Mesa/DXVK) fixado em disco: menos engasgo de primeira execução a cada sessão.",
            "Governador térmico (ADPF) e wakelock de sessão: menos queda de FPS por aquecimento e o jogo não congela se a tela bloquear.",
            "Novos containers usam só os núcleos rápidos por padrão; env vars de desempenho do Box64 e Turnip ampliadas.",
        ),
    ),
    WhatsNewSection(
        icon = Icons.Default.Build,
        title = "Compatibilidade e estabilidade",
        items = listOf(
            "Matriz automática Wine↔Box64: ao escolher uma versão incompatível, o app ajusta o Box64 sozinho e avisa.",
            "Proteção contra o erro \"Symbol __libc_init not found\": detecta Wine da libc errada antes do jogo abrir e troca por uma versão compatível.",
            "Rotação no jogo agora segue o celular (gira entre os dois lados quando deitado).",
            "Correção do crash ao ler versões com sufixo \"(Default)\" (Box64/Proton).",
            "Sistema de log de verdade + diagnóstico de erros do guest; kill em segundo plano (137) não é mais tratado como crash do jogo.",
            "Melhorias do Winlator-Ludashi portadas e verificadas no código-fonte.",
            "Dezenas de bugs corrigidos por auditoria: travamentos, vazamentos, downloads de GOG/Epic que travavam, e uma falha de segurança no SDK da Amazon.",
        ),
    ),
    WhatsNewSection(
        icon = Icons.Default.NewReleases,
        title = "Biblioteca e interface",
        items = listOf(
            "O app abre direto na biblioteca — a tela de login das lojas deixou de ser obrigatória (login continua no menu do sistema).",
            "Papel de parede / vídeo animado com som no fundo do app inteiro, configurável pelo menu Layout (aplica na hora).",
            "Gerenciador de capas para jogos locais: trocar/remover capa (aceita fotos HEIC), com redimensionamento automático.",
            "Biblioteca em tempo real: jogos copiados para o aparelho aparecem sozinhos, sem atualizar manualmente.",
            "Menu \"Layout\" reunindo ordenar por, tipo de app, status e carrossel; \"Baixar tudo\" para componentes de emulação.",
            "Loja unificada (Steam/GOG/Epic/Amazon) com favoritos e lojas personalizadas; interface toda traduzida para PT-BR.",
        ),
    ),
    WhatsNewSection(
        icon = Icons.Default.Build,
        title = "Multiplayer local (LAN)",
        items = listOf(
            "Salas LAN: segure um jogo instalado > Jogar LAN. Crie (nome + senha opcional, o IP aparece pronto) ou entre com o link/IP de convite.",
            "Chat entre jogadores dentro do jogo; descoberta de salas por broadcast liberada (CS 1.6, NFS MW 2005).",
            "Gerenciador de salas reforçado contra falhas de rede e travamentos.",
        ),
    ),
    WhatsNewSection(
        icon = Icons.Default.Upcoming,
        title = "O que vem a seguir",
        items = listOf(
            "Botão \"Dicas\" no jogo: histórico de FPS por configuração e aplicar a melhor com um toque.",
            "Perfil \"Máximo Desempenho\" (CPU/GPU) para jogos onde a CPU fica em 100% e a GPU ociosa.",
            "Remover camadas de tradução quando o jogo não precisa, para ganhar FPS.",
            "Steam Big Picture (modo picup) e recorte manual de capa.",
            "Mais opções de download (versões de Box64/Wine/drivers) e verificação de dependências.",
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
