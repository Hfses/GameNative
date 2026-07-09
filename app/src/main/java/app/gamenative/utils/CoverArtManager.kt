package app.gamenative.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import timber.log.Timber
import java.io.File

/**
 * Cover-art manager for custom games.
 *
 * A custom game's cover is simply an image file in its folder ("cover"/"coverv"/"coverh" —
 * see [CustomGameScanner.findCapsuleCoverInFolder]); the user-supplied cover takes priority
 * over SteamGridDB downloads. This manager writes/removes that file from a user-picked image:
 * it decodes the source (with subsampling so a 50 MP photo doesn't OOM), downscales it to a
 * sane cover size, and saves it as an optimized JPEG. The library's folder watcher picks the
 * change up automatically.
 */
object CoverArtManager {

    /** Long-edge cap for stored covers — plenty for the hero pane, small enough to load fast. */
    private const val MAX_DIMENSION = 1440

    private val COVER_BASENAMES = listOf("cover", "coverv", "coverh")
    private val COVER_EXTENSIONS = listOf("png", "jpg", "jpeg", "webp")

    /** Whether the folder currently has a user-supplied cover file. */
    fun hasCustomCover(folder: File): Boolean =
        listCoverFiles(folder).isNotEmpty()

    /**
     * Sets [sourceUri] as the game's cover: replaces any existing cover.* files with an
     * optimized "cover.jpg". Returns null on success or a short error description.
     */
    fun setCustomCover(context: Context, folder: File, sourceUri: Uri): String? {
        if (!folder.isDirectory) return "game folder not found"
        return try {
            val bitmap = decodeScaled(context, sourceUri) ?: return "unsupported image"
            // Remove every old cover first so the new one always wins the priority scan.
            listCoverFiles(folder).forEach { it.delete() }
            val out = File(folder, "cover.jpg")
            out.outputStream().use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)) {
                    return "could not encode image"
                }
            }
            Timber.i("CoverArtManager: wrote ${out.absolutePath} (${bitmap.width}x${bitmap.height})")
            null
        } catch (e: Exception) {
            Timber.w(e, "CoverArtManager: failed to set cover in ${folder.path}")
            e.message ?: e.javaClass.simpleName
        }
    }

    /** Removes user-supplied cover files, restoring the default art resolution order. */
    fun removeCustomCover(folder: File): Boolean {
        var removed = false
        listCoverFiles(folder).forEach { removed = it.delete() || removed }
        return removed
    }

    private fun listCoverFiles(folder: File): List<File> {
        val files = folder.listFiles { f -> f.isFile } ?: return emptyList()
        return files.filter { f ->
            COVER_BASENAMES.any { base ->
                COVER_EXTENSIONS.any { ext -> f.name.equals("$base.$ext", ignoreCase = true) }
            }
        }
    }

    /** Decodes [uri] subsampled near [MAX_DIMENSION], then scales down exactly if still larger. */
    private fun decodeScaled(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while ((bounds.outWidth / (sample * 2)) >= MAX_DIMENSION || (bounds.outHeight / (sample * 2)) >= MAX_DIMENSION) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        val longEdge = maxOf(decoded.width, decoded.height)
        if (longEdge <= MAX_DIMENSION) return decoded
        val scale = MAX_DIMENSION.toFloat() / longEdge
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }
}
