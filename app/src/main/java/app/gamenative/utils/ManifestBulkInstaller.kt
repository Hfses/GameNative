package app.gamenative.utils

import android.content.Context
import com.winlator.contents.ContentProfile
import timber.log.Timber

/**
 * Downloads and installs EVERY component listed in the remote manifest (all Wine/Proton, DXVK,
 * VKD3D, Box64/WoWBox64, FEXCore versions and GPU drivers) in one sweep, so a user can pre-fetch
 * everything instead of installing each version by hand.
 *
 * Reuses [ManifestInstaller] per entry (each already handles its own download, caching and failure),
 * runs sequentially to avoid saturating the network/disk, and never throws: a failed entry is
 * counted and the sweep continues, returning an installed/failed tally.
 *
 * Note: this can pull several GB. Callers should confirm with the user and ideally gate on Wi-Fi.
 */
object ManifestBulkInstaller {

    /** (isDriver, contentType) for a manifest type key, or null contentType for a type we skip. */
    private fun classify(typeKey: String): Pair<Boolean, ContentProfile.ContentType?> =
        when (typeKey.lowercase()) {
            ManifestContentTypes.DRIVER -> true to null
            ManifestContentTypes.DXVK -> false to ContentProfile.ContentType.CONTENT_TYPE_DXVK
            ManifestContentTypes.VKD3D -> false to ContentProfile.ContentType.CONTENT_TYPE_VKD3D
            ManifestContentTypes.BOX64 -> false to ContentProfile.ContentType.CONTENT_TYPE_BOX64
            ManifestContentTypes.WOWBOX64 -> false to ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64
            ManifestContentTypes.FEXCORE -> false to ContentProfile.ContentType.CONTENT_TYPE_FEXCORE
            ManifestContentTypes.WINE -> false to ContentProfile.ContentType.CONTENT_TYPE_WINE
            ManifestContentTypes.PROTON -> false to ContentProfile.ContentType.CONTENT_TYPE_PROTON
            else -> false to null
        }

    data class Progress(
        val currentName: String,
        val index: Int,
        val total: Int,
        /** 0..1 download fraction of the current item. */
        val itemFraction: Float,
    )

    data class Result(val installed: Int, val failed: Int, val total: Int)

    /** How many components the manifest would install (for a confirmation prompt). */
    suspend fun count(context: Context): Int = buildJobs(context).size

    private suspend fun buildJobs(
        context: Context,
    ): List<Triple<ManifestEntry, Boolean, ContentProfile.ContentType?>> {
        val manifest = ManifestRepository.loadManifest(context)
        return manifest.items.flatMap { (typeKey, entries) ->
            val (isDriver, type) = classify(typeKey)
            if (!isDriver && type == null) {
                Timber.w("ManifestBulkInstaller: skipping unknown manifest type '$typeKey'")
                emptyList()
            } else {
                entries.map { Triple(it, isDriver, type) }
            }
        }
    }

    suspend fun installAll(context: Context, onProgress: (Progress) -> Unit): Result {
        val jobs = buildJobs(context)
        var installed = 0
        var failed = 0
        jobs.forEachIndexed { i, (entry, isDriver, type) ->
            onProgress(Progress(entry.name, i + 1, jobs.size, 0f))
            val result = runCatching {
                ManifestInstaller.installManifestEntry(context, entry, isDriver, type) { f ->
                    onProgress(Progress(entry.name, i + 1, jobs.size, f))
                }
            }.getOrElse {
                Timber.e(it, "ManifestBulkInstaller: entry '${entry.name}' failed")
                null
            }
            if (result?.success == true) installed++ else failed++
        }
        return Result(installed = installed, failed = failed, total = jobs.size)
    }
}
