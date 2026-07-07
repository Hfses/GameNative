package app.gamenative.gamehub

import app.gamenative.data.GameSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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

    // Serializes the mutate-then-recompute of [_registered]. The map itself is thread-safe, but
    // "put/remove then set _registered = keys" is a read-modify-write: without this lock two
    // concurrent register/unregister calls could interleave and leave _registered permanently out
    // of sync with the map (a lost update).
    private val registryLock = Any()

    private val _registered = MutableStateFlow<List<GameSource>>(emptyList())

    /** The set of currently-registered sources, observable so the UI can rebuild its store list. */
    val registeredSources: StateFlow<List<GameSource>> = _registered.asStateFlow()

    /** Register (or replace) the provider for its [StoreProvider.source]. */
    suspend fun register(provider: StoreProvider) {
        synchronized(registryLock) {
            providers[provider.source] = provider
            _registered.value = providers.keys.sortedBy { it.ordinal }
        }
        runCatching { provider.initialize() }
    }

    fun unregister(source: GameSource) {
        synchronized(registryLock) {
            providers.remove(source)
            _registered.value = providers.keys.sortedBy { it.ordinal }
        }
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
        val searchable = searchableProviders()
        if (searchable.isEmpty()) return emptyList()
        // Genuine fan-out: query every store concurrently so total latency is the slowest store,
        // not the sum of all of them.
        return coroutineScope {
            searchable
                .map { provider -> async { provider.search(query).getOrDefault(emptyList()) } }
                .awaitAll()
                .flatten()
        }
    }

    /**
     * The live connection state of every registered store, merged into one map. Emits whenever any
     * store's connection changes, so the Stores tab stays current without knowing any concrete store.
     */
    fun connectionStates(): Flow<Map<GameSource, StoreConnectionState>> {
        val current = allProviders()
        if (current.isEmpty()) return flowOf(emptyMap())
        return combine(current.map { provider -> provider.connectionState().map { provider.source to it } }) { pairs ->
            pairs.toMap()
        }
    }

    /** Refresh every store's library concurrently. Returns per-source counts (or the failure). */
    suspend fun refreshAll(): Map<GameSource, Result<Int>> = coroutineScope {
        allProviders()
            .map { provider -> async { provider.source to provider.refreshLibrary() } }
            .awaitAll()
            .toMap()
    }
}
