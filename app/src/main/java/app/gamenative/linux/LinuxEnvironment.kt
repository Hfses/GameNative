package app.gamenative.linux

import android.content.Context
import android.os.Environment
import android.system.Os
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import timber.log.Timber

/**
 * A full ARM64 Ubuntu userland inside the app (stage 1 of the Linux mode): root shell via
 * Termux's PRoot with fake root (-0), working apt, internet and access to shared storage.
 *
 * PRoot must ship inside the APK (Android 10+ forbids executing downloaded binaries), built by
 * tools/linux-proot/build.sh into jniLibs as libprootlinux.so. Launch flags mirror DroidDeck's
 * LinuxRuntime (GPL-3.0).
 */
object LinuxEnvironment {

    /** Official Ubuntu Base 24.04 arm64 rootfs (~28 MB); point releases tried newest first. */
    private val ROOTFS_URLS = listOf(
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz",
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.2-base-arm64.tar.gz",
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.1-base-arm64.tar.gz",
    )

    fun rootDir(context: Context) = File(context.filesDir, "linux/ubuntu")
    private fun prootBinary(context: Context) = File(context.applicationInfo.nativeLibraryDir, "libprootlinux.so")
    private fun prootLoader(context: Context) = File(context.applicationInfo.nativeLibraryDir, "libprootlinux-loader.so")

    fun prootAvailable(context: Context) = prootBinary(context).isFile
    fun isInstalled(context: Context) = File(rootDir(context), "usr/bin/bash").exists() &&
        File(rootDir(context), ".gamenative-ready").isFile

    /** Downloads + unpacks the rootfs and applies Android fixes. [onProgress] 0..1, -1 = unknown. */
    suspend fun install(context: Context, onProgress: (Float, String) -> Unit) = withContext(Dispatchers.IO) {
        val root = rootDir(context)
        val staging = File(root.parentFile, "ubuntu.staging")
        staging.deleteRecursively()
        staging.mkdirs()
        var lastError: Exception? = null
        for (url in ROOTFS_URLS) {
            try {
                onProgress(-1f, "Baixando Ubuntu…")
                downloadAndExtract(url, staging, onProgress)
                lastError = null
                break
            } catch (e: Exception) {
                Timber.tag("LinuxEnv").w(e, "rootfs $url failed")
                lastError = e
                staging.deleteRecursively()
                staging.mkdirs()
            }
        }
        lastError?.let { throw IOException("Falha ao baixar o Ubuntu: ${it.message}", it) }
        onProgress(-1f, "Configurando…")
        configure(staging)
        root.deleteRecursively()
        if (!staging.renameTo(root)) throw IOException("Falha ao instalar o Linux")
        File(root, ".gamenative-ready").writeText("1")
    }

    fun uninstall(context: Context) {
        rootDir(context).parentFile?.deleteRecursively()
    }

    private fun downloadAndExtract(url: String, dest: File, onProgress: (Float, String) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
        }
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            var read = 0L
            val counting = object : java.io.FilterInputStream(conn.inputStream) {
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, len).also { n ->
                        if (n > 0) {
                            read += n
                            if (total > 0) onProgress(read.toFloat() / total, "Baixando Ubuntu…")
                        }
                    }
            }
                        TarArchiveInputStream(GZIPInputStream(BufferedInputStream(counting, 256 * 1024))).use { tar ->
                val hardLinks = ArrayList<Pair<File, File>>()
                while (true) {
                    val entry: TarArchiveEntry = tar.nextEntry ?: break
                    val out = File(dest, entry.name)
                    // Reject entries escaping the rootfs.
                    // Check the entry's own path (not its link target); normalize without following links.
                    val norm = File(dest, entry.name).toPath().normalize().toString() + File.separator
                    if (!norm.startsWith(dest.path + File.separator)) continue
                    when {
                        entry.isDirectory -> {
                            out.mkdirs()
                            chmod(out, entry.mode or 0b111_000_000) // keep dirs owner-writable
                        }
                        entry.isSymbolicLink -> {
                            out.parentFile?.mkdirs()
                            runCatching { out.delete() }
                            runCatching { Os.symlink(entry.linkName, out.path) }
                        }
                        entry.isLink -> hardLinks += out to File(dest, entry.linkName)
                        entry.isFile -> {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { tar.copyTo(it, 64 * 1024) }
                            chmod(out, entry.mode or 0b110_000_000)
                        }
                    }
                }
                // Android denies hard links to apps: materialize them as copies.
                for ((link, target) in hardLinks) {
                    runCatching {
                        link.parentFile?.mkdirs()
                        target.copyTo(link, overwrite = true)
                        chmod(link, 0b111_101_101)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun chmod(f: File, mode: Int) {
        runCatching { Os.chmod(f.path, mode and 0b111_111_111) }
    }

    /** Android-specific fixes so apt, DNS and common tools work under proot. */
    private fun configure(root: File) {
        File(root, "etc/resolv.conf").apply { delete(); writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n") }
        File(root, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost\n127.0.1.1 gamenative\n")
        File(root, "etc/hostname").writeText("gamenative\n")
        // apt drops to the _apt user for downloads; Android's seccomp blocks that switch.
        File(root, "etc/apt/apt.conf.d").mkdirs()
        File(root, "etc/apt/apt.conf.d/99gamenative").writeText(
            "APT::Sandbox::User \"root\";\nAcquire::Retries \"3\";\n",
        )
        // Unknown Android group ids would spam "cannot find name for group ID".
        File(root, "etc/group").appendText("aid_inet:x:3003:root\naid_net_raw:x:3004:root\n")
        File(root, "tmp").apply { mkdirs(); chmod(this, 0b111_111_111) }
        File(root, "root").mkdirs()
        File(root, "root/.bashrc").appendText(
            "\nexport DEBIAN_FRONTEND=noninteractive\nexport LANG=C.UTF-8\nalias apt='apt -y'\n",
        )
        // Fake /proc entries Android hides from apps (top, free, apt and glibc read them).
        val fake = File(root, "etc/gamenative/proc").apply { mkdirs() }
        File(fake, "loadavg").writeText("0.12 0.07 0.02 2/165 765\n")
        File(fake, "stat").writeText("cpu  1957 0 2877 93280 262 342 254 87 0 0\nbtime 1700000000\n")
        File(fake, "uptime").writeText("124.08 932.80\n")
        File(fake, "version").writeText("Linux version 6.1.0-gamenative (proot) #1 SMP PREEMPT\n")
        File(fake, "vmstat").writeText("nr_free_pages 146031\n")
        File(root, "etc/gamenative/empty").mkdirs()
    }

    /**
     * Starts an interactive root shell. stdin/stdout are pipes (no PTY): fine for commands,
     * apt, curl, git, python; full-screen apps (vim, htop) need a real terminal (stage 2).
     */
    fun startShell(context: Context): Process {
        val root = rootDir(context)
        val cmd = ArrayList<String>()
        cmd += prootBinary(context).path
        cmd += listOf("--kill-on-exit", "--link2symlink", "-0", "-r", root.path, "-w", "/root")
        fun bind(spec: String) { cmd += "-b"; cmd += spec }
        bind("/dev"); bind("/proc"); bind("/sys")
        bind("/dev/urandom:/dev/random")
        bind("/proc/self/fd:/dev/fd")
        bind("/proc/self/fd/0:/dev/stdin")
        bind("/proc/self/fd/1:/dev/stdout")
        bind("/proc/self/fd/2:/dev/stderr")
        bind("${root.path}/etc/gamenative/empty:/sys/fs/selinux")
        val fake = File(root, "etc/gamenative/proc")
        for (name in listOf("loadavg", "stat", "uptime", "version", "vmstat")) {
            if (!File("/proc/$name").canRead()) bind("${File(fake, name).path}:/proc/$name")
        }
        val shm = File(context.cacheDir, "linux-shm").apply { mkdirs() }
        bind("${shm.path}:/dev/shm")
        // Shared storage (Downloads, game folders) at /sdcard, like Termux.
        val storage = Environment.getExternalStorageDirectory()
        if (storage.canRead()) bind("${storage.path}:/sdcard")
        cmd += listOf(
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "TERM=dumb", "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "DEBIAN_FRONTEND=noninteractive",
            "/bin/bash", "--login", "-s",
        )
        val pb = ProcessBuilder(cmd).redirectErrorStream(true).directory(root)
        pb.environment().apply {
            clear()
            put("PROOT_LOADER", prootLoader(context).path)
            put("PROOT_TMP_DIR", context.cacheDir.path)
            put("PATH", "/system/bin")
        }
        return pb.start()
    }
}
