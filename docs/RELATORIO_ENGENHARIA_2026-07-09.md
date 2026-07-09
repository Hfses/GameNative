# Relatório de engenharia — GameNative (2026-07-09)

Entrega referente à solicitação: análise completa, compatibilidade automática Wine↔Box64,
correção do erro `__libc_init`, biblioteca em tempo real, sistema de capas, layouts,
aproveitamento do XoDos.

## 1. Novas funcionalidades implementadas nesta entrega

### 1.1 Compatibilidade automática Wine ↔ Box64 (matriz interna)
**Arquivo novo:** `app/src/main/java/app/gamenative/utils/RuntimeCompatibility.kt`

- Matriz interna Wine/Proton ↔ variante do container ↔ Box64 mínimo:

| Wine/Proton | Container | Box64 mínimo |
|---|---|---|
| wine-9.x | glibc | qualquer |
| wine/proton-10.x | glibc | 0.3.6 |
| wine/proton-11.x | glibc | 0.3.6 |
| proton-9.x | bionic | qualquer |
| wine/proton-10.x | bionic | 0.4.0 |
| wine/proton-11.x | bionic | 0.4.2 |

- **No seletor (GeneralTab):** escolher p.ex. Wine 11 com Box64 0.3.4 → o app ajusta o Box64
  para 0.3.6 na mesma ação e mostra aviso visual explicando a mudança (exemplo pedido no chamado).
- DXVK/VKD3D/Turnip: os pré-requisitos por variante já eram trocados automaticamente ao mudar
  glibc↔bionic (driver, dxwrapper, config); a matriz cobre agora o eixo que faltava (Box64×Wine).

### 1.2 Correção do erro crítico `Symbol __libc_init not found`
**Causa raiz:** binário de Wine linkado contra uma libc (bionic) sendo carregado num container da
outra libc (glibc) — o relocador não encontra `__libc_init` e aborta.

**Correção (pré-launch, em `XServerScreen` + `RuntimeCompatibility.checkAndAutoFix`):**
- Detecta automaticamente a libc do Wine selecionado **lendo os ELF** (`bin/wine*`):
  `libc.so.6`/`__libc_start_main` → glibc; `__libc_init`/`liblog.so` → bionic.
- Se houver conflito com a variante do container, **impede o crash antes da execução**, aplica
  fallback compatível (glibc → `wine-9.2-x86_64`; bionic → `proton-9.0-arm64ec`), persiste,
  dispara a re-extração correta do prefixo e **informa o usuário** (snackbar) com:
  Wine selecionado → motivo da falha → ação aplicada → status.
- Registra tudo em log amigável: `files/logs/runtime_compat.log`.

### 1.3 Biblioteca em tempo real
**Arquivo novo:** `app/src/main/java/app/gamenative/utils/CustomGameWatcher.kt` +
integração no `LibraryViewModel`.
- FileObserver (inotify) por pasta de jogo custom; debounce de 1 s; invalida somente o cache de
  jogos custom e re-filtra apenas a página atual (sem refresh completo da tela).
- Novos jogos copiados para o aparelho, capas e exes aparecem/atualizam sozinhos — inclusive
  nome, capa e metadados.

### 1.4 Sistema de capas
**Arquivo novo:** `app/src/main/java/app/gamenative/utils/CoverArtManager.kt` + opções
"Trocar capa" / "Remover capa personalizada" no painel de opções do jogo (jogos custom).
- Escolher imagem (seletor do sistema) → decodificação com subsampling (sem OOM), redimensiona
  para 1440 px, salva `cover.jpg` otimizado na pasta do jogo (prioridade sobre SteamGridDB).
- Remover capa restaura a arte padrão. Lote: como a capa é um arquivo `cover.*` na pasta do jogo,
  copiar várias capas por USB/gerenciador já funciona e a biblioteca atualiza sozinha (1.3).
- Recorte automático: o layout aplica crop central (ContentScale.Crop) nas visões capsule/hero.

### 1.5 Wallpaper/vídeo animado
- Já entregues nesta branch: fundo animado (vídeo com som) no **login** e na **biblioteca**
  (menu Layout), com scrim para legibilidade, pausa em background e escolha de vídeo/imagem.
- Pausar quando o jogo abre: o player vive na tela da biblioteca — ao navegar para o jogo a
  composição sai e o player é liberado (comportamento pedido).
- Perfis de layout (Minimalista/Gamer/Arcade/Steam-like) e vídeo em *todas* as telas: **não
  implementado ainda** — proposto como próximo passo (ver §4).

### 1.6 Limpeza automática do armazenamento do Actions
- Workflow `build-apk.yml`: job `cleanup` roda no início de cada build e **mantém só os artifacts
  das últimas 4 execuções** (o que o usuário pediu: "limpar a cada 4 action"), além de
  `retention-days: 1` nos uploads. Releases (o link durável de download) não contam na cota.

## 2. Bugs corrigidos nesta branch (auditoria + verificação adversarial)

Resumo: 1 crítico, 7 altos, ~12 médios, ~10 baixos corrigidos até aqui. Destaques:

| Sev. | Onde | Bug |
|---|---|---|
| CRÍTICO | MainViewModel | Loop infinito/ANR ao mapear janela (lia `window.parent` fixo) |
| ALTO | SteamService | HashMap de progresso mutado por threads paralelas → ConcurrentHashMap |
| ALTO | GOG/Epic | Chunk com falha permanente travava o download para sempre |
| ALTO | IntentLaunchManager | mergeConfigurations perdia ~30 campos de config |
| ALTO | DrawRequests | PolyFillRectangle com cor errada (protocolo X11) |
| MÉDIO | LAN | Overlay de chat preso após sala fechar; falso "porta em uso"; envio na UI thread |
| MÉDIO | Download-all | Diálogo modal preso em erro (try/finally) |

Pendentes conhecidos (2 altos que exigem teste em aparelho): double-close de fence fd no
renderer nativo (`ASurfaceRendererContext.cpp:442`) e hot-path de toque do
`InputControlsView` — documentados em TRIAGEM (scratchpad) e no histórico da conversa.

## 3. Integração XoDos

Ver `docs/XODOS_ANALISE.md`. Resumo: XoDos = Termux + termux-x11 + Debian proot; para *jogos*
o GameNative já contém equivalentes integrados de tudo; o ganho real (proteção contra mistura
de libc) foi implementado (§1.2). Não recomendo migrar Termux/rootfs/termux-x11.

## 4. Próximos problemas/melhorias que ainda podem existir

1. Big Picture da Steam (modo picup): plano pronto (args `-no-cef-sandbox -start
   steam://open/bigpicture`), risco = steamwebhelper/CEF em tela preta; implementar toggle e
   testar em aparelho.
2. Perfis de layout + vídeo global (todas as telas) com controle de opacidade/transições.
3. Aplicar H1/H2 do estudo de desempenho (merge de `WINEDLLOVERRIDES`, fixar
   `MESA_SHADER_CACHE_DIR`) e M1–M4 opt-in.
4. Lojas personalizadas do Game Hub estão inertes (nunca viram StoreProvider) — completar ou
   ocultar o recurso.
5. Status de conexão das lojas no Game Hub é snapshot único (não atualiza após login/logout).
6. Verificação final por hash/tamanho nos downloads Epic (assembleReady ignora falha por posição).
7. Medições antes/depois de FPS/tempo de abertura — exigem aparelho (roteiro no doc do XoDos).

## 5. Como testar (usuário)

APK contínuo: release `debug-claude-gamenative-comprehensive-review-egflnd` no GitHub.
Casos de teste sugeridos:
- Selecionar Wine 11/Proton 11 com Box64 antigo → deve ajustar sozinho e avisar.
- Forçar um Wine bionic num container glibc (ou vice-versa) → jogo não crasha mais com
  `__libc_init`; aparece aviso e o fallback abre; conferir `files/logs/runtime_compat.log`.
- Copiar uma pasta de jogo custom (ou um `cover.jpg`) com o app aberto → biblioteca atualiza sozinha.
- Menu do jogo custom → Trocar capa / Remover capa personalizada.
