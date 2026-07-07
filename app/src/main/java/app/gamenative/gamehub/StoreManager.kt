package app.gamenative.gamehub

import app.gamenative.data.GameSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.ConcurrentHashMap

/**
 * Game Hub — the store registry and aggregation point.
 *
 * The core of the "universal library". [StoreProvider]s register here; everything above (the
 * Stores tab, the unified Library, search, install/update flows) talks only to this manager and to
 * the [GameModel] abstraction, never to a concrete store. Adding a new source is: implement
 * [StoreProvider], call [register]. Nothing in the core changes.
 *
 * Thread-safe: providers live in a [ConcurrentHashMap] so registration and reads can race safely.
 */
class StoreManager {

    private val providers = ConcurrentHashMap<GameSource, StoreProvider>()

    private val _registered = MutableStateFlow<List<GameSource>>(emptyList())

    /** The set of currently-registered sources, observable so the UI can rebuild its store list. */
    val registeredSources: StateFlow<List<GameSource>> = _registered.asStateFlow()

    /** Register (or replace) the provider for its [StoreProvider.source]. */
    suspend fun register(provider: StoreProvider) {
        providers[provider.source] = provider
        _registered.value = providers.keys.sortedBy { it.ordinal }
        runCatching { provider.initialize() }
    }

    fun unregister(source: GameSource) {
        providers.remove(source)
        _registered.value = providers.keys.sortedBy { it.ordinal }
    }

    fun provider(source: GameSource): StoreProvider? = providers[source]

    fun allProviders(): List<StoreProvider> = providers.values.sortedBy { it.source.ordinal }

    /** Providers that advertise catalog search. */
    fun searchableProviders(): List<StoreProvider> = allProviders().filter { it.capabilities.canSearch }

    /**
     * The unified library: every registered store's [StoreProvider.library] merged into one list.
     * Emits whenever any store's library changes. A store that has no games contributes an empty
     * slice; a store that errors is simply absent from that emission (its own flow handles errors).
     */
    fun unifiedLibrary(): Flow<List<GameModel>> {
        val libraries = allProviders().map { it.library() }
        if (libraries.isEmpty()) return flowOf(emptyList())
        return combine(libraries) { slices -> slices.toList().flatten() }
    }

    /**
     * Fan-out search across all searchable stores. Per-store failures are dropped (an empty slice)
     * so one broken store can't fail the whole search. Callers get one flat, source-tagged list.
     */
    suspend fun searchAll(query: String): List<GameModel> {
        if (query.isBlank()) return emptyList()
        return searchableProviders().flatMap { provider ->
            provider.search(query).getOrDefault(emptyList())
        }
    }

    /** Refresh every store's library. Returns per-source counts (or the failure) for reporting. */
    suspend fun refreshAll(): Map<GameSource, Result<Int>> =
        allProviders().associate { it.source to it.refreshLibrary() }
}
