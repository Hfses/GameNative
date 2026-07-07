package app.gamenative.gamehub

import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Game Hub — a ready-to-use [StoreProvider] that adapts an existing source by delegation.
 *
 * Each existing store already has a manager that can produce its games as [LibraryItem]s and knows
 * how to refresh, resolve a launch exe, search, etc. Rather than rewrite that logic, a store plugs
 * into the hub by constructing one of these with the relevant delegates — so the hub gains a real,
 * working provider for that source with no coupling in the core to the concrete manager.
 *
 * Example (wiring GOG, done at the composition root, not in the core):
 * ```
 * DelegatingStoreProvider(
 *     source = GameSource.GOG,
 *     displayName = "GOG",
 *     capabilities = StoreCapabilities(canSearch = false, hasCloudSaves = true),
 *     libraryItems = gogLibraryFlow,                       // Flow<List<LibraryItem>>
 *     onRefresh = { gogManager.refreshLibrary(context).getOrDefault(0) },
 *     onLaunchExecutable = { id, path -> gogManager.getLaunchExecutable(id, container) },
 * )
 * ```
 */
class DelegatingStoreProvider(
    override val source: GameSource,
    override val displayName: String,
    override val capabilities: StoreCapabilities,
    /** Live per-source library as the app already models it; mapped to [GameModel] internally. */
    private val libraryItems: Flow<List<LibraryItem>>,
    private val onRefresh: suspend () -> Int = { 0 },
    private val onLaunchExecutable: suspend (gameId: String, installPath: String) -> String? = { _, _ -> null },
    private val onSearch: suspend (query: String) -> List<GameModel> = { emptyList() },
    private val onCheckUpdate: suspend (gameId: String) -> UpdateStatus = { UpdateStatus.UNKNOWN },
    private val onAuthenticate: suspend () -> StoreConnectionState = { StoreConnectionState.Connected() },
    initialState: StoreConnectionState = StoreConnectionState.Connected(),
) : StoreProvider {

    private val _connection = MutableStateFlow(initialState)

    override fun connectionState(): Flow<StoreConnectionState> = _connection

    override suspend fun authenticate(): Result<StoreConnectionState> = runCatching {
        _connection.value = StoreConnectionState.Connecting
        val state = onAuthenticate()
        _connection.value = state
        state
    }.onFailure { _connection.value = StoreConnectionState.Error(it.message ?: "Authentication failed") }

    override fun library(): Flow<List<GameModel>> =
        libraryItems.map { items -> items.map(GameModelMapper::fromLibraryItem) }

    override suspend fun refreshLibrary(): Result<Int> = runCatching { onRefresh() }

    override suspend fun details(gameId: String): Result<GameModel> =
        // A store with a richer detail endpoint supplies its own provider; the delegating base has
        // only the library projection, so callers should fall back to the library model on failure.
        Result.failure(UnsupportedOperationException("details() not wired for $displayName"))

    override suspend fun search(query: String): Result<List<GameModel>> =
        if (capabilities.canSearch) runCatching { onSearch(query) } else Result.success(emptyList())

    override suspend fun checkUpdate(gameId: String): Result<UpdateStatus> =
        if (capabilities.canUpdate) runCatching { onCheckUpdate(gameId) } else Result.success(UpdateStatus.UP_TO_DATE)

    override suspend fun launchExecutable(gameId: String, installPath: String): String? =
        onLaunchExecutable(gameId, installPath)
}
