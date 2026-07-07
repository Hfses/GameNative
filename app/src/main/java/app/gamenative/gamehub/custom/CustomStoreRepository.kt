package app.gamenative.gamehub.custom

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.customStoresDataStore by preferencesDataStore(name = "game_hub_custom_stores")

/**
 * Persists the user's [CustomStoreConfig] list (the stores added via the config form) as a JSON
 * blob in a Preferences DataStore, so they survive restarts.
 */
class CustomStoreRepository(private val context: Context) {

    private val key = stringPreferencesKey("configs_json")

    /** Observable list of configured custom stores. */
    val configs: Flow<List<CustomStoreConfig>> =
        context.customStoresDataStore.data.map { CustomStoreConfig.listFromJson(it[key]) }

    suspend fun getAll(): List<CustomStoreConfig> =
        CustomStoreConfig.listFromJson(context.customStoresDataStore.data.first()[key])

    /** Add or replace (by id) a store config. */
    suspend fun upsert(config: CustomStoreConfig) {
        context.customStoresDataStore.edit { prefs ->
            val updated = CustomStoreConfig.listFromJson(prefs[key]).filter { it.id != config.id } + config
            prefs[key] = CustomStoreConfig.listToJson(updated)
        }
    }

    suspend fun remove(id: String) {
        context.customStoresDataStore.edit { prefs ->
            val updated = CustomStoreConfig.listFromJson(prefs[key]).filter { it.id != id }
            prefs[key] = CustomStoreConfig.listToJson(updated)
        }
    }
}
