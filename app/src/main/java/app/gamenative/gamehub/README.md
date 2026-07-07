# Game Hub — universal store/library architecture (Phase 1: core)

The Game Hub turns GameNative into a **source-agnostic game manager**. Steam, GOG, Epic, Amazon,
local folders and any future store are all reached through a single adapter contract, so the app's
library, search, install, update and launch flows never branch on "which store".

This package is **Phase 1: the core architecture**. It is additive and self-contained — no existing
screen or manager changed, so it cannot alter current behaviour. It is the foundation the later
phases (UI, install queue, storage manager, update manager, telemetry) build on.

## The principle

The core never depends on a concrete store. There is no `if (source == GOG) …` in the hub. A store
plugs in by implementing an adapter and registering it:

```
              ┌──────────────────────────┐
              │        StoreManager        │  ← registry + aggregation (the core)
              └──────────────┬─────────────┘
                             │ depends only on StoreProvider + GameModel
      ┌───────────┬──────────┼───────────┬─────────────┐
   Steam        GOG        Epic        Amazon      Local games   ← StoreProvider adapters
      └───────────┴──────────┴───────────┴─────────────┘
                             │
                    Unified library (List<GameModel>)
```

## The pieces (this phase)

| File | Role |
|------|------|
| `GameModel.kt` | The single, immutable, source-agnostic game shape every store maps to. `InstallState` is its lifecycle. |
| `StoreProvider.kt` | The **adapter contract**: `library()`, `refreshLibrary()`, `search()`, `checkUpdate()`, `authenticate()`, `launchExecutable()`, plus `StoreCapabilities` (what the store actually supports) and `StoreConnectionState`. All fallible calls return `Result`; slow calls are `suspend`. |
| `StoreManager.kt` | The registry. `register()`/`unregister()`, `unifiedLibrary()` (merges every store's live library), `searchAll()` (fan-out over searchable stores), `refreshAll()`. Thread-safe. |
| `DelegatingStoreProvider.kt` | A ready-made adapter that wraps an existing manager by delegation, so each store plugs in with a few lambdas instead of a rewrite. |
| `GameModelMapper.kt` | Bridge to the existing `LibraryItem` so migration is incremental — both models coexist. |
| `GameLibraryRepository.kt` | Persistence contract for **hub-owned** cross-store state (favourites, last-played, per-game execution profile). `InMemoryGameLibraryRepository` is the default; a Room/DataStore impl replaces it later with no caller changes. |

Tested by `app/src/test/java/app/gamenative/gamehub/{StoreManagerTest,GameModelMapperTest}.kt`.

## Wiring an existing store (example, at the composition root — NOT in the core)

```kotlin
val hub = StoreManager()
hub.register(
    DelegatingStoreProvider(
        source = GameSource.GOG,
        displayName = "GOG",
        capabilities = StoreCapabilities(canSearch = false, hasCloudSaves = true),
        libraryItems = gogLibraryFlow,                                  // existing per-source flow
        onRefresh = { gogManager.refreshLibrary(context).getOrDefault(0) },
        onLaunchExecutable = { id, path -> gogManager.getLaunchExecutable(id, container) },
    ),
)
// The whole app then reads hub.unifiedLibrary() / hub.searchAll(query) — store-agnostic.
```

## Roadmap (later phases, not in this commit)

- **Phase 2 — Adapters + UI**: concrete `StoreProvider`s for Steam/GOG/Epic/Amazon/Local wired to
  the real managers; "Stores" and unified "Library" tabs consuming `StoreManager`; filters
  (installed / not installed / updates / favourites / by source).
- **Phase 3 — Managers**: `InstallManager` (space check → location → download → validate → register
  → profile → library), global `DownloadManager` queue (pause/resume/cancel/limit), `StorageManager`
  (multiple locations, move games), `UpdateManager` (games + adapters), "import existing game" scan.
- **Phase 4 — Telemetry**: per-launch benchmark capture feeding the (separate) GameNative Server;
  see `docs/SERVIDOR_GAMENATIVE_ANALISE.md`.
