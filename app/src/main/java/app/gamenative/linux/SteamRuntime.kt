package app.gamenative.linux

import android.content.Context
import android.os.StatFs
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.json.JSONObject
import timber.log.Timber

/**
 * Linux mode stage 5: the Steam ARM runtime — DroidDeck/Bannerlator's Arch Linux ARM rootfs with
 * gamescope, Xwayland, Mesa Turnip (KGSL) and the session scripts that fetch Valve's native arm64
 * Steam client on first run. Contains no Valve software. GPL-3.0; published by The412Banner.
 *
 * Installed to files/linux/steam. The download is checked against the SHA-256 the manifest gives.
 */
object SteamRuntime {
    private const val MANIFEST_URL =
        "https://raw.githubusercontent.com/The412Banner/winlator-contents/main/linuxfs.json"
    /** Room for the download, the unpacked rootfs and Steam's first update. */
    private const val REQUIRED_FREE_BYTES = 6L * 1024 * 1024 * 1024

    data class Manifest(val release: String, val url: String, val sha256: String, val size: Long)

    fun rootDir(context: Context) = File(context.filesDir, "linux/steam")
    private fun marker(context: Context) = File(rootDir(context), ".gamenative-release")

    fun installedRelease(context: Context): String? = marker(context).takeIf { it.isFile }?.readText()?.trim()
    fun isInstalled(context: Context) = installedRelease(context) != null

    suspend fun fetchManifest(): Manifest = withContext(Dispatchers.IO) {
        val json = JSONObject(URL(MANIFEST_URL).readText())
        val url = json.getString("url")
        require(url.startsWith("https://github.com/")) { "unexpected runtime URL: $url" }
        Manifest(json.getString("release"), url, json.getString("sha256").lowercase(), json.optLong("size"))
    }

    suspend fun install(context: Context, onProgress: (Float, String) -> Unit) = withContext(Dispatchers.IO) {
        val free = StatFs(context.filesDir.path).availableBytes
        if (free < REQUIRED_FREE_BYTES) {
            throw IOException("Espaço insuficiente: precisa de ~6 GB livres (há ${free / (1024 * 1024)} MB).")
        }
        onProgress(-1f, "Consultando o runtime…")
        val m = fetchManifest()
        val linuxDir = rootDir(context).parentFile!!.apply { mkdirs() }
        val archive = File(linuxDir, "steam-runtime.tar.zst.part")
        try {
            download(m, archive, onProgress)
            onProgress(-1f, "Extraindo (pode levar alguns minutos)…")
            val staging = File(linuxDir, "steam.staging").apply { deleteRecursively(); mkdirs() }
            LinuxEnvironment.extractTar(
                TarArchiveInputStream(ZstdCompressorInputStream(BufferedInputStream(archive.inputStream(), 1 shl 20))),
                staging,
            )
            rootDir(context).deleteRecursively()
            if (!staging.renameTo(rootDir(context))) throw IOException("Falha ao instalar o runtime")
            File(rootDir(context), "etc/resolv.conf").apply { delete(); writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n") }
            marker(context).writeText(m.release)
            Timber.tag("SteamRuntime").i("installed ${m.release}")
        } finally {
            archive.delete()
        }
    }

    private fun download(m: Manifest, dest: File, onProgress: (Float, String) -> Unit) {
        val conn = (URL(m.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: m.size
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total, "Baixando runtime Steam (${done shr 20} / ${total shr 20} MB)…")
                    }
                }
            }
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            if (got != m.sha256) throw IOException("Arquivo corrompido (SHA-256 não confere). Tente de novo.")
        } finally {
            conn.disconnect()
        }
    }

    fun uninstall(context: Context) = rootDir(context).deleteRecursively()
}
