package app.gamenative.gamehub

import app.gamenative.data.GameSource
import kotlinx.coroutines.flow.Flow

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
)

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
