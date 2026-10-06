package app.gamenative.utils

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray

/**
 * Latest Turnip (Mesa Adreno Vulkan) builds straight from their GitHub release repos, so users
 * get new drivers the day they ship instead of waiting for a manifest update.
 * Source list and asset naming adapted from DroidDeck's gpu/TurnipReleases.kt (GPL-3.0).
 * Only Android (adrenotools) builds are offered; Linux/Wayland variants are skipped.
 */
object TurnipReleases {

    data class Asset(val source: String, val tag: String, val name: String, val label: String, val url: String, val size: Long)

    private class Source(val label: String, val repo: String, val classify: (name: String, tag: String) -> String?)

    private val SOURCES = listOf(
        // Turnip-<tag>[-variant][-Linux|-Wayland].zip
        Source("Banners-Turnip", "The412Banner/Banners-Turnip") { name, tag ->
            val prefix = "Turnip-$tag"
            if (!name.startsWith(prefix) || !name.endsWith(".zip")) return@Source null
            val variant = name.removePrefix(prefix).removeSuffix(".zip")
            if (variant.endsWith("-Linux") || variant.endsWith("-Wayland")) return@Source null
            when {
                variant.isEmpty() -> "Adreno 6xx/7xx"
                variant == "-A8xx" -> "Adreno 8xx (8 Elite)"
                variant.startsWith("-710-720") -> "Adreno 710/720"
                variant.contains("OneUI", ignoreCase = true) -> "8 Gen 2 (One UI)"
                else -> variant.trimStart('-')
            }
        },
        // WN-Turnip-<ver>-<b|p>_Axxx.zip (Android); WN-Linux-… are skipped.
        Source("WinNative", "WinNative-Emu/Drivers") { name, _ ->
            val m = Regex("""^WN-Turnip-[^-]+-([a-z]+)_(\w+)\.zip$""").find(name) ?: return@Source null
            val flavour = when (m.groupValues[1]) { "b" -> "Balanced"; "p" -> "Performance"; else -> m.groupValues[1] }
            val gpus = if (m.groupValues[2] == "Axxx") "all Adreno" else m.groupValues[2]
            "$gpus · $flavour"
        },
    )

    /** Newest release of each source. Sources that fail are skipped (reported in [failed]). */
    suspend fun check(): Pair<List<Asset>, List<String>> = withContext(Dispatchers.IO) {
        val assets = ArrayList<Asset>()
        val failed = ArrayList<String>()
        for (src in SOURCES) {
            try {
                val req = Request.Builder()
                    .url("https://api.github.com/repos/${src.repo}/releases?per_page=5")
                    .header("Accept", "application/vnd.github+json")
                    .build()
                Net.http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val releases = JSONArray(resp.body?.string() ?: "[]")
                    val seen = HashSet<String>()
                    // Newest first: each variant from the newest release that still carries it.
                    for (i in 0 until releases.length()) {
                        val rel = releases.getJSONObject(i)
                        if (rel.optBoolean("draft")) continue
                        val tag = rel.optString("tag_name")
                        val list = rel.optJSONArray("assets") ?: continue
                        for (j in 0 until list.length()) {
                            val a = list.getJSONObject(j)
                            val name = a.optString("name")
                            val label = src.classify(name, tag) ?: continue
                            if (!seen.add(label)) continue
                            val url = a.optString("browser_download_url")
                            if (!url.startsWith("https://github.com/")) continue
                            assets += Asset(src.label, tag, name, label, url, a.optLong("size"))
                        }
                    }
                }
            } catch (e: Exception) {
                failed += "${src.label}: ${e.message}"
            }
        }
        assets to failed
    }

    /** Downloads [asset] into [dir]; returns the zip, ready for AdrenotoolsManager.installDriver. */
    suspend fun download(asset: Asset, dir: File, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dest = File(dir, asset.name)
        val tmp = File(dir, asset.name + ".part")
        Net.http.newCall(Request.Builder().url(asset.url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("empty body")
            val total = body.contentLength().takeIf { it > 0 } ?: asset.size
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) throw IOException("rename failed")
        dest
    }
}
