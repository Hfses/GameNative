# GameNative — Mudanças deste fork (Hfses/GameNative)

Documento único com **tudo que foi feito** neste fork em cima do GameNative original
(Winlator + Pluvia). Serve como changelog e guia de referência.

**Números:** 95 commits · 134 arquivos alterados · +8.839 / −420 linhas.
**Branch de desenvolvimento:** `claude/gamenative-comprehensive-review-egflnd`.
**Todos os commits compilam verde no CI** (`build-apk.yml` + testes unitários).

> GameNative roda jogos Windows no Android via **Wine/Proton + Box64/FEXCore +
> DXVK/VKD3D + Turnip/Zink/Vortek**, com integração **Steam/GOG/Epic/Amazon**.
> Kotlin + Java + C/C++ nativo, Jetpack Compose, Hilt, Room, DataStore, ExoPlayer.

---

## 1. Correções críticas de crash e estabilidade

Achadas por uma auditoria com dezenas de agentes (33 agentes, verificação
adversarial de cada achado) sobre o crash ao entrar no jogo.

- **Crash nativo ao lançar o jogo + "instalando bionic" em todo launch**
  (`ImageFsInstaller`). O instalador marcava uma imagem **parcialmente instalada**
  como válida: as extrações de Wine e das libs do runtime (`redirect.tzst` /
  `extras.tzst`) eram `void` e seus erros descartados, mas a versão era gravada
  mesmo assim → o Box64/Wine subia sem os `.so` necessários → **SIGSEGV**. Como o
  arquivo `.variant` só era escrito *depois* do launch (que o crash nunca
  alcançava), a imagem reinstalava e **re-apagava a árvore toda a cada abertura**.
  **Correção:** as extrações agora retornam sucesso/erro; a imagem só é carimbada
  como válida quando a árvore inteira extrai; o `.variant` é escrito junto com a
  versão (para de reinstalar); uma instalação que falha mostra erro em vez de
  crashar.
- **Preset Box64 "revertendo para Estabilidade"** — era artefato de exibição: o
  dropdown colapsava qualquer id não encontrado para o índice 0 (= Estabilidade).
  Correção: match case-insensitive + valor real preservado; `Box86_64PresetManager`
  resolve ids case-insensitive (id não casado antes aplicava env vars vazias no
  launch); o toggle "Perfil Máximo" restaura o preset anterior ao desligar.
- **Double-close de fence fd no scanout direto** (`ASurfaceRendererContext.cpp`) —
  o mesmo fd ia para o compositor e para o retorno da fonte, podendo fechar um fd
  alheio. Agora vai para um único dono.
- **`dxvk-null` / `vkd3d-null`** — quando faltava a chave de versão, o nome do
  componente virava inexistente e nada extraía → falha no launch. Agora usa a
  versão padrão como fallback.
- **Guarda pré-launch do `__libc_init`** (wine bionic × glibc) + matriz de
  compatibilidade Wine↔Box64 com auto-ajuste e aviso amigável.
- **SIGKILL (137) em segundo plano** classificado separadamente de crash do jogo.
- Diversos bugs de auditoria: vazamento de serviços, flags de import, crash de
  chave duplicada no hub, path traversal (Amazon), crash do Epic, leituras OOB
  nativas (PutImage/BITMAP), corrida de `StateFlow`, etc.

## 2. Desempenho

- **Estudo de CPU (100%) / GPU (0%)** → preset **Box64 "Máximo"** (`MAX_PERFORMANCE`)
  para jogos limitados por CPU (SAFEFLAGS=0, NATIVEFLAGS=1, BIGBLOCK=3,
  BLEEDING_EDGE=1, FORWARD=1024).
- **`sfCompatMode` desligado por padrão** — corrige a grande regressão recente de
  FPS (era uma conversão BGRA→RGBA por frame).
- **Geração de quadros própria (Frame Gen)** — motor próprio, **sem DLL paga e sem
  Lossless Scaling**:
  - v1: interpolação temporal por blend.
  - v2: **extrapolação compensada por movimento** (block-matching em baixa
    resolução estima o movimento entre dois quadros reais e avança o quadro atual
    ao longo desse movimento → menos "fantasma"). Modos **Normal / Turbo / High /
    Máximo** na barra lateral do jogo.
- **Governador ADPF v1** (PerformanceHintManager + thermal headroom) — sugere cap
  de FPS antes do SoC throttlear.
- Variáveis de ambiente de performance do Box64 expandidas; DXVK com defaults
  saudáveis (threads do compilador, memória do device, frame-pacing); env vazias
  não são mais exportadas (MESA/WRAPPER).
- Melhorias portadas do fork **Winlator-Ludashi** (verificadas na fonte): taxa de
  atualização máxima, env vars da Turnip, cache de shader Mesa persistente,
  wakelock de sessão, toggle de semáforo DXVK.
- Hard-link do exe com DRM (evita cópia dupla) e otimizações de hot-path de input
  (sem alocação por evento).

## 3. Compatibilidade e camadas de tradução

- **Minimizador de camadas** — lê os imports PE do jogo (`PEInspector`) e escolhe a
  pilha DX mínima (`LayerMinimizer`): d3d12→vkd3d, d3d10/11→dxvk, d3d9→dxvk,
  d3d8→d8vk, opengl→wined3d/gl, ddraw→wined3d. Só sobrepõe o padrão quando tem
  **certeza**. (Correção posterior: `dxgi.dll` sozinho é ambíguo entre D3D10/11/12 →
  fica indefinido e deixa a detecção de rede escolher vkd3d, evitando classificar
  jogo D3D12 como dxvk.)
- Seletor de versão do Wine exibido para todas as variantes; detecção de `ntsync`
  em runtime; `WINEESYNC` respeitado no caminho proot.

## 4. Interface e experiência

- **Vídeo/imagem de papel de parede** animado atrás do app inteiro (menu "Layout"),
  reativo na hora ao trocar. Renderizado via **TextureView** (composita dentro da
  hierarquia) e as telas da biblioteca ficaram **transparentes** para o vídeo
  aparecer atrás da grade de jogos. Também disponível atrás da tela de login.
- **Aba "Dicas"** na barra lateral do jogo — histórico de FPS (média/mínimo +
  mini-gráfico), estado térmico e uma **recomendação com botão "Aplicar"** (FPS
  baixo → preset Máximo; esquentando → cap de FPS; travamentos → Compatibilidade).
- **"Perfil Máximo"** — toggle de desempenho na barra lateral do jogo.
- **Login removido** — abre direto na biblioteca (offline).
- **Steam Big Picture** — toggle experimental para abrir a UI de gamepad da Steam.
- **Capas de jogo** — corrige erro "imagem não suportada" (decodifica HEIC/HEIF via
  ImageDecoder), gerenciador de capas (trocar/restaurar), placement, refresh ao
  vivo, cache busting, seletor de galeria.
- **Biblioteca em tempo real** — detecta jogos novos sem refresh manual.
- Rotação em jogo segue o aparelho; item "Layout" no menu do sistema; "Low Graphics
  Mode" (upscaling FSR); **"O que há de novo"** atualizado com o delta do fork.
- Todas as strings novas localizadas em **pt-BR** (e EN).

## 5. Downloads e componentes

- **Múltiplas fontes de manifesto** — o catálogo de Box64/Wine/driver/DXVK/VKD3D
  agora mistura uma base embutida + o upstream + uma **URL de manifesto
  personalizada** (configurável no Gerenciador de Conteúdos). Sem URLs falsas: o
  mecanismo permite apontar para repositórios reais de componentes.
- Botão "Baixar todos os componentes" nas configurações de emulação.
- Box64 DynaCache no seletor de env vars; expostas `VKD3D_CONFIG`, `NOARCH`, `FSR`;
  Large Address Aware por padrão; re-extração de DXVK/drivers condicional.

## 6. LAN / multiplayer

- Salas LAN com **link de convite compartilhável** (copiar no host, colar para
  entrar); **chat overlay em jogo**; `LanRoomManager` endurecido (concorrência +
  segurança, multicast-lock, socket órfão, corrida de chat).

## 7. Loja / Game Hub

- Núcleo de loja/biblioteca agnóstico de fonte; adaptadores reais de loja; aba
  única **"Loja"**; formulário de loja customizada por JSON; favoritos
  persistentes, ordenação, contagem de resultados, tela de detalhe do jogo
  (instalar/jogar/configurar), last-played no launch; fan-out concorrente real.

## 8. Segurança, robustez e infraestrutura

- Escrita atômica do arquivo `.container` (+ testes); X server, downloaders e event
  bus endurecidos contra entrada malformada; `@Volatile` em flags de serviço;
  guardas de progresso `NaN`; PID via `Process.pid()` em vez de reflexão; debug
  verboso do Steam apenas em builds debug; flags de página de 16 KB no build nativo.
- **CI** — publica APK debug como Release baixável; mantém o Release rolando (links
  não dão 404); auto-limpa o storage do Actions (mantém os últimos 4 runs); job de
  Release separado para os artefatos ficarem rápidos.
- Documentos de engenharia em `docs/` (análise do XoDos, relatório de engenharia,
  design do minimizador de camadas, análise de servidor).

---

## Como testar / verificar

- **Crash / instalação bionic:** ao abrir um jogo, ele instala os componentes
  **uma vez**; na **segunda** abertura **não deve** aparecer "instalando bionic"
  de novo. Se uma instalação falhar (rede), aparece erro em vez de crashar.
- **Vídeo de fundo:** menu "Layout" → escolher vídeo → deve aparecer atrás da grade
  de jogos na hora.
- **Preset Box64:** escolher "Desempenho"/"Máximo" → reabrir as configs → o preset
  escolhido continua selecionado (não volta para "Estabilidade").
- **Frame Gen / Dicas / Perfil Máximo:** barra lateral dentro do jogo.
- **Captura de crash nativo (no PC com o aparelho ligado):**
  ```
  adb logcat -c -b all
  adb logcat -b all -v threadtime > mk11_crash.txt   # abra o jogo até crashar; Ctrl+C
  adb bugreport mk11_bugreport                        # contém o tombstone
  ```
  Procurar por `Fatal signal`, `SIGSEGV`, `#00 pc`, `backtrace`, `box64`, `wine`.

## Pendências conhecidas (não feitas de propósito, por risco > benefício)

- Corrida do marcador de crash na telemetria (pode contar um crash a mais em
  start/stop muito rápidos) — baixa severidade.
- Guarda de versão mínima do Box64 quando o campo está vazio — evitar forçar versão
  em todos os containers "default".
- Executor compartilhado no `ProcessHelper` (limpeza interna).
- Anti-cheat kernel (EAC/BattlEye) **não funciona** em emulação Android — limitação
  real, não é bug.

---

*Gerado como parte da revisão abrangente deste fork. Cada item acima corresponde a
um ou mais commits na branch de desenvolvimento.*
