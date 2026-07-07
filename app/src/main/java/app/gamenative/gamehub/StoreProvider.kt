package app.gamenative.gamehub

import app.gamenative.data.GameSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Game Hub — the store adapter contract.
 *
 * A [StoreProvider] is the ONLY place that knows how to talk to one game source (Steam, GOG, Epic,
 * Amazon, local folders, or any future store/plugin). The Game Hub core ([StoreManager], the
 * library, the install/update flows) depends solely on this interface, never on a concrete store,
 * so a new source can be added by implementing this interface and registering it — with no changes
 * to the core. This is the app-native equivalent of the "plugin per store" design.
 *
 * Contract notes:
 * - All potentially-slow calls are `suspend` and must be safe to call off the main thread.
 * - Fallible operations return [Result] rather than throwing, so one misbehaving store can never
 *   take down the aggregated library.
 * - A provider advertises what it can actually do via [capabilities]; the core must respect those
 *   flags instead of assuming every store supports search/install/updates.
 */
interface StoreProvider {

    /** Which source this provider serves. Unique across registered providers. */
    val source: GameSource

    /** Human-facing store name (e.g. "GOG", "Local games"). */
    val displayName: String

    /** What this provider supports. The core gates optional features on these flags. */
    val capabilities: StoreCapabilities

    /** Called once when the provider is registered. Load config/caches here. Cheap and idempotent. */
    suspend fun initialize() {}

    /** Current connection/authentication state, observable so the Stores tab can react to changes. */
    fun connectionState(): Flow<StoreConnectionState>

    /**
     * Begin/refresh authentication for this store. Returns the resulting state. Providers that need
     * no auth (e.g. local games) return [StoreConnectionState.Connected] immediately.
     */
    suspend fun authenticate(): Result<StoreConnectionState> = Result.success(StoreConnectionState.Connected())

    /**
     * The user's games for this store as unified models. Emits a fresh list whenever the underlying
     * store library changes, so the aggregated library stays live.
     */
    fun library(): Flow<List<GameModel>>

    /** Force a network refresh of [library]. Returns the number of games after refresh. */
    suspend fun refreshLibrary(): Result<Int>

    /**
     * Full details for one game (long description, screenshots, requirements, etc.). Optional —
     * providers may return the already-known [GameModel] unchanged.
     */
    suspend fun details(gameId: String): Result<GameModel>

    /** Search this store's catalog. Only meaningful when [StoreCapabilities.canSearch]. */
    suspend fun search(query: String): Result<List<GameModel>> = Result.success(emptyList())

    /** Whether an installed game has an update available. */
    suspend fun checkUpdate(gameId: String): Result<UpdateStatus> = Result.success(UpdateStatus.UP_TO_DATE)

    /**
     * Resolve the Windows-relative launch executable for an installed game, given its install path.
     * Returns null if it can't be determined.
     */
    suspend fun launchExecutable(gameId: String, installPath: String): String?

    // --- Extended professional contract (all optional; default to "not supported") ---
    // Every provider exposes the SAME surface; a provider that can't do something advertises it via
    // [capabilities] and returns a NotSupported failure, so the core never branches on the store.

    /** Begin an interactive login for this store. Defaults to [authenticate]. */
    suspend fun login(): Result<StoreConnectionState> = authenticate()

    /** Sign out / clear this store's credentials. */
    suspend fun logout(): Result<Unit> = Result.success(Unit)

    /** Whether the user is currently authenticated to this store. */
    suspend fun isLogged(): Boolean = false

    /** The signed-in user's profile (name/avatar/status), or null if unknown / not applicable. */
    suspend fun getProfile(): Result<StoreProfile?> = Result.success(null)

    /** The subset of [library] currently installed. Providers may override for efficiency. */
    suspend fun getInstalledGames(): List<GameModel> = emptyList()

    /** A single game by id, or null if unknown. */
    suspend fun getGame(gameId: String): GameModel? = null

    /** Force a full library sync. Defaults to [refreshLibrary]. */
    suspend fun syncLibrary(): Result<Int> = refreshLibrary()

    /** Launch an installed game directly. Defaults to unsupported (the app's launch flow handles it). */
    suspend fun launch(gameId: String): Result<Unit> = notSupported("launch")

    /** Start installing/downloading a game. Only when [StoreCapabilities.canInstall]. */
    suspend fun install(gameId: String): Result<Unit> = notSupported("install")

    /** Remove an installed game. Only when [StoreCapabilities.canUninstall]. */
    suspend fun uninstall(gameId: String): Result<Unit> = notSupported("uninstall")

    /** Pause an in-progress download. Only when [StoreCapabilities.canControlDownloads]. */
    suspend fun pauseDownload(gameId: String): Result<Unit> = notSupported("pauseDownload")

    /** Resume a paused download. Only when [StoreCapabilities.canControlDownloads]. */
    suspend fun resumeDownload(gameId: String): Result<Unit> = notSupported("resumeDownload")

    /** Cancel a download. Only when [StoreCapabilities.canControlDownloads]. */
    suspend fun cancelDownload(gameId: String): Result<Unit> = notSupported("cancelDownload")

    /** Observe a game's download progress, or a flow of null when not downloading / unsupported. */
    fun downloadProgress(gameId: String): Flow<DownloadProgress?> = flowOf(null)

    /** Verify an installed game's files. Only when [StoreCapabilities.canVerify]. */
    suspend fun verifyInstallation(gameId: String): Result<Boolean> = notSupported("verifyInstallation")

    /** Repair an installed game's files. Only when [StoreCapabilities.canRepair]. */
    suspend fun repairInstallation(gameId: String): Result<Unit> = notSupported("repairInstallation")

    private fun <T> notSupported(op: String): Result<T> =
        Result.failure(UnsupportedOperationException("$op not supported by $displayName"))
}

/** Declares which optional operations a [StoreProvider] actually supports. */
data class StoreCapabilities(
    val canSearch: Boolean = false,
    val canInstall: Boolean = true,
    val canUpdate: Boolean = true,
    val canUninstall: Boolean = true,
    val canImportExisting: Boolean = false,
    val hasCloudSaves: Boolean = false,
    val requiresAuth: Boolean = true,
    /** Supports interactive login/logout (vs. auth handled elsewhere). */
    val canLogin: Boolean = false,
    /** Exposes a user profile (name/avatar). */
    val hasProfile: Boolean = false,
    /** Supports pause/resume/cancel of downloads. */
    val canControlDownloads: Boolean = false,
    /** Supports verifying installed files. */
    val canVerify: Boolean = false,
    /** Supports repairing installed files. */
    val canRepair: Boolean = false,
)

/** Signed-in user profile for a store (all fields optional). */
data class StoreProfile(
    val username: String = "",
    val avatarUrl: String = "",
    val status: String = "",
)

/** Live download progress for one game. */
data class DownloadProgress(
    val gameId: String,
    val percent: Float = 0f,
    val bytesDownloaded: Long = 0L,
    val bytesTotal: Long = 0L,
    val state: DownloadState = DownloadState.DOWNLOADING,
)

enum class DownloadState { QUEUED, DOWNLOADING, PAUSED, INSTALLING, DONE, FAILED, CANCELLED }

/** Connection/authentication state of a store, surfaced on the Stores tab. */
sealed interface StoreConnectionState {
    /** Not connected / not logged in. */
    data object Disconnected : StoreConnectionState

    /** Auth/handshake in progress. */
    data object Connecting : StoreConnectionState

    /** Connected and usable. [account] is an optional display name. */
    data class Connected(val account: String = "") : StoreConnectionState

    /** Connection failed. [reason] is a user-facing message. */
    data class Error(val reason: String) : StoreConnectionState
}

/** Result of an update check for a single installed game. */
enum class UpdateStatus {
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    UNKNOWN,
}
