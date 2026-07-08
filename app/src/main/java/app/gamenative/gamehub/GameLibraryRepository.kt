package app.gamenative.gamehub

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

/**
 * Game Hub — persistence contract for hub-owned, cross-store state.
 *
 * The per-store catalogs are already persisted by the existing Room entities (SteamApp, GOGGame,
 * EpicGame, AmazonGame). This repository owns only the state that is *about the hub itself* and
 * spans stores: favourites, last-played timestamps, and the per-game execution-profile association.
 * Keeping it a separate, small store avoids a risky migration of the existing catalog tables in
 * Phase 1; a Room-backed implementation can replace [InMemoryGameLibraryRepository] later without
 * touching callers.
 */
interface GameLibraryRepository {
    /** Observable map of gameId -> hub metadata. */
    fun observeAll(): Flow<Map<String, GameHubMetadata>>

    suspend fun get(gameId: String): GameHubMetadata?

    suspend fun setFavorite(gameId: String, favorite: Boolean)

    suspend fun setLastPlayed(gameId: String, epochMillis: Long)

    suspend fun setConfigurationProfile(gameId: String, profileId: String?)

    /** Merge hub metadata onto a freshly-produced model (favourite/last-played/profile). */
    suspend fun decorate(model: GameModel): GameModel {
        val meta = get(model.id) ?: return model
        return model.copy(
            isFavorite = meta.favorite,
            lastPlayedAt = meta.lastPlayedAt,
            configurationProfileId = meta.configurationProfileId,
        )
    }
}

/** Hub-owned, cross-store metadata for one game. */
data class GameHubMetadata(
    val gameId: String,
    val favorite: Boolean = false,
    val lastPlayedAt: Long = 0L,
    val configurationProfileId: String? = null,
)

/**
 * Default in-memory implementation. Correct and thread-safe; simply non-persistent. Suitable for
 * Phase 1 wiring and tests. Swap for a Room/DataStore-backed implementation to survive restarts.
 */
class InMemoryGameLibraryRepository : GameLibraryRepository {
    private val store = ConcurrentHashMap<String, GameHubMetadata>()
    private val flow = MutableStateFlow<Map<String, GameHubMetadata>>(emptyMap())

    private fun publish() { flow.value = HashMap(store) }

    override fun observeAll(): Flow<Map<String, GameHubMetadata>> = flow.asStateFlow()

    override suspend fun get(gameId: String): GameHubMetadata? = store[gameId]

    override suspend fun setFavorite(gameId: String, favorite: Boolean) {
        store[gameId] = (store[gameId] ?: GameHubMetadata(gameId)).copy(favorite = favorite)
        publish()
    }

    override suspend fun setLastPlayed(gameId: String, epochMillis: Long) {
        store[gameId] = (store[gameId] ?: GameHubMetadata(gameId)).copy(lastPlayedAt = epochMillis)
        publish()
    }

    override suspend fun setConfigurationProfile(gameId: String, profileId: String?) {
        store[gameId] = (store[gameId] ?: GameHubMetadata(gameId)).copy(configurationProfileId = profileId)
        publish()
    }
}

/** Convenience: observe a single game's hub metadata as a flow. */
fun GameLibraryRepository.observe(gameId: String): Flow<GameHubMetadata?> =
    observeAll().map { it[gameId] }
