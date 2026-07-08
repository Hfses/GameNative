package app.gamenative.gamehub

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.db.dao.EpicGameDao
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.db.dao.SteamAppDao
import app.gamenative.service.SteamService
import app.gamenative.service.amazon.AmazonManager
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicManager
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGManager
import app.gamenative.service.gog.GOGService
import app.gamenative.utils.CustomGameScanner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Game Hub — the composition root that plugs the app's real stores into the [StoreManager].
 *
 * This is deliberately the ONLY place that knows every concrete store at once. Each source is wired
 * as a [DelegatingStoreProvider] over the manager/DAO it already has, so the hub gains a live,
 * working provider per source with no store-specific branching in the core. Call [registerAll] once
 * at startup (idempotent); after that the whole app can read [StoreManager.unifiedLibrary] and get
 * every source's games as one source-agnostic list.
 */
@Singleton
class GameHubRegistrar @Inject constructor(
    private val storeManager: StoreManager,
    private val steamAppDao: SteamAppDao,
    private val gogGameDao: GOGGameDao,
    private val epicGameDao: EpicGameDao,
    private val amazonGameDao: AmazonGameDao,
    private val gogManager: GOGManager,
    private val epicManager: EpicManager,
    private val amazonManager: AmazonManager,
    @ApplicationContext private val context: Context,
) {
    private val registered = AtomicBoolean(false)

    /** Register every store provider exactly once. Safe to call from multiple entry points. */
    suspend fun registerAll() {
        if (!registered.compareAndSet(false, true)) return
        storeManager.register(steamProvider())
        storeManager.register(gogProvider())
        storeManager.register(epicProvider())
        storeManager.register(amazonProvider())
        storeManager.register(localProvider())
    }

    private fun steamState() =
        if (SteamService.isLoggedIn) StoreConnectionState.Connected() else StoreConnectionState.Disconnected

    private fun steamProvider() = DelegatingStoreProvider(
        source = GameSource.STEAM,
        displayName = "Steam",
        capabilities = StoreCapabilities(canSearch = false, requiresAuth = true),
        // Steam's entity has no install column; resolve it per app off the main thread.
        games = steamAppDao.getAllOwnedApps()
            .map { apps -> apps.map { it.toGameModel(SteamService.isAppInstalled(it.id)) } }
            .flowOn(Dispatchers.IO),
        onRefresh = { SteamService.refreshOwnedGamesFromServer() },
        onAuthenticate = { steamState() },
        initialState = steamState(),
    )

    private fun gogState(): StoreConnectionState =
        if (GOGService.hasStoredCredentials(context)) StoreConnectionState.Connected() else StoreConnectionState.Disconnected

    private fun gogProvider() = DelegatingStoreProvider(
        source = GameSource.GOG,
        displayName = "GOG",
        capabilities = StoreCapabilities(canSearch = false, hasCloudSaves = true, requiresAuth = true),
        games = gogGameDao.getAll().map { games -> games.map { it.toGameModel() } },
        onRefresh = { gogManager.refreshLibrary(context).getOrDefault(0) },
        onAuthenticate = { gogState() },
        initialState = gogState(),
    )

    private fun epicState(): StoreConnectionState =
        if (EpicService.hasStoredCredentials(context)) StoreConnectionState.Connected() else StoreConnectionState.Disconnected

    private fun epicProvider() = DelegatingStoreProvider(
        source = GameSource.EPIC,
        displayName = "Epic Games",
        capabilities = StoreCapabilities(canSearch = false, requiresAuth = true),
        games = epicGameDao.getAll().map { games -> games.map { it.toGameModel() } },
        onRefresh = { epicManager.refreshLibrary(context).getOrDefault(0) },
        onAuthenticate = { epicState() },
        initialState = epicState(),
    )

    private fun amazonState(): StoreConnectionState =
        if (AmazonService.hasStoredCredentials(context)) StoreConnectionState.Connected() else StoreConnectionState.Disconnected

    private fun amazonProvider() = DelegatingStoreProvider(
        source = GameSource.AMAZON,
        displayName = "Amazon Games",
        capabilities = StoreCapabilities(canSearch = false, requiresAuth = true),
        games = amazonGameDao.getAll().map { games -> games.map { it.toGameModel() } },
        onRefresh = { amazonManager.refreshLibrary(); amazonManager.getAllGames().size },
        onAuthenticate = { amazonState() },
        initialState = amazonState(),
    )

    private fun localProvider() = DelegatingStoreProvider.fromLibraryItems(
        source = GameSource.CUSTOM_GAME,
        displayName = "Local games",
        capabilities = StoreCapabilities(
            canSearch = false,
            canInstall = false,
            canUpdate = false,
            canImportExisting = true,
            requiresAuth = false,
        ),
        // Folder-scanned games have no reactive source yet; emit a snapshot (refresh re-scans).
        libraryItems = flow { emit(CustomGameScanner.scanAsLibraryItems()) }.flowOn(Dispatchers.IO),
        onRefresh = { CustomGameScanner.scanAsLibraryItems().size },
        onAuthenticate = { StoreConnectionState.Connected() },
    )
}
