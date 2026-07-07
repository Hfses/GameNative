# Relatório de Revisão Completa do GameNative — Julho/2026

Revisão profunda do código-fonte (768 arquivos Kotlin/Java + código nativo C/C++), com verificação adversarial dos achados, pesquisa comparativa com os principais projetos do ecossistema (Winlator e forks, Ludashi, Cassia, Mobox/Termux-X11, Proton/GE, Wine, Box64, FEX-Emu, DXVK, VKD3D-Proton, Mesa/Turnip/Zink, ANGLE) e propostas inéditas com estudo de viabilidade.

**Metodologia**: 8 análises independentes — 4 varreduras de código por subsistema (containers/Wine, stack gráfica, emulação/processos/arquitetura, input/áudio/fixes/memória/startup), 1 verificação adversarial dos bugs (cada achado foi confirmado ou refutado lendo o código exato), 2 pesquisas externas (renderização/GPU e CPU/Wine/containers) e 1 análise de cobertura de testes. Nada abaixo entrou como "confirmado" sem verificação direta no fonte.

---

## 1. Resposta: o seletor de versão do Wine em "Editar Container"

**Diagnóstico direto às suas perguntas:**

| Pergunta | Resposta |
|---|---|
| É um bug? | Não é bug de runtime — é **ocultação condicional por design + funcionalidade incompleta** |
| A funcionalidade está incompleta? | **Sim**, para a variante glibc |
| Existe mas não está sendo exibida? | **Sim** — e em local não-intuitivo |
| Alguma variável impede sua exibição? | **Sim**: `containerVariant` e o flag de build `MODERN_ANDROID` |

**Detalhes:**

1. O seletor **existe**, mas não fica na aba "Wine" — fica na aba **General** (`GeneralTab.kt:232-258`). A aba "Wine" (`WineTab.kt`), apesar do nome, só contém opções de GPU/renderer (armadilha de UX herdada do fork).
2. Ele só era renderizado quando `containerVariant == BIONIC`: `if (config.containerVariant.equals(Container.BIONIC, ignoreCase = true))`. Para containers **glibc, nenhum seletor era renderizado**.
3. A lista para glibc (`glibcWineOptions`) era **calculada mas nunca ligada a nenhum widget** (`ContainerConfigDialog.kt:438-440` → `ContainerConfigState.kt:115`, zero leituras) — código morto que confirma a funcionalidade inacabada.
4. Em builds `MODERN_ANDROID=true` (flavors modern), a variante glibc é **removida da lista de variantes** (`ContainerConfigDialog.kt:207-214`), então só existe Bionic e o seletor aparece — mas apenas na aba General.
5. As opções vêm de `res/values/arrays.xml` (`bionic_wine_entries`: proton-9.0-arm64ec, proton-9.0-x86_64; `glibc_wine_entries`: wine-9.2-x86_64) mais versões instaladas via `ContentsManager`/manifesto remoto.

**✅ CORRIGIDO NESTA ENTREGA** (commit `60de355`): o dropdown agora é **sempre visível**, com opções escolhidas pela variante (bionic → `bionicWineOptions`, glibc → `glibcWineOptions`), reutilizando o fluxo de instalação por manifesto. Recomendação futura de UX: renomear a aba "Wine" para "Direct3D/GPU" ou mover o seletor para ela.

---

## 2. Bugs encontrados (verificados adversarialmente)

### 2.1 Corrigidos nesta entrega

| # | Bug | Arquivo/função | Cenário de falha | Correção |
|---|---|---|---|---|
| B1 | **Máscara de afinidade com sign-extension** | `XServerScreen.kt:2019-2020` (`.toShort().toInt()`) | Core 15 na máscara → `0x8000.toShort()` = −32768 → `.toInt()` = `0xFFFF8000` → bits 15–31 ligados espuriamente; cores 16+ descartados | Removido o truncamento; protocolo (`WinHandler.setProcessAffinity` → `putInt`) já era de 32 bits |
| B2 | **`Math.pow` satura no core 31** | `ProcessHelper.getAffinityMask` (4 overloads) | `(int)Math.pow(2,31)` = `Integer.MAX_VALUE` → liga cores 0–30 em vez do 31 | Substituído por `1 << i` com guarda 0–31 |
| B3 | **`duplicateContainer` perde ~40 campos** | `ContainerManager.java:192-231` | Duplicar container perdia `containerVariant`, `emulator`, `fexcoreVersion`, renderer, input mappings, `executablePath`… (copiava 21 de ~60 campos) | Round-trip JSON do `.container` já copiado no diretório; só o nome é sobrescrito |
| B4 | **Corrupção de presets Box64 custom** | `Box86_64PresetManager.java` | Formato `id\|name\|env,` sem escaping; env com vírgula (ex.: `ZINK_DEBUG=compact,deck_emu`, presente nos defaults!) corrompia todos os presets na releitura (`ArrayIndexOutOfBoundsException`/desalinhamento) | Armazenamento em JSON com migração automática do formato legado e descarte de fragmentos corrompidos |
| B5 | **Marcadores de driver gravados antes da extração** | `XServerScreen.kt` (bloco `changed` dos drivers gráficos) | O bloco deletava as `.so` antigas e gravava extra+sentinel **antes** de `extractGraphicsDriverComponent`; processo morto no intervalo = container sem driver com marcador "ok" → jogos "param de funcionar aleatoriamente" (a causa provável do workaround `ALWAYS_REEXTRACT`) | Marcadores movidos para depois da extração bem-sucedida |
| B6 | **`ALWAYS_REEXTRACT=true`** | `XServerScreen.kt:221` + 3 sites | Re-extração de ~30MB+ (zstd) de DXVK/VKD3D/drivers em TODO launch — latência de abertura alta em todos os jogos | Desligado (com B5 corrigido); guarda de sanidade adicional: dxgi/d3d11/d3d9.dll ausente/zerada força re-extração |
| B7 | **esync forçado a 0 no caminho proot** | `GuestProgramLauncherComponent.java:280` | `WINEESYNC=0` gravado após configurar bind de `/dev/shm` pelo valor do usuário — esync silenciosamente desligado no caminho glibc/proot | Respeita o valor do usuário |
| B8 | **Toggle de baixa latência de áudio inócuo** | `PulseAudioComponent.java:170` + `Container.java:39` | Toggle só adicionava `low_latency=true` ao módulo AAudio; `PULSE_LATENCY_MSEC=144` (default de todo container) continuava dominando a latência | Com o toggle ativo, 144→60ms (valores customizados são respeitados) |
| B9 | **Dropdowns duplicados na aba Wine** | `WineTab.kt:20-44` | "Renderer" e "GPU Name" ligados ao MESMO índice/lista, brigando pelo estado; um gravava `gpuName`+`videoPciDeviceID`, o outro só `videoPciDeviceID` | Fundidos em um único "GPU Name" com comportamento superset |
| B10 | **`PRINT_DEBUG=true` em release** | `ProcessHelper.java:28` (`// FIXME`) | `System.out.println` para CADA linha de stdout/stderr dos processos guest em produção (callbacks registrados incondicionalmente em `XServerScreen.kt:1527/3230`) | `PRINT_DEBUG = BuildConfig.DEBUG` |
| B11 | **Env de debug do Steam em produção** | `BionicProgramLauncherComponent.java:552-559` | `STEAM_LOG_LEVEL=10`, `IPCLOGGING=1`, networking verbose etc. sempre ativos → CPU e I/O de log durante gameplay | Gated em `BuildConfig.DEBUG` |
| B12 | **PID via reflection** | `ProcessHelper.java:307-310` | `getDeclaredField("pid")` — frágil a mudanças de Android/ART (também em `SteamBootstrap.kt:93`) | `Process.pid()` em API 33+, reflection como fallback; helper único reutilizado |
| B13 | **Libs nativas sem alinhamento 16KB** | `cpp/{virglrenderer,patchelf,proot}/CMakeLists.txt` | Dispositivos Android 15+ com página de 16KB rejeitam `.so` com LOAD segments alinhados a 4KB → crash no load | `-Wl,-z,max-page-size=16384` adicionado (demais alvos já tinham) |

### 2.2 Bugs/riscos conhecidos NÃO corrigidos nesta entrega (com justificativa)

| # | Problema | Local | Por que não mexi | Recomendação |
|---|---|---|---|---|
| R1 | **Shadowing de campos** em `BionicProgramLauncherComponent` (redeclara `pid`, `lock`, `envVars`, `wow64Mode`… do pai) | `BionicProgramLauncherComponent.java:59-73` | Verificação adversarial **não encontrou caminho executável com falha ativa** (todos os métodos relevantes são sobrescritos; despacho virtual acerta) — é dívida técnica frágil, não bug ativo. Refatorar a hierarquia exige teste em dispositivo | Unificar os 3 launchers numa hierarquia sem duplicação de estado (alto valor, risco médio) |
| R2 | **Double-send de gamepad** (UDP + shm por evento) | `WinHandler.java:938-939, 1001-1004` | Os dois transportes podem ter consumidores distintos no guest (winhandler.exe vs evshim/XInput); remover um sem testar em dispositivo arrisca quebrar controller em parte dos jogos | Instrumentar e remover o caminho UDP quando shm estiver ativo |
| R3 | **`MAX_PLAYERS=2`** apesar do evshim suportar 4 | `WinHandler.java:56-58` | Mudança de comportamento visível; requer teste com 3-4 controles físicos | Elevar para 4 após validação |
| R4 | **Fila de ações do WinHandler sem back-pressure** + trabalho pesado sob lock | `WinHandler.java:63, 679-683` | Refatoração de concorrência sem testes de integração é arriscada | Coalescer eventos MOVE; mover parsing para fora do `synchronized` |
| R5 | **Buffer ALSA cresce em underrun e nunca encolhe** | `ALSAClient.java:193-202` | Comportamento de áudio audível; requer teste em dispositivo | Decaimento gradual após N s sem underrun |
| R6 | **Leak assumido de Views em estáticos** ("leak that memory baby") | `PluviaApp.kt:201-207` | Arquitetural — o ciclo de vida do XServer depende disso hoje; consertar exige redesenho do ownership | Mover ownership para o escopo da Activity/ViewModel do XServer |
| R7 | **`.container` sem escrita atômica** — `saveData()` grava direto; kill no meio corrompe a config (o `loadContainers` já pula containers corrompidos) | `Container.java:674` | Simples, mas prefiro entregar junto com testes de `Container` | Escrever em `.container.tmp` + rename atômico |
| R8 | **Fonte nativo órfão**: `cpp/asurfacerenderer/drawable.c` compila para `libdrawable.so` que **nenhuma classe carrega** — o app usa o prebuilt `libwinlator_11.so`, cujas assinaturas JNI (sem `needsSwapRB`) nem batem com esse fonte | `cpp/asurfacerenderer/drawable.c` vs `Drawable.java:28-51` | Remover código pode ser destrutivo se houver plano de migração em curso | Ou migrar o `Drawable` para a lib compilada do fonte, ou remover o fonte órfão — hoje ele engana qualquer auditoria (2 dos meus próprios achados iniciais caíram nisso) |
| R9 | **Sem verificação de espaço no serviço de download** (só na UI) | `SteamService.kt` (grep vazio para `usableSpace`) | Mudança em caminho crítico de download | Checar espaço no início do download e a cada N% |
| R10 | **`runBlocking` no boot** (migração GOG/Amazon no `Application.onCreate`; consulta GOG DB no boot do jogo) | `PluviaApp.kt:164`, `GameFixesRegistry.kt:98` | Mover migração para async muda ordem de inicialização; precisa validação | Gate rápido ("já migrei?") antes do `runBlocking`; mover fixes para o pipeline async de boot |

---

## 3. Gargalos de desempenho

### 3.1 CPU
- **Logging por linha de processo em release** (B10/B11 — corrigidos).
- **Alocações por evento de input**: `XForm.transformPoint` alocava `float[2]` a cada movimento de mouse/touch/stylus no UI thread — 19 call sites em `TouchpadView.java` (**corrigido**: buffers reutilizáveis).
- **Alocações por atualização de cena**: `ASurfaceRenderer.pushRenderList` alocava `HashSet` + `WindowGeometry` por janela a cada evento de janela (**corrigido**: scratch reutilizado). Nota do verificador: ocorre por evento de janela, não por frame — impacto menor do que parecia, mas gratuito de corrigir.
- **Executors ad-hoc**: `Executors.newSingleThreadExecutor()` criado por chamada em `ProcessHelper`/`ContainerManager` — threads e alocação desnecessárias (futuro: pool compartilhado).
- **Polls com `delay` fixo** no exit de processos e no quick-menu (`XServerScreen.kt:855-891, 1033-1040`) e `delay(1200)` fixo de splash.

### 3.2 GPU / caminho de apresentação (o fluxo, verificado)
O caminho ativo padrão (ASurfaceRenderer, `sfCompatMode=true` default e configurável em `GraphicsTab.kt:404`):
1. Wine desenha **BGRA** no mapeamento CPU de um `AHardwareBuffer` (`AHBImage.virtualData`) — sem swap (o fonte com swap CPU é o órfão R8).
2. `pushCpuImageToNative` → **memcpy** do frame inteiro para 1 de 3 AHBs do swapchain (`ahbimage.c:211/214`).
3. Com `sfCompatMode=true`: **uma conversão GPU BGRA→RGBA** (`blit_converter.cpp:313`) para um pool de buffers convertidos; com `false`: o AHB vai **direto** ao `SurfaceControl` (zero-copy no compositor).
4. `ASurfaceTransaction_setBuffer` → SurfaceFlinger compõe (overlay).

**Custos evitáveis por frame**: o memcpy do passo 2 e a passada GPU do passo 3. O `VulkanRenderer` alternativo já tem fast-path de scanout zero-copy (AHB do jogo direto no SurfaceControl, X pausado — `VulkanRenderer.java:496-507`), mas **qualquer efeito de tela o desativa**. Ver melhorias G1–G3 e a proposta inédita P2.

### 3.3 Memória
- Leak assumido de Views (R6); caches em `SteamService` sem evicção; `auxBuffer` de áudio realocado por `setSharedBuffer`; cópia dupla de áudio no caminho glibc (`ALSARequestHandler.java:115-124`).

### 3.4 I/O / tempo de carregamento
- **B6 (corrigido)** era o maior: dezenas de MB descomprimidos por launch.
- Duas cópias completas do exe por launch em jogos com DRM desempacotado (`XServerScreen.kt:4356-4358`).
- Extrações de tar em sequência no boot do jogo (Wine system files → drivers → DLLs de input) — paralelizáveis.

---

## 4. Problemas de compatibilidade

| Tema | Estado | Ação |
|---|---|---|
| **16KB pages (Android 15+)** | Maioria dos alvos OK; 4 libs sem flag (**corrigido**, B13). **Restante**: os prebuilts em `jniLibs/` (`libwinlator_11.so`, `libpulseaudio.so`, etc.) precisam ser reconstruídos com alinhamento 16KB — não é possível corrigir sem os fontes/pipeline deles. `useLegacyPackaging=true` (`build.gradle.kts:222`) merece revisão | Auditar prebuilts com `llvm-readelf -l` no CI |
| **esync apenas; sem fsync/ntsync** | esync=1 default (ok no bionic). ntsync exige kernel 6.14+ com driver habilitado — **inviável na maioria dos Androids sem root hoje**; fsync exige patch futex fora da árvore | Detectar `/dev/ntsync` em runtime e habilitar quando existir (Wine 11+); ver melhoria W2 |
| **VKD3D 2.14.1 defasado** | VKD3D-Proton 3.x + `VK_EXT_descriptor_heap` (2026) é a virada para D3D12 em Adreno | Empacotar VKD3D-Proton 3.x como opção experimental (ver W4) |
| **Media Foundation** | Sem pacote MF completo → jogos com cutscenes MF travam/tela preta. Proton/GE resolvem com patches+codecs; Winlator/Mobox distribuem pacotes wmf/mf | Adicionar wincomponent "mf" opcional (ver W3) |
| **Jogos antigos (D3D8/DDraw)** | Bem servido: d8vk + cnc-ddraw 6.6 embutidos | Manter |
| **GameFixesRegistry estático** | 32 fixes compilados no app; protonfixes é extensível/atualizável sem release | Mover fixes para dados versionados baixáveis (ver C3) |
| **URL de conteúdo de terceiros** | `ContentsManager.REMOTE_PROFILES_URL` aponta para raw GitHub de `longjunyu2/winlator` — dependência externa sem controle/assinatura | Espelhar sob domínio próprio + checksum |

---

## 5. Arquitetura: código morto, duplicado e simplificações

- **Código morto confirmado**: caminho proot/Glibc (~640 LOC: `GuestProgramLauncherComponent.exec` + `GlibcProgramLauncherComponent`) inalcançável nos builds modern (variante GLIBC filtrada da UI; `WineProtonManagerDialog.kt:481` diz "not supporting GLIBC but we will in future"); `execShellCommand()` stub que retorna `""`; `glibcWineOptions` (**agora ligado**, deixou de ser morto); blocos comentados grandes em `GuestProgramLauncherComponent.java:207-234` e `XServerScreen.kt`; **fonte nativo órfão** (R8); `img.png` de 1,7MB na raiz do repo.
- **Duplicação**: 3 launchers com campos/métodos copiados (R1); 2 classes `ProcessInfo` (`ProcessHelper.ProcessInfo` vs `winhandler/ProcessInfo.java`); lógica duplicada de GET_GAMEPAD em `WinHandler.handleRequest:536-584` (com um `if (!enabled) {}` vazio).
- **God-files**: `XServerScreen.kt` (5.456 linhas — uma "tela" Compose que faz todo o bring-up do runtime: env, drivers, DXVK, afinidade, wine command), `SteamService.kt` (4.488, singleton estático global), `WorkshopManager.kt` (4.090), `PluviaMain.kt` (2.357). **Recomendação estrutural nº 1**: extrair de `XServerScreen` um `GameRuntimeOrchestrator` puro (sem Compose) testável.
- **DI**: Hilt presente mas quase não usado (2 módulos); o acoplamento real é via singletons estáticos (`SteamService.instance`, `PluviaApp.*`, `PrefManager`).

---

## 6. Melhorias por área (com os 7 campos pedidos)

Formato: **Arquivo → Função · Por quê · Impacto compat · Impacto perf · Risco · Exemplo**.

### G. GPU / Renderização

**G1 — Zero-copy com efeitos: aplicar upscale/sharpening no caminho de scanout Vulkan**
- `VulkanRenderer.java` (`effectsRequireCompositor`, `nativeScanoutSetBuffer`) + `cpp/winlator/VulkanRendererScanout.cpp`
- Hoje qualquer efeito (FSR1/FXAA/CRT) desativa o fast-path e reativa o compositor completo. Forks (Ludashi DAC) aplicam FSR/DLS como passada única no próprio present.
- Compat: neutro. Perf: recupera o caminho mais rápido mesmo com upscaling — ganho típico de 1 blit + 1 troca de contexto por frame; menos latência. Risco: médio (shaders novos no scanout).
- Exemplo: no scanout, em vez de `setBuffer(ahbJogo)`, renderizar 1 quad `ahbJogo→ahbScanout` com o shader FSR1 (EASU+RCAS) e `setBuffer(ahbScanout)` — mantendo o X pausado.

**G2 — Eliminar o memcpy do caminho CPU do ASurfaceRenderer**
- `AHBImage.java`/`ahbimage.c` (`copyHardwareBuffer`) + `Drawable`
- O X escreve num buffer e o frame inteiro é copiado para o swapchain triplo. Escrever diretamente em N AHBs rotativos (o drawable aponta para o AHB "de trás") elimina a cópia.
- Compat: neutro. Perf: −1 passada de memória por frame (relevante em 1080p+ CPU-bound). Risco: médio-alto (sincronização de fences com o X).
- Exemplo: `Drawable.data` vira uma janela sobre `ahb[writeIndex]`; `forceUpdate()` troca o índice após o fence de release do compositor.

**G3 — `sfCompatMode=false` por padrão em devices que suportam BGRA em overlay**
- `Container.java:87` (default) + detecção em `ASurfaceRendererContext.cpp`
- O modo compat existe para GPUs/compositores que não aceitam o AHB BGRA direto; onde funciona, elimina a conversão GPU por frame.
- Compat: precisa denylist por GPU. Perf: −1 blit GPU/frame. Risco: médio (dispositivos Mali antigos).
- Exemplo: probe na 1ª execução (compor 1 frame de teste direto; fallback para compat se `ASurfaceTransaction` reportar erro) + persistir resultado por GPU.

**G4 — Frame pacing com Swappy (AGDK) no XServerView**
- `XServerView.java`/renderers; nenhuma API de pacing é usada hoje (verificado: zero refs a Swappy/ADPF)
- Mailbox reduz latência mas não alinha apresentação ao vsync → microstutter com FPS ≈ refresh.
- Compat: neutro. Perf: frametimes mais estáveis (menos jank perceptível). Risco: baixo (biblioteca madura).
- Exemplo: `SwappyVk_setSwapIntervalNS(display, 16_666_666)` no caminho Vulkan; no ASurface, usar `ASurfaceTransaction_setDesiredPresentTime` calculado por Choreographer.

**G5 — `VkPipelineCache` persistente no Vortek**
- `VortekRendererComponent.java` + servidor Vortek nativo
- O wrapper cria devices Vulkan para o guest; serializar o pipeline cache por (GPU, driverUUID) entre sessões reduz stutter de recompilação no driver.
- Compat: neutro (cache é opaco/versionado pelo driver). Perf: menos hitches na 1ª hora de cada jogo. Risco: baixo.
- Exemplo: `vkGetPipelineCacheData` no destroy do contexto → `files/pipeline_cache/<driverUUID>/<jogo>.bin`; alimentar em `vkCreatePipelineCache` no boot.

### W. Wine / Proton / DXVK / VKD3D

**W1 — Atualizar DXVK default e adotar GPL quando o driver expõe**
- `DefaultVersion.java` (DXVK 2.6.1-gplasync) + `DXVKHelper.java`
- DXVK 2.7+ removeu o state cache legado em favor de `VK_EXT_graphics_pipeline_library`; em Turnip recente, GPL nativo é melhor que gplasync (compila no load, não no draw). Manter gplasync como fallback p/ drivers sem GPL (Mali/Adreno antigos).
- Compat: melhora em títulos D3D11 modernos. Perf: menos stutter de compilação. Risco: médio (regressões pontuais → manter seleção por jogo).
- Exemplo: probe `VK_EXT_graphics_pipeline_library` via `GPUHelper`; default dxvk-2.7.x se presente, senão 2.6.1-gplasync.

**W2 — Detecção de ntsync em runtime**
- `XServerScreen.kt` (montagem de env) + launcher
- Kernels Android 6.14+ (GKI) começarão a expor `/dev/ntsync`; Wine/Proton 11 usam. Ganhos de 20-40% em jogos wineserver-bound (dados desktop; menores no ARM, ainda relevantes).
- Compat: alta (menos deadlocks de sync que esync). Perf: alta onde disponível. Risco: baixo (feature-gated).
- Exemplo: `if (File("/dev/ntsync").exists() && wineSupportsNtsync) envVars.put("WINENTSYNC","1") else WINEESYNC=1`.

**W3 — Pacote Media Foundation opcional (wincomponent)**
- `assets/wincomponents/` + `extractWinComponentFiles` (`XServerScreen.kt:4506`)
- Cutscenes MF são a maior causa de "tela preta no vídeo de abertura". Winlator/Mobox distribuem mf/wmf; Proton usa patches+codecs.
- Compat: destrava dezenas de títulos. Perf: neutro. Risco: médio (licenciamento de codecs — preferir build do mf do Proton/open-source, sem DLLs proprietárias).
- Exemplo: novo toggle "Media Foundation" em Win Components → extrai mf.tzst no prefixo + `WINEDLLOVERRIDES=mfplat,mf,mfreadwrite=n,b` + registro dos CLSIDs.

**W4 — VKD3D-Proton 3.x experimental**
- `assets/dxwrapper/` + `DefaultVersion.VKD3D`
- 2.14.1 está atrás da série 3.x (descriptor heaps → caminho mais leve p/ Adreno).
- Compat: melhora D3D12 (hoje o ponto mais fraco). Perf: alta em D3D12. Risco: alto (exige Vulkan 1.3 + extensões; gate por driver).
- Exemplo: opção "vkd3d-3.x (experimental)" no dropdown DX wrapper, visível só quando `vkMaxVersion>=1.3` e descriptor indexing completo.

**W5 — Prefixo Wine: escrita atômica e template pré-aquecido**
- `Container.saveData` (R7) + `ContainerManager.extractContainerPatternFile`
- Além do rename atômico, gerar o pattern do prefixo já com os tweaks de registro aplicados offline (via `WineRegistryEditor` no build do pattern, não no 1º boot) corta o 1º boot.
- Compat: neutro. Perf: 1º boot de container mais rápido. Risco: baixo.

### C. Containers / CPU / Box64

**C1 — Box64 DynaCache (0.4.x)**
- `EnvVarInfo.kt` (sugestões) + `BionicProgramLauncherComponent.addBox64EnvVars`
- Persistir blocos DynaRec entre sessões reduz stutter de recompilação e tempo de load em execuções repetidas.
- Compat: neutro (invalidado por versão do box64/binário). Perf: média em jogos grandes. Risco: baixo-médio (feature nova do box64; expor como toggle).
- Exemplo: `BOX64_DYNACACHE=1`, `BOX64_DYNACACHE_LIMIT=2048` com dir em `~/.cache/box64` do container.

**C2 — Presets por engine via rcfile em vez de env global**
- `assets/box86_64/*.box64rc` + `RCManager`
- O box64 aplica seções `[jogo.exe]`; hoje os presets são globais por container. Mapear engine→preset (Unity: `BIGBLOCK=0,STRONGMEM=1`; UE4: perfil próprio) por executável evita pagar STRONGMEM global.
- Compat: alta. Perf: média. Risco: baixo.
- Exemplo: gerar `[<exe>]`-sections no box64rc do container a partir do `GameFixesRegistry`/telemetria.

**C3 — GameFixes como dados baixáveis (estilo protonfixes)**
- `GameFixesRegistry.kt` (32 fixes hardcoded)
- Fixes compilados exigem release do app para cada jogo novo. Um JSON versionado (env/registry/args/dll-overrides por gameId) baixado como os `container_pattern` já são, cobre a cauda longa.
- Compat: alta (velocidade de resposta da comunidade). Perf: neutro. Risco: baixo-médio (validar schema; sem código executável remoto).
- Exemplo: `gamefixes.json` assinado no mesmo pipeline dos manifests; `GameFixesRegistry` vira leitor de dados com fallback aos fixes embutidos.

**C4 — Afinidade + ADPF (ver proposta P3)** e **C5 — pool compartilhado de executors** (`ProcessHelper`).

### A. Áudio / Input
- **A1**: decaimento do buffer ALSA (R5). **A2**: eliminar cópia dupla glibc (`ALSARequestHandler`). **A3**: coalescing de mouse MOVE + fila limitada no `WinHandler` (R4). **A4**: 4 controles (R3).

### S. Inicialização / Loading
- **S1**: paralelizar extrações do boot do jogo (`setupWineSystemFiles` ∥ `extractGraphicsDriverFiles` ∥ input DLLs — hoje sequenciais em runBlocking).
- **S2**: mover `System.load(libjpeg)` e inits do `PluviaApp.onCreate` para async com gate (R10).
- **S3**: hard-link/reflink em vez de `Files.copy` duplo do exe DRM (`XServerScreen.kt:4356`).
- **S4**: `installSize` incremental em vez de `getFolderSize` recursivo (`ContainerStorageManager.kt:322`).

---

## 7. Comparativo com outros projetos — o que falta no GameNative

| Técnica | Quem tem | Estado no GameNative |
|---|---|---|
| Present zero-copy AHB→SurfaceControl | Ludashi-Plus (DAC), Termux-X11/lorie | **Tem a base** (ASurfaceRenderer/VulkanRenderer scanout), mas compat-mode + memcpy + efeitos desativam (G1-G3) |
| Driver Vulkan cliente-servidor (glibc↔bionic) | Winlator (Vortek) | Tem (Vortek 2.1); falta pipeline cache persistente (G5) |
| WoW64 novo + arm64ec (FEX) | Winlator bionic, Cassia | Tem (wowbox64/FEXCore arm64ec) — em paridade |
| DynaCache do Box64 | Box64 0.4.x upstream (Mobox pode ativar) | **Não usa** (C1) |
| Frame pacing (Swappy/ADPF) | Jogos nativos Android; nenhum emulador usa bem | **Não tem** (G4/P3 — oportunidade de liderança) |
| Fossilize-style pre-caching | Steam/Proton desktop | **Não tem** (P1 — proposta inédita adaptada) |
| protonfixes extensível | Proton/GE | Parcial (32 fixes estáticos; C3) |
| Media Foundation packs | Proton-GE, Mobox, forks Winlator | **Não tem** (W3) |
| ntsync | Proton/Wine 11 em kernels 6.14+ | Não aplicável hoje; preparar detecção (W2) |
| FSR1/FSHack no fullscreen | Proton (WINE_FULLSCREEN_FSR), forks | Tem efeitos FSR no compositor GL legado; falta no caminho Vulkan de scanout (G1) |
| LSFG frame generation | Poucos forks | **Tem** (à frente da maioria) |
| Cloud saves multi-loja + configs automáticas | — | **Tem e é diferencial** (Pluvia + recommendations) |

---

## 8. Propostas inéditas (estudo de viabilidade)

### P1 — Warm Cache Packs: cache comunitário multi-camada pré-aquecido durante o download do jogo ⭐

**Problema**: o stutter e o tempo do primeiro jogo vêm de **três caches frios empilhados**: (1) tradução x86→ARM64 (Box64), (2) pipelines Vulkan (DXVK/driver), (3) shaders Mesa/Turnip. Cada projeto ataca um; ninguém trata os três como um artefato distribuível.

**Por que ninguém fez**: exige controlar o canal de distribuição do jogo (para saber O QUE pré-aquecer e QUANDO) e ter telemetria de população (para saber DE ONDE colher). Winlator/Mobox não têm nem um nem outro. O GameNative tem os dois: é cliente Steam/Epic/GOG (o download do jogo demora minutos — janela de pré-aquecimento de graça) e já coleta config+FPS por sessão (PostHog) e aplica "known configs".

**Funcionamento interno**:
1. **Colheita**: ao fim de uma sessão com bom desempenho, o app empacota `~/.cache/box64` (DynaCache, C1), o `VkPipelineCache` serializado do Vortek (G5) e o cache Mesa do container; envia (opt-in) com a chave `(appId, buildId do jogo, GPU, driverUUID, versão box64, versão wrapper)`.
2. **Curadoria no backend**: dedup por chave, validação de tamanho/formato, ranking por sessões bem-sucedidas — mesmo pipeline conceitual do `recommendations.json` atual.
3. **Consumo**: `DownloadsViewModel`/`SteamService.downloadApp` consulta o índice ao iniciar o download; baixa o pack (tipicamente 5-50MB) em paralelo e o instala no container **antes do primeiro launch**.
- **Módulos**: `SteamService.kt` (hook de download), novo `WarmCacheManager.kt`, `VortekRendererComponent` (serialização), `BionicProgramLauncherComponent` (env DynaCache), backend (mesma infra dos manifests).
- **Algoritmos**: chave composta com match exato para pipeline cache (driverUUID) e match por binário (hash do exe) para DynaCache; LRU local com limite (ex.: 2GB); versionamento por schema.
- **APIs**: `vkGetPipelineCacheData`/`vkCreatePipelineCache(initialData)`; formato DynaCache do box64 0.4.x; zstd (já embutido).
- **Riscos**: (i) cache de pipeline é driver-específico — mitigado pela chave driverUUID (mismatch = ignorar, custo zero); (ii) DynaCache entre devices — a tradução é determinística por binário+versão do box64, mas validar CRC do bloco (o box64 já valida); (iii) privacidade — packs contêm só artefatos derivados do binário do jogo, não saves; (iv) tamanho no backend — quota por chave.
- **Ganho estimado**: primeira sessão com 60-90% menos hitches de compilação (efeito equivalente ao Fossilize no Steam Deck); loads iniciais 10-30% menores em jogos grandes; estabilidade percebida ("o jogo já chega rodando liso"). **Diferencial competitivo real**: nenhum emulador Android tem isso ponta a ponta.

### P2 — Formato negociado ponta-a-ponta + fences propagados (eliminar conversões e esperas entre camadas)

**Problema**: a cadeia DXVK→wrapper→X→compositor converte BGRA→RGBA por frame (GPU) e sincroniza com esperas de CPU (`poll(-1)` na exaustão do pool de conversão — `ASurfaceRendererContext.cpp:389-397`).

**Como funciona**: (1) o wrapper Vulkan (Vortek) anuncia `VK_FORMAT_R8G8B8A8_UNORM` como formato preferido do swapchain WSI, fazendo o DXVK mapear `DXGI_FORMAT_B8G8R8A8` para RGBA **uma vez na criação do swapchain** (swizzle no blit final que o DXVK já faz), zerando conversões por frame; (2) o release fence do `ASurfaceTransaction` é importado como `VkSemaphore` (`VK_KHR_external_semaphore_fd`/sync_fd) e passado ao guest como wait do próximo acquire — GPU espera GPU, CPU nunca bloqueia.
- **Módulos**: servidor Vortek (WSI), `ahbimage.c` (formato AHB `R8G8B8A8`), `ASurfaceRendererContext.cpp` (fences), `blit_converter.cpp` (remoção no caminho negociado).
- **APIs**: `AHardwareBuffer_Desc.format=R8G8B8A8`, `VK_ANDROID_external_memory_android_hardware_buffer`, `VK_KHR_external_semaphore_fd`, `ASurfaceTransaction_OnComplete` (release fence).
- **Riscos**: apps/GDI que desenham BGRA por CPU precisam do caminho antigo (manter dual-path); jogos que leem de volta o backbuffer esperam BGRA (raro; DXVK abstrai).
- **Ganho estimado**: −1 passada GPU/frame e −esperas de CPU no present → +3-8% FPS em GPU-bound e latência mais estável; menos consumo térmico.
- **Por que ninguém fez**: os forks copiam o pipeline do Winlator sem controlar o wrapper Vulkan; o GameNative controla os dois lados (Vortek + compositor).

### P3 — Governador ADPF: malha fechada térmica/desempenho para sessões longas

**Problema**: em 15-30 min de jogo o SoC estrangula e o FPS despenca; nenhum emulador Android usa as APIs de performance do sistema (verificado: zero refs no código).

**Como funciona**: um `PerformanceHintManager.Session` (API 31+) com os TIDs reais das threads quentes — coletados de `/proc/<pid>/task` dos processos wine/box64 (o app já os enumera em `ProcessHelper`) — reporta `reportActualWorkDuration(frametimeNs)` medido no present path (o `FrameRating` já mede); o OS eleva clocks quando o frametime estoura o alvo e economiza quando sobra. Em paralelo, `PowerManager.getThermalHeadroom(30s)` alimenta uma política preventiva: headroom < 0.85 → reduzir alvo de FPS (limiter existente) e/ou resolução interna (FSR do G1) **antes** do throttle disruptivo.
- **Módulos**: novo `PerformanceGovernor.kt`; hooks em `FrameRating`/renderers (frametime), `XServerScreen` (ciclo de vida), `QuickMenu` (toggle/telemetria HUD).
- **Algoritmos**: histerese com janelas de 5s; alvo dinâmico = min(fpsLimit, refresh); rampa de descida suave (60→45→30) e subida conservadora.
- **APIs**: `PerformanceHintManager`, `getThermalHeadroom`/`addThermalStatusListener` (API 30+), `Process.setThreadPriority` dentro do cpuset permitido.
- **Riscos**: baixo — APIs oficiais sem root; OEMs com implementações fracas de hint session (fallback: no-op); cuidado para não "premiar" threads erradas (filtrar por utilização via `/proc/<tid>/stat`).
- **Ganho estimado**: desempenho sustentado significativamente melhor em sessões longas (menos queda pós-15min), frametimes mais estáveis, menos calor — exatamente o item "Performance and thermals" do ROADMAP.md oficial.

### P4 — Auto-tuning distribuído de configs (multi-armed bandit sobre a telemetria existente)

**Problema**: os "known configs" são curados manualmente; a matriz (jogo × SoC × GPU × driver) é grande demais para curadoria humana.

**Como funciona**: para jogos sem config consolidada, o backend define um pequeno espaço de variações seguras (preset box64, DXVK vs versão, present mode, TU_DEBUG gmem/sysmem); cada instalação recebe um braço (Thompson sampling por tupla jogo+GPU); a recompensa é a métrica que o app **já coleta** (FPS médio, duração de sessão, crash). Convergência → o braço vencedor vira o "known config" automático daquela tupla.
- **Módulos**: `BestConfigService` (já existe e tem teste de 1075 linhas — ponto de integração natural), backend de recommendations.
- **Riscos**: éticos/UX (usuário como cobaia) → só variações seguras, opt-in, e nunca em jogos já com config boa; estatísticos (confundidores por device) → estratificar por GPU/driver.
- **Ganho**: compatibilidade e desempenho "out of the box" crescendo com a base instalada — efeito de rede que nenhum fork tem.

---

## 9. Cobertura de testes (análise + propostas)

**Estado**: ~60 suítes unitárias/17.500 linhas (JUnit4 + Robolectric 37 arquivos + MockK 22 + MockWebServer), CI roda `testLegacyDebugUnitTest`+`testModernDebugUnitTest` em PRs (`pluvia-pr-check.yml`). Instrumentados (androidTest) **não rodam em CI**. Boas práticas existentes que valem replicar: fixtures reais com pares `*.expected.json` (SteamAutoCloud), Robolectric+`TemporaryFolder` (ImageFs*), `mockkStatic` com `unmockkAll` (WineUtilsTest), MockWebServer (GOG/HLTB).

**Lacunas priorizadas por risco** (o que quebraria silenciosamente):

| Prioridade | Alvo | Viabilidade | Status |
|---|---|---|---|
| **Alta** | `Container.loadData/saveData/checkObsoleteOrMissingProperties` (migração de schema de containers de usuários!) | Robolectric + TemporaryFolder | proposto |
| **Alta** | `Box86_64PresetManager` (round-trip + migração legado) | JVM + MockK | ✅ **entregue nesta revisão** |
| **Alta** | `ContainerManager.createContainer/loadContainers/duplicateContainer` | Robolectric + TemporaryFolder | proposto |
| **Média-alta** | `ContainerUtils.toContainerData/applyToContainer` (mapeamento central modelo↔UI) | Robolectric + MockK | proposto |
| **Média** | `WineInfo.fromIdentifier` (regex de identificadores wine/proton) | JVM (regex isolada) | proposto |
| **Média** | `ProcessHelper.getAffinityMask` | JVM puro | ✅ **entregue nesta revisão** |
| **Média** | `KeyValueSet`, `TarCompressorUtils` (zstd já disponível como testImplementation) | JVM / Robolectric | proposto |
| **Baixa** | `com.winlator.xserver` (extrair partes puras: `Bitmask`, `Atom`) | JVM p/ partes puras | proposto |

Recomendações de infra: rodar androidTest em CI com emulador (matriz mínima API 29/36); adicionar teste de regressão para B5 (ordem marcador/extração) via fake de extração que lança exceção.

---

## 10. Roadmap priorizado

### Alta prioridade (maior ganho ÷ risco)
| Item | Ganho esperado |
|---|---|
| ✅ Correções B1–B13 (entregues) | Estabilidade +alta (corrupção de driver/preset/container), CPU −(logs/allocs), abertura de jogo −segundos por launch, compat 16KB |
| W3 Media Foundation pack | Compat: destrava a maior classe de falhas "tela preta" |
| C3 GameFixes baixáveis | Compat: resposta em dias, não releases |
| G4+P3 Frame pacing + governador ADPF | Perf sustentada e stutter: o maior salto de "sensação" sem tocar no core |
| C1 DynaCache + G5 VkPipelineCache | Stutter/load: barato e mensurável |
| R7 escrita atômica do `.container` + testes de `Container`/`ContainerManager` | Estabilidade: protege dados do usuário |

### Média prioridade
| Item | Ganho |
|---|---|
| P1 Warm Cache Packs | Diferencial competitivo; requer backend |
| W1 DXVK 2.7+/GPL por driver | Perf/compat D3D11 |
| G1 FSR no scanout zero-copy | Perf com upscaling |
| G3 sfCompatMode auto | −1 blit/frame onde suportado |
| S1-S4 paralelização do boot + cópias de exe | Loading |
| A1-A3 áudio/input | Latência |
| Extrair `GameRuntimeOrchestrator` de `XServerScreen` + unificar launchers (R1) | Manutenibilidade/estabilidade |
| R8 resolver fonte nativo órfão | Auditabilidade |

### Baixa prioridade (ou dependente de externos)
| Item | Nota |
|---|---|
| W2 ntsync | Esperar kernels 6.14+ chegarem ao parque |
| W4 VKD3D-Proton 3.x | Experimental, gate por driver |
| P2 formato negociado + fences | Alto esforço no Vortek; fazer após G1-G3 |
| P4 auto-tuning bandit | Após maturar telemetria |
| G2 remover memcpy do caminho CPU | Beneficia apps GDI/2D principalmente |
| R2/R3 gamepad (UDP dedup, 4 players) | Precisa bateria de testes com hardware |
| Remoção do caminho proot morto | Decidir se glibc volta (comentário no código diz que sim) |

---

## 11. O que foi entregue nesta revisão (commits na branch `claude/gamenative-comprehensive-review-egflnd`)

1. `72dc8da` — Bugs de runtime da camada Winlator (B2, B3, B4, B7, B10, B11, B12)
2. `4ff1808` — Overhead por frame/evento no renderer e input (B9-adjacente, alocações, logs)
3. `60de355` — Launch e UI de container (B1, B5, B6, B8, B9, seletor de Wine)
4. `1902841` — 16KB pages nas libs nativas restantes (B13)
5. (este commit) — Testes novos (`ProcessHelperAffinityTest`, `Box86_64PresetManagerTest`) + este relatório

**Validação**: o ambiente desta sessão bloqueia o Google Maven (proxy), impossibilitando compilar/rodar testes localmente; a validação automática ocorrerá no CI do repositório (`pluvia-pr-check.yml` roda os unit tests de ambos os flavors em PR). Todas as mudanças foram revisadas linha a linha, com atenção a escopo de variáveis, imports e semântica preservada (ex.: reset de `dst` no `WindowGeometry` reutilizado; migração retrocompatível dos presets).
