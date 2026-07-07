package app.gamenative.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.data.GameSource
import app.gamenative.gamehub.GameHubRegistrar
import app.gamenative.gamehub.GameLibraryRepository
import app.gamenative.gamehub.GameModel
import app.gamenative.gamehub.StoreConnectionState
import app.gamenative.gamehub.StoreManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the unified Game Hub library screen. It registers every store provider once (via
 * [GameHubRegistrar]) and exposes the merged, source-agnostic library from [StoreManager], with
 * client-side filtering. The screen never talks to a concrete store.
 */
@HiltViewModel
class GameHubViewModel @Inject constructor(
    private val storeManager: StoreManager,
    private val registrar: GameHubRegistrar,
    private val repository: GameLibraryRepository,
) : ViewModel() {

    enum class InstallFilter { ALL, INSTALLED, NOT_INSTALLED }
    enum class SortBy { NAME, STORE, RECENT }

    data class GameHubUiState(
        val games: List<GameModel> = emptyList(),
        val sources: List<GameSource> = emptyList(),
        val installFilter: InstallFilter = InstallFilter.ALL,
        val sourceFilter: GameSource? = null,
        val query: String = "",
        val sortBy: SortBy = SortBy.NAME,
        val favoritesOnly: Boolean = false,
        /** Total games across all stores before filtering (for the "N of M" header). */
        val totalCount: Int = 0,
        val loading: Boolean = true,
    )

    private val allGames = MutableStateFlow<List<GameModel>>(emptyList())
    private val installFilter = MutableStateFlow(InstallFilter.ALL)
    private val sourceFilter = MutableStateFlow<GameSource?>(null)
    private val query = MutableStateFlow("")
    private val sortBy = MutableStateFlow(SortBy.NAME)
    private val favoritesOnly = MutableStateFlow(false)
    private val loading = MutableStateFlow(true)

    private val filteredState = combine(
        allGames, installFilter, sourceFilter, query, loading,
    ) { games, install, source, q, isLoading ->
        val filtered = games.filter { game ->
            (install == InstallFilter.ALL ||
                (install == InstallFilter.INSTALLED && game.isInstalled) ||
                (install == InstallFilter.NOT_INSTALLED && !game.isInstalled)) &&
                (source == null || game.source == source) &&
                (q.isBlank() || game.name.contains(q.trim(), ignoreCase = true))
        }
        GameHubUiState(
            games = filtered,
            sources = games.map { it.source }.distinct().sortedBy { it.ordinal },
            installFilter = install,
            sourceFilter = source,
            query = q,
            totalCount = games.size,
            loading = isLoading,
        )
    }

    val state: StateFlow<GameHubUiState> = combine(filteredState, sortBy, favoritesOnly) { s, sort, favOnly ->
        val base = if (favOnly) s.games.filter { it.isFavorite } else s.games
        val sorted = when (sort) {
            SortBy.NAME -> base.sortedBy { it.name.lowercase() }
            SortBy.STORE -> base.sortedWith(compareBy({ it.source.ordinal }, { it.name.lowercase() }))
            SortBy.RECENT -> base.sortedWith(
                compareByDescending<GameModel> { it.lastPlayedAt }.thenBy { it.name.lowercase() },
            )
        }
        s.copy(games = sorted, sortBy = sort, favoritesOnly = favOnly)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GameHubUiState())

    fun setSort(value: SortBy) { sortBy.value = value }
    fun setFavoritesOnly(value: Boolean) { favoritesOnly.value = value }
    fun toggleFavorite(game: GameModel) {
        viewModelScope.launch { repository.setFavorite(game.id, !game.isFavorite) }
    }

    /** Stamp a game as just-played so the Recent sort reflects it. Call when launching from the hub. */
    fun recordPlayed(gameId: String) {
        viewModelScope.launch { repository.setLastPlayed(gameId, System.currentTimeMillis()) }
    }

    /** One row per registered store for the Stores tab. */
    data class StoreInfo(
        val source: GameSource,
        val displayName: String,
        val connection: StoreConnectionState,
        val gameCount: Int,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val stores: StateFlow<List<StoreInfo>> = storeManager.registeredSources
        .flatMapLatest { sources ->
            if (sources.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(storeManager.connectionStates(), allGames) { conns, games ->
                    storeManager.allProviders().map { provider ->
                        StoreInfo(
                            source = provider.source,
                            displayName = provider.displayName,
                            connection = conns[provider.source] ?: StoreConnectionState.Disconnected,
                            gameCount = games.count { it.source == provider.source },
                        )
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            // Register every provider first, THEN observe the unified library: unifiedLibrary()
            // snapshots the provider set at call time, so it must run after registration.
            registrar.registerAll()
            loading.value = false
            // Merge the persisted hub metadata (favourite/last-played/profile) onto each model.
            combine(storeManager.unifiedLibrary(), repository.observeAll()) { games, meta ->
                games.map { game ->
                    val m = meta[game.id] ?: return@map game
                    game.copy(
                        isFavorite = m.favorite,
                        lastPlayedAt = m.lastPlayedAt,
                        configurationProfileId = m.configurationProfileId,
                    )
                }
            }.collect { allGames.value = it }
        }
    }

    fun setInstallFilter(filter: InstallFilter) { installFilter.value = filter }
    fun setSourceFilter(source: GameSource?) { sourceFilter.value = source }
    fun setQuery(value: String) { query.value = value }

    /** Force a network refresh of every store's library. */
    fun refresh() {
        viewModelScope.launch {
            loading.value = true
            runCatching { storeManager.refreshAll() }
            loading.value = false
        }
    }
}
