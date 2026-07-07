# GameNative Server — Análise de Viabilidade e Projeto

**Status:** documento de análise (não implementado neste repositório).
**Motivo:** o servidor é um projeto separado (Python/TypeScript/Docker), não é código Android e
não pertence ao app. Ele deve viver em um repositório próprio (ex.: `gamenative-server`). Este
documento descreve **o que dá para fazer, como fazer, e melhorias** para quando você criar esse repo.

---

## 1. Resumo executivo

O objetivo é um **ecossistema de telemetria e recomendação** para o GameNative:

- o app Android envia benchmarks (poucos KB) e diagnósticos avançados (sob demanda);
- um servidor (inicialmente seu PC) armazena, analisa, cria rankings e gera recomendações;
- um dashboard web mostra estatísticas por jogo/hardware/configuração;
- a arquitetura permite migrar do PC → VPS → Cloud **sem reescrever o app**.

**Viabilidade: alta.** Todas as peças (FastAPI, PostgreSQL, Next.js, Docker, Redis, ZSTD) são
maduras e gratuitas. O risco real não é técnico — é de **privacidade, anti-fraude e custo de
manutenção**. Este documento prioriza esses pontos, que o prompt original subestima.

---

## 2. Arquitetura recomendada

```
Android (GameNative)
  └── TelemetryClient  ──►  fila local (Room)  ──►  Sincronização (batch, com backoff)
                                                        │  HTTPS + API Key por dispositivo
                                                        ▼
                                          ┌──────────────────────────────┐
                                          │  Reverse proxy (Caddy/Nginx)  │  TLS automático
                                          └───────────────┬──────────────┘
                                                          ▼
                              ┌───────────────────────────────────────────────┐
                              │  FastAPI  (API REST + auth + validação)         │
                              ├───────────────┬───────────────┬────────────────┤
                              │ PostgreSQL     │ Redis (rate    │ Object storage │
                              │ (dados relac.) │  limit + cache)│ (logs ZSTD/TTL)│
                              └───────────────┴───────────────┴────────────────┘
                                                          ▲
                              ┌───────────────────────────┴───────────────────┐
                              │  Next.js dashboard (SSR)  +  Analytics/Reco     │
                              └────────────────────────────────────────────────┘
```

**Decisões-chave**
- **Reverse proxy à frente do FastAPI** (o prompt não menciona): indispensável para TLS, rate
  limiting de borda e para não expor o Uvicorn direto. Caddy dá HTTPS automático.
- **Redis** para rate limiting e cache de agregações (rankings), não para dados primários.
- **Object storage** (MinIO local, S3 depois) para logs/diagnósticos comprimidos — **nunca** no
  Postgres, como o próprio prompt já indica.
- **A API é a única fronteira estável.** App fala só com a API. Trocar PC→VPS→Cloud = mudar o
  endereço base + mover volumes Docker. É isso que dá a portabilidade pedida.

---

## 3. Modelo de dados (PostgreSQL, normalizado)

Tabelas mínimas (chaves e índices resumidos):

- **users** `(id, username, email, created_at)`
- **devices** `(id, user_id→users, fabricante, modelo, soc, cpu, gpu, ram_mb, android_version, api_key_hash, created_at)`
- **games** `(id, canonical_name, source, external_id, UNIQUE(source, external_id))`
- **components** `(id, tipo, versao, hash, UNIQUE(tipo, hash))` — Wine/Box64/DXVK/VKD3D/Turnip/Mesa/VirGL
- **profiles** `(id, device_id→devices, game_id→games, renderer, resolution, esync, fsync, …)`
- **profile_components** `(profile_id→profiles, component_id→components)` — N:N (evita duplicar versões)
- **benchmarks** `(id, game_id, device_id, profile_id, fps_avg, fps_1low, fps_01low, frame_time_ms, cpu_pct, gpu_pct, temp_c, ram_mb, vram_mb, duration_s, created_at)`
- **recommendations** `(id, game_id, hardware_key, config_atual_json, config_reco_json, ganho_estimado_pct, created_at)`
- **diagnostics** `(id, benchmark_id, storage_key, size_bytes, ttl_at)` — só metadados; blob no object storage

**Regras**
- Componentes e jogos são **deduplicados por hash/UNIQUE** — uma versão de DXVK existe uma vez.
- `benchmarks` guarda **apenas agregados** (nada de série temporal por frame). Série temporal =
  diagnóstico avançado, comprimido, no storage com TTL.
- Índice composto sugerido: `benchmarks(game_id, device_id, created_at)` para rankings/consultas.

---

## 4. Telemetria em duas camadas (correto no prompt, reforçado aqui)

**Camada 1 — Benchmark normal (default, opt-in):** FPS médio/1%/0.1%, temperatura, uso CPU/GPU,
config usada, versões de componentes. Poucos KB. Enviado em lote.

**Camada 2 — Diagnóstico avançado (sob demanda / em crash):** timeline, spikes, shader
compilation, logs, stacktrace. Comprimido com **ZSTD**, enviado ao object storage, **TTL 7–30 dias**.

**No app, isto conecta ao Game Hub (Fase 4):** um hook por sessão de jogo (`GAME_START`/`GAME_END`)
que grava o benchmark na fila local (Room) e é sincronizado depois. A captura de FPS/frametime pode
reusar o overlay/telemetria já existente no projeto.

---

## 5. Sincronização

- Botão **"☁ Sincronizar com GameNative Server"** + sync automático quando online.
- Fluxo: verificar `/health` → enviar lote pendente → confirmar → limpar cache enviado.
- Offline: acumular na fila local com contador ("Benchmarks pendentes: 35"); reenviar com **backoff
  exponencial** ao voltar. **Idempotência**: cada benchmark tem um `client_uuid` para o servidor
  descartar reenvios duplicados sem contar duas vezes.

---

## 6. API REST (FastAPI)

| Método | Rota | Função |
|--------|------|--------|
| GET | `/health` | `{api, db, storage}` OK — usado pela sync |
| POST | `/devices/register` | registra dispositivo, devolve **API Key** |
| GET | `/games`, `/games/{id}` | catálogo e detalhe |
| POST | `/benchmarks` | recebe lote (validação + idempotência) |
| GET | `/games/{id}/benchmarks` | agregados por jogo |
| POST | `/diagnostics` | upload de diagnóstico (multipart, ZSTD) |
| POST | `/recommendations` | pede recomendação para hardware+jogo+config |

Documentação automática via OpenAPI (grátis no FastAPI). Versionar sob `/v1/…` desde o início.

---

## 7. Segurança e anti-fraude (o ponto mais crítico — subestimado no prompt)

> "Nunca confiar nos dados enviados pelo usuário." — correto, e precisa de mecanismo, não só intenção.

- **API Key por dispositivo**, guardada com **hash** (nunca em texto), enviada em header.
- **Rate limiting** por dispositivo/IP (Redis) — impede flood e brute force.
- **Validação de esquema** estrita (Pydantic): tipos, faixas plausíveis (ex.: FPS 0–1000, temp
  0–120 °C), rejeitar fora do intervalo.
- **Checksum + hash dos componentes**: a config declarada precisa referenciar versões/hashes
  conhecidos; hashes desconhecidos entram em quarentena, não no ranking.
- **Anti-benchmark falso** (o mais difícil): 
  - detecção de outliers estatísticos por (jogo, hardware); 
  - exigir N amostras de dispositivos distintos antes de um resultado entrar no ranking; 
  - "confiança" por dispositivo (histórico consistente sobe peso; contradições derrubam); 
  - nunca deixar um único envio mover o ranking.
- **HTTPS obrigatório** (Caddy/Nginx). Sem TLS, nada de API Key trafegando.

---

## 8. Privacidade / LGPD (ausente no prompt — obrigatório)

- Telemetria **opt-in explícito**, com tela clara do que é coletado.
- **Anonimização**: id de dispositivo aleatório, não vinculável a identidade; e-mail opcional.
- Diagnósticos podem conter caminhos/logs — **sanitizar** nomes de usuário/paths antes de enviar.
- Direito a apagar dados (endpoint de exclusão do dispositivo e seus benchmarks).
- Documentar retenção (TTL) e finalidade. Isto protege você legalmente quando virar "Cloud pública".

---

## 9. Motor de recomendação (evolução em 3 fases)

A IA **não recebe logs completos** — só FPS, hardware, config, uso CPU/GPU, temperatura, spikes.

1. **Heurístico (comece aqui):** regras sobre agregados. Ex.: se GPU% ~100 e CPU% baixo → *GPU
   bound* → sugerir DXVK/Turnip mais novos ou resolução menor; retorno "ganho estimado +15%"
   calculado a partir das amostras reais de configs vizinhas. **Sem ML, já entrega valor.**
2. **Estatístico:** "melhor config para (jogo, hardware)" = a config com melhor FPS médio/1% low
   com suporte amostral suficiente. É basicamente um ranking com filtro de confiança.
3. **ML (só quando houver volume):** modelo que prevê ganho ao trocar de config. Só compensa com
   milhares de benchmarks — não construa isso no dia 1.

---

## 10. Docker e operação

`docker-compose.yml` com serviços: `caddy` (proxy/TLS), `fastapi`, `postgres`, `redis`, `minio`
(storage), `frontend` (Next.js). Script `Start_GameNative_Server.bat` (Windows): sobe o compose,
espera o Postgres ficar *healthy*, roda migrações (Alembic), sobe API e dashboard, imprime o
endereço. Extras recomendados:

- **Backup diário** do Postgres (`pg_dump` agendado → volume/borda externa).
- **Migrações versionadas** (Alembic) desde o início — nunca alterar schema à mão.
- **Health checks** no compose para ordenar a subida (API espera DB pronto).
- **Observabilidade** mínima: logs estruturados + `/metrics` (Prometheus) quando crescer.

---

## 11. Caminho PC → VPS → Cloud

Porque o app só conhece a **URL base da API**, migrar é:

1. **PC local:** compose na sua máquina; app aponta para o IP da LAN (ou DDNS + porta).
2. **VPS:** mesmo compose num VPS barato; app aponta para o domínio; Caddy resolve TLS.
3. **Cloud gerenciada:** Postgres gerenciado (RDS/Cloud SQL), storage S3/GCS, API em container
   gerenciado. Só muda infra; código e app não mudam.

Regra de ouro: **nada de estado no container da API** — tudo em Postgres/Redis/storage (volumes).
Assim qualquer host é descartável.

---

## 12. Roadmap sugerido (para o repo do servidor)

- **M1 — MVP:** FastAPI + Postgres + `/health` + `/devices/register` + `/benchmarks` + validação
  Pydantic + API Key + Docker compose. App: fila Room + sync com backoff. (Entrega valor real.)
- **M2 — Leitura:** `/games/{id}/benchmarks`, agregações em Redis, dashboard Next.js básico
  (contadores + página do jogo + ranking).
- **M3 — Recomendação heurística:** `/recommendations` com regras sobre agregados.
- **M4 — Diagnóstico avançado:** upload ZSTD → MinIO + TTL; captura sob demanda no app.
- **M5 — Robustez:** anti-fraude estatístico, backup, observabilidade, LGPD (exclusão/retenção).
- **M6 — Escala/Cloud:** Postgres gerenciado, storage S3, e — só aqui — ML se houver volume.

---

## 13. Melhorias além do pedido original

- **Reverse proxy + TLS automático** (Caddy) desde o M1 — segurança de borda.
- **Idempotência por `client_uuid`** — evita contagem dupla em reenvios.
- **Modelo de confiança por dispositivo** — anti-fraude que um checksum sozinho não resolve.
- **LGPD/opt-in/sanitização** — requisito, não extra, para virar Cloud pública.
- **Recomendação heurística antes de ML** — entrega em semanas, não meses.
- **Compartilhamento de perfis** (evolução natural): "aplicar a melhor config da comunidade" baixa
  o perfil vencedor e o injeta no container via o sistema de perfis do Game Hub (Fase 4).
- **Versionar a API (`/v1`)** desde o início — quando o app estiver na loja, você não pode quebrar
  clientes antigos.

---

### Conexão com este repositório (Android)

O único ponto de contato do servidor com o app é a **camada de telemetria**, que se encaixa na
**Fase 4 do Game Hub** (ver `app/src/main/java/app/gamenative/gamehub/README.md`): um cliente de
telemetria que grava benchmarks numa fila local e sincroniza com a URL da API. Nada do servidor
precisa entrar neste repositório até essa fase — e mesmo então, só o cliente (Kotlin) mora aqui; o
servidor fica no repo dedicado.
