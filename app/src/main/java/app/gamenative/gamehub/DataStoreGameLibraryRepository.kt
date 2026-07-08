package app.gamenative.gamehub

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.gameHubDataStore by preferencesDataStore(name = "game_hub_metadata")

/**
 * DataStore-backed [GameLibraryRepository]: hub-owned cross-store state (favourites, last-played,
 * per-game profile) that survives restarts, stored as a single JSON blob. This is the persistent
 * replacement for [InMemoryGameLibraryRepository]; callers are unaffected.
 */
class DataStoreGameLibraryRepository(private val context: Context) : GameLibraryRepository {

    private val key = stringPreferencesKey("metadata_json")

    override fun observeAll(): Flow<Map<String, GameHubMetadata>> =
        context.gameHubDataStore.data.map { prefs -> parse(prefs[key]) }

    override suspend fun get(gameId: String): GameHubMetadata? =
        parse(context.gameHubDataStore.data.first()[key])[gameId]

    override suspend fun setFavorite(gameId: String, favorite: Boolean) =
        update(gameId) { it.copy(favorite = favorite) }

    override suspend fun setLastPlayed(gameId: String, epochMillis: Long) =
        update(gameId) { it.copy(lastPlayedAt = epochMillis) }

    override suspend fun setConfigurationProfile(gameId: String, profileId: String?) =
        update(gameId) { it.copy(configurationProfileId = profileId) }

    private suspend fun update(gameId: String, transform: (GameHubMetadata) -> GameHubMetadata) {
        context.gameHubDataStore.edit { prefs ->
            val map = parse(prefs[key]).toMutableMap()
            val current = map[gameId] ?: GameHubMetadata(gameId)
            map[gameId] = transform(current)
            prefs[key] = serialize(map)
        }
    }

    private fun serialize(map: Map<String, GameHubMetadata>): String {
        val root = JSONObject()
        map.values.forEach { m ->
            val obj = JSONObject()
                .put("f", m.favorite)
                .put("lp", m.lastPlayedAt)
            if (m.configurationProfileId != null) obj.put("p", m.configurationProfileId)
            root.put(m.gameId, obj)
        }
        return root.toString()
    }

    private fun parse(json: String?): Map<String, GameHubMetadata> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val root = JSONObject(json)
            buildMap {
                root.keys().forEach { id ->
                    val obj = root.getJSONObject(id)
                    put(
                        id,
                        GameHubMetadata(
                            gameId = id,
                            favorite = obj.optBoolean("f", false),
                            lastPlayedAt = obj.optLong("lp", 0L),
                            configurationProfileId = if (obj.isNull("p")) null else obj.optString("p").ifEmpty { null },
                        ),
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }
}
