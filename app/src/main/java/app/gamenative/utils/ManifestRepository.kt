package app.gamenative.utils

import android.content.Context
import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request
import timber.log.Timber

object ManifestRepository {
    private const val ONE_DAY_MS = 24 * 60 * 60 * 1000L
    private const val MANIFEST_URL = "https://raw.githubusercontent.com/utkarshdalal/GameNative/refs/heads/master/manifest.json"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun loadManifest(context: Context): ManifestData {
        // The bundled asset is a curated base catalog that always works offline; the upstream and any
        // user-supplied custom source are merged on top of it for extra download options.
        var result = readLocalManifest(context) ?: ManifestData.empty()
        if (BuildConfig.DEBUG && result.items.isNotEmpty()) {
            Timber.i("ManifestRepository: using local debug manifest as base")
        }

        result = result.merge(loadUpstreamManifest())
        result = result.merge(loadCustomManifest())
        return result
    }

    /** Upstream catalog, cached for a day in PrefManager. */
    private suspend fun loadUpstreamManifest(): ManifestData {
        val cachedJson = PrefManager.componentManifestJson
        val cachedManifest = parseManifest(cachedJson) ?: ManifestData.empty()
        val lastFetchedAt = PrefManager.componentManifestFetchedAt
        val isStale = System.currentTimeMillis() - lastFetchedAt >= ONE_DAY_MS

        if (cachedJson.isNotEmpty() && !isStale) {
            return cachedManifest
        }

        val fetched = fetchManifestJson(MANIFEST_URL)
        if (fetched != null) {
            val parsed = parseManifest(fetched)
            if (parsed != null) {
                PrefManager.componentManifestJson = fetched
                PrefManager.componentManifestFetchedAt = System.currentTimeMillis()
                return parsed
            }
        }
        return cachedManifest
    }

    /** Optional user-configured extra source (PrefManager.customComponentManifestUrl), cached. */
    private suspend fun loadCustomManifest(): ManifestData {
        val url = PrefManager.customComponentManifestUrl.trim()
        if (url.isEmpty() || !(url.startsWith("https://") || url.startsWith("http://"))) {
            return ManifestData.empty()
        }
        val fetched = fetchManifestJson(url)
        if (fetched != null) {
            parseManifest(fetched)?.let {
                PrefManager.customComponentManifestJson = fetched
                return it
            }
        }
        // Fall back to the last good custom manifest if the source is unreachable this run.
        return parseManifest(PrefManager.customComponentManifestJson) ?: ManifestData.empty()
    }

    private suspend fun fetchManifestJson(url: String): String? = withContext(Dispatchers.IO) {
    try {
        val request = Request.Builder().url(url).build()
        Net.http.newCall(request).execute().use { response ->
            response.takeIf { it.isSuccessful }?.body?.string()
        }
    } catch (e: Exception) {
        Timber.e(e, "ManifestRepository: fetch failed for $url")
        null
    }
}

    private fun readLocalManifest(context: Context): ManifestData? = try {
        context.assets.open("manifest.json").bufferedReader().use { parseManifest(it.readText()) }
    } catch (e: Exception) {
        null
    }

    fun parseManifest(jsonString: String?): ManifestData? {
        if (jsonString.isNullOrBlank()) return null
        return try {
            json.decodeFromString<ManifestData>(jsonString)
        } catch (e: Exception) {
            Timber.e(e, "ManifestRepository: parse failed")
            null
        }
    }
}
