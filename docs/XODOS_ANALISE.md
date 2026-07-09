# Análise do XoDos e plano de aproveitamento no GameNative

*Análise técnica do pacote XoDos-main (fornecido em zip) feita em 2026-07.*

## O que o XoDos é, por dentro

| Componente | O que é | Equivalente no GameNative |
|---|---|---|
| `app/` (com.termux) | Fork do **Termux** (terminal Android) | — (GameNative não expõe terminal) |
| `termux-x11/` | Servidor X11 compilado das fontes do **X.org** (libx11, xkbcomp, pixman…) | Servidor X próprio em Java/Kotlin (`com.winlator.xserver`) + renderer nativo |
| `terminal-emulator/`, `terminal-view/`, `shell-loader/`, `termux-shared/` | Infra do Termux | — |
| `float-ball/`, `wid/` | Botão flutuante com menu rápido sobre o jogo | QuickMenu (painel in-game) |
| Rootfs (download externo) | **Debian/Kali via proot** com XFCE4, Wine glibc/bionic, Box64 instalados por apt | imagefs próprio + variantes glibc (proot) e bionic + ContentsManager/manifest |

Conclusão estrutural: o XoDos é um **desktop Linux completo dentro do Android** (instalável via apt, com terminal), enquanto o GameNative é um **launcher de jogos integrado** (Steam/GOG/Epic/Amazon + Winlator embutido). O runtime Wine/Box64 do XoDos mora dentro do rootfs Debian — não há código de orquestração de Wine no app dele que possamos "portar"; a orquestração é shell + apt.

## Avaliação item a item (o que foi pedido)

1. **Sistema Linux utilizado** — Debian/Kali proot. O GameNative já tem caminho proot (variante glibc) com imagefs otimizado e menor. Adotar um rootfs Debian completo **pioraria** tamanho (o APK "full" do XoDos tem 1.86 GB) e tempo de boot do container. **Não migrar.**
2. **Estrutura de runtime** — wine glibc+bionic instalados no rootfs. O GameNative já suporta as duas variantes nativamente com troca por container. **Já coberto.**
3. **Gerenciamento de dependências** — `apt` dentro do proot. Flexível para desktop, mas pesado e online-first. O GameNative usa ContentsManager + manifest (agora com "baixar tudo"). **Já coberto com abordagem melhor para jogos.**
4. **Inicialização de containers** — scripts shell no boot do proot. GameNative tem pipeline tipado (LaunchDependency/preInstallSteps) com re-extração condicional. **Já coberto.**
5. **Performance de Wine** — mesma base (Box64/DXVK/Turnip); XoDos não traz tuning que o GameNative não tenha; nosso lado já aplica ASYNC/ASYNC_CACHE, PULSE_LATENCY, presets Box64, ADPF governor, afinidade de CPU. **Sem ganho real identificado.**
6. **Cache de shaders** — nada específico no XoDos além do padrão DXVK/mesa do rootfs. GameNative já fixa `DXVK_STATE_CACHE_PATH` persistente (e o relatório de melhorias propõe fixar também `MESA_SHADER_CACHE_DIR`). **Sem ganho.**
7. **Gerenciamento de bibliotecas compartilhadas** — ld.so do Debian dentro do proot. GameNative usa patchelf/imagefs. **Equivalente.**

## O que VALE aproveitar (ganho real)

1. **Diagnóstico de símbolo/libc no loader** — a classe de erro `Symbol __libc_init not found` que o XoDos evita "na marra" (rootfs consistente) nós resolvemos melhor: **matriz de compatibilidade + detecção ELF pré-launch** (implementado em `RuntimeCompatibility.kt`): detecta Wine compilado para a libc errada antes de abrir o jogo, troca para fallback compatível, avisa o usuário e registra em `files/logs/runtime_compat.log`.
2. **Terminal/console de depuração (conceito)** — para power users, um console de logs do container (não um Termux completo). Proposta futura: tela "Logs do container" lendo stdout/stderr do guest (baixo esforço, alto valor de suporte).
3. **Float-ball (conceito)** — atalho flutuante minimizado além do QuickMenu atual; opcional, baixo valor incremental. Futuro/opcional.

## O que NÃO migrar (e por quê)

- **Termux/terminal embutido**: peso, superfície de segurança e manutenção enormes; foge do produto (launcher de jogos).
- **Rootfs Debian/Kali**: 1–2 GB extras, boot mais lento, dependência de mirrors apt.
- **termux-x11**: nosso servidor X em Java já é integrado ao renderer/scanout e aos controles; trocar seria reescrever o coração do app sem ganho comprovado.

## Medições de desempenho

Não é possível medir FPS/tempo de abertura neste ambiente (CI sem GPU/Android). O que já está instrumentado no GameNative para o usuário medir no aparelho: overlay de FPS, PerformanceGovernor (ADPF), e os logs de boot. Comparação honesta XoDos×GameNative exige o mesmo jogo, mesmo aparelho, mesmas versões de Wine/DXVK — recomendo 3 títulos (leve/médio/pesado), 3 execuções cada, anotando FPS médio e tempo até o menu.

## Resumo executivo

O XoDos é um ótimo projeto para quem quer um **PC Linux no bolso**; o GameNative já contém internamente tudo que o XoDos usa para *jogos* (X server, Wine glibc/bionic, Box64, DXVK/Turnip, controles), com integração mais profunda. O ganho real desta análise foi transformar a fraqueza que o XoDos evita por construção (mistura de libc) em **proteção automática pré-launch** no GameNative — já implementada.
