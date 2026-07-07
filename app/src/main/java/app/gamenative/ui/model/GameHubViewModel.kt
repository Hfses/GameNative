package app.gamenative.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.data.GameSource
import app.gamenative.gamehub.GameHubRegistrar
import app.gamenative.gamehub.GameModel
import app.gamenative.gamehub.StoreManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
) : ViewModel() {

    enum class InstallFilter { ALL, INSTALLED, NOT_INSTALLED }

    data class GameHubUiState(
        val games: List<GameModel> = emptyList(),
        val sources: List<GameSource> = emptyList(),
        val installFilter: InstallFilter = InstallFilter.ALL,
        val sourceFilter: GameSource? = null,
        val query: String = "",
        val loading: Boolean = true,
    )

    private val allGames = MutableStateFlow<List<GameModel>>(emptyList())
    private val installFilter = MutableStateFlow(InstallFilter.ALL)
    private val sourceFilter = MutableStateFlow<GameSource?>(null)
    private val query = MutableStateFlow("")
    private val loading = MutableStateFlow(true)

    val state: StateFlow<GameHubUiState> = combine(
        allGames, installFilter, sourceFilter, query, loading,
    ) { games, install, source, q, isLoading ->
        val filtered = games.filter { game ->
            (install == InstallFilter.ALL ||
                (install == InstallFilter.INSTALLED && game.isInstalled) ||
                (install == InstallFilter.NOT_INSTALLED && !game.isInstalled)) &&
                (source == null || game.source == source) &&
                (q.isBlank() || game.name.contains(q.trim(), ignoreCase = true))
        }.sortedBy { it.name.lowercase() }
        GameHubUiState(
            games = filtered,
            sources = games.map { it.source }.distinct().sortedBy { it.ordinal },
            installFilter = install,
            sourceFilter = source,
            query = q,
            loading = isLoading,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GameHubUiState())

    init {
        viewModelScope.launch {
            // Register every provider first, THEN observe the unified library: unifiedLibrary()
            // snapshots the provider set at call time, so it must run after registration.
            registrar.registerAll()
            loading.value = false
            storeManager.unifiedLibrary().collect { allGames.value = it }
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
