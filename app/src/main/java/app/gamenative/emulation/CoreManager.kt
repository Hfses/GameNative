package app.gamenative.emulation

import android.content.Context
import android.os.Build
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Downloads libretro cores on demand from the official libretro buildbot, so the APK doesn't
 * carry ~20 emulators nobody asked for. Cores live in app-private storage (must be executable
 * memory, so not external storage).
 */
object CoreManager {

    private const val BUILDBOT = "https://buildbot.libretro.com/nightly/android/latest"
    private const val MAX_CORE_BYTES = 200L * 1024 * 1024

    private fun abi(): String =
        if (Build.SUPPORTED_ABIS.contains("arm64-v8a")) "arm64-v8a" else "armeabi-v7a"

    private fun coresDir(context: Context) = File(context.filesDir, "libretro/cores").apply { mkdirs() }

    fun coreFile(context: Context, system: ConsoleSystem): File? =
        system.coreName?.let { File(coresDir(context), "${it}_libretro_android.so") }

    fun isInstalled(context: Context, system: ConsoleSystem): Boolean =
        coreFile(context, system)?.let { it.isFile && it.length() > 0 } == true

    /** Directory where BIOS/firmware files go (libretro "system" dir), shared by all cores. */
    fun systemDir(context: Context) = File(context.getExternalFilesDir(null) ?: context.filesDir, "console/system").apply { mkdirs() }

    fun savesDir(context: Context, system: ConsoleSystem) =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "console/saves/${system.id}").apply { mkdirs() }

    fun statesDir(context: Context, system: ConsoleSystem) =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "console/states/${system.id}").apply { mkdirs() }

    /**
     * Downloads and unpacks the core. [onProgress] gets 0f..1f (or -1f when size is unknown).
     * Writes to a temp file and renames, so a half-downloaded core is never loaded.
     */
    suspend fun install(context: Context, system: ConsoleSystem, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val name = system.coreName ?: throw IOException("${system.displayName}: sem core disponível")
        val target = coreFile(context, system)!!
        val tmp = File(target.parentFile, target.name + ".part")
        val url = URL("$BUILDBOT/${abi()}/${name}_libretro_android.so.zip")
        Timber.tag("CoreManager").i("Downloading core $url")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${conn.responseCode} ao baixar o core $name")
            }
            val total = conn.contentLengthLong
            var read = 0L
            var extracted = false
            ZipInputStream(CountingStream(conn.inputStream) { n ->
                read = n
                onProgress(if (total > 0) (n.toFloat() / total).coerceIn(0f, 1f) else -1f)
            }).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".so")) {
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var written = 0L
                            while (true) {
                                val n = zip.read(buf)
                                if (n < 0) break
                                written += n
                                if (written > MAX_CORE_BYTES) throw IOException("core grande demais")
                                out.write(buf, 0, n)
                            }
                        }
                        extracted = true
                        break
                    }
                    entry = zip.nextEntry
                }
            }
            if (!extracted) throw IOException("Arquivo do core $name inválido")
            if (!tmp.renameTo(target)) throw IOException("Falha ao salvar o core $name")
            Timber.tag("CoreManager").i("Installed core $name (${target.length()} bytes, $read downloaded)")
        } finally {
            conn.disconnect()
            tmp.delete()
        }
    }

    fun uninstall(context: Context, system: ConsoleSystem) {
        coreFile(context, system)?.delete()
    }

    private class CountingStream(
        private val inner: java.io.InputStream,
        private val onCount: (Long) -> Unit,
    ) : java.io.FilterInputStream(inner) {
        private var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) report(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) report(it.toLong()) }
        private fun report(n: Long) {
            count += n
            onCount(count)
        }
    }
}
