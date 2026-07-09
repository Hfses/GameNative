package app.gamenative.utils

import android.content.Context
import com.winlator.container.Container
import com.winlator.contents.ContentsManager
import com.winlator.core.WineInfo
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile

/**
 * Internal Wine ↔ Box64 ↔ variant compatibility matrix with automatic correction.
 *
 * Solves two real failure classes:
 *  1. libc mismatch — a Wine/Proton build linked against one libc launched in a container using
 *     the other one. The classic symptom is the loader crash
 *     "Symbol __libc_init not found, cannot apply R_X86_64_JUMP_SLOT" (a bionic-linked binary in
 *     a glibc container; the inverse fails on missing glibc symbols like __libc_start_main).
 *  2. Box64 too old for the selected Wine/Proton series (e.g. Wine 11 needs Box64 ≥ 0.3.6).
 *
 * [checkAndAutoFix] runs pre-launch: it detects both classes BEFORE the guest process starts,
 * applies the safest compatible fallback, persists it, and returns a user-friendly explanation
 * (also appended to files/logs/runtime_compat.log so users can see what happened and why).
 */
object RuntimeCompatibility {

    enum class Libc { GLIBC, BIONIC, UNKNOWN }

    /** One row of the internal compatibility matrix. */
    data class MatrixRule(
        /** Prefix of the wine identifier this rule applies to (e.g. "proton-11", "wine-11"). */
        val winePrefix: String,
        /** Which container variant this wine build runs on, or null = both. */
        val variant: String?,
        /** Minimum Box64 version required (null = any). */
        val minBox64: String?,
    )

    /**
     * The internal matrix. Sources: versions actually shipped in arrays.xml / the manifest and
     * upstream Box64 release notes (Wine 10/11 need the newer dynarec — Box64 ≥ 0.3.6 on glibc,
     * ≥ 0.4.0 on bionic builds).
     */
    val MATRIX: List<MatrixRule> = listOf(
        // glibc containers
        MatrixRule("wine-9", Container.GLIBC, null),
        MatrixRule("wine-10", Container.GLIBC, "0.3.6"),
        MatrixRule("wine-11", Container.GLIBC, "0.3.6"),
        MatrixRule("proton-10", Container.GLIBC, "0.3.6"),
        MatrixRule("proton-11", Container.GLIBC, "0.3.6"),
        // bionic containers (arm64ec/x86_64 proton builds)
        MatrixRule("proton-9", Container.BIONIC, null),
        MatrixRule("proton-10", Container.BIONIC, "0.4.0"),
        MatrixRule("proton-11", Container.BIONIC, "0.4.2"),
        MatrixRule("wine-10", Container.BIONIC, "0.4.0"),
        MatrixRule("wine-11", Container.BIONIC, "0.4.2"),
    )

    /** Box64 versions available per variant (mirrors arrays.xml). */
    private val GLIBC_BOX64 = listOf("0.3.4", "0.3.6", "0.3.8")
    private val BIONIC_BOX64 = listOf("0.3.7", "0.4.0", "0.4.2")

    /** Fallback wine identifiers known-good per variant (mirrors arrays.xml). */
    const val FALLBACK_WINE_GLIBC = "wine-9.2-x86_64"
    const val FALLBACK_WINE_BIONIC = "proton-9.0-arm64ec"

    data class CompatFix(
        val changed: Boolean,
        /** User-facing, friendly explanation of what was wrong and what was applied. */
        val message: String? = null,
    )

    // ------------------------------------------------------------------ libc detection

    /**
     * Detects which libc a Wine build was linked against by scanning its ELF binaries for the
     * dynamic-linker strings. glibc binaries reference "libc.so.6"; bionic ones reference
     * "libc.so" and the bionic-only entry symbol "__libc_init".
     */
    fun detectWineLibc(wineDir: File?): Libc {
        if (wineDir == null || !wineDir.isDirectory) return Libc.UNKNOWN
        val candidates = listOf("bin/wine", "bin/wine64", "bin/wineserver", "bin/wineserver64")
        for (rel in candidates) {
            val f = File(wineDir, rel)
            if (!f.isFile || f.length() < 64) continue
            when (scanElfLibc(f)) {
                Libc.GLIBC -> return Libc.GLIBC
                Libc.BIONIC -> return Libc.BIONIC
                Libc.UNKNOWN -> {}
            }
        }
        return Libc.UNKNOWN
    }

    private fun scanElfLibc(file: File): Libc {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(4)
                raf.readFully(magic)
                // Not an ELF (e.g. a shell script wrapper): follow no further, just unknown.
                if (magic[0] != 0x7F.toByte() || magic[1] != 'E'.code.toByte() ||
                    magic[2] != 'L'.code.toByte() || magic[3] != 'F'.code.toByte()
                ) {
                    return Libc.UNKNOWN
                }
                // Read up to the first 1 MiB — .dynstr with the DT_NEEDED names lives early.
                val size = minOf(raf.length(), 1L shl 20).toInt()
                raf.seek(0)
                val buf = ByteArray(size)
                raf.readFully(buf)
                val hay = String(buf, Charsets.ISO_8859_1)
                val hasGlibc = hay.contains("libc.so.6") || hay.contains("__libc_start_main")
                val hasBionic = hay.contains("__libc_init") || hay.contains("liblog.so")
                when {
                    hasGlibc && !hasBionic -> Libc.GLIBC
                    hasBionic && !hasGlibc -> Libc.BIONIC
                    else -> Libc.UNKNOWN
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "RuntimeCompatibility: failed to scan ${file.path}")
            Libc.UNKNOWN
        }
    }

    /** The libc a container variant provides to guest processes. */
    fun containerLibc(container: Container): Libc =
        if (Container.BIONIC.equals(container.containerVariant, ignoreCase = true)) Libc.BIONIC else Libc.GLIBC

    // ------------------------------------------------------------------ box64 matrix

    /** Returns the minimum Box64 required by [wineIdentifier] on [variant], or null if any works. */
    fun minBox64For(wineIdentifier: String, variant: String): String? {
        val id = wineIdentifier.lowercase()
        return MATRIX.firstOrNull { rule ->
            id.startsWith(rule.winePrefix) && (rule.variant == null || rule.variant.equals(variant, true))
        }?.minBox64
    }

    /** Best available Box64 satisfying [min] for [variant] (smallest that is >= min). */
    fun pickBox64(min: String, variant: String): String {
        val pool = if (Container.BIONIC.equals(variant, true)) BIONIC_BOX64 else GLIBC_BOX64
        return pool.filter { compareVersions(it, min) >= 0 }.minWithOrNull(::compareVersions) ?: pool.last()
    }

    /** Compares dotted version strings numerically ("0.3.6" < "0.4.0" < "0.4.2"). */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.', '-').mapNotNull { it.toIntOrNull() }
        val pb = b.split('.', '-').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /**
     * Returns the Box64 auto-fix (if any) for selecting [wineIdentifier] with [currentBox64] on
     * [variant] — pure check used by the config UI so it can warn before applying.
     */
    fun box64FixFor(wineIdentifier: String, variant: String, currentBox64: String): String? {
        val min = minBox64For(wineIdentifier, variant) ?: return null
        if (compareVersions(currentBox64, min) >= 0) return null
        return pickBox64(min, variant)
    }

    // ------------------------------------------------------------------ pre-launch guard

    /**
     * Pre-launch guard. Detects libc mismatches and too-old Box64 for the container's Wine and
     * fixes both IN PLACE (persisting the container) so the guest never crashes with the
     * "Symbol __libc_init not found" class of loader errors. Returns what was changed, with a
     * friendly message for the UI, and appends the incident to files/logs/runtime_compat.log.
     */
    @JvmStatic
    fun checkAndAutoFix(context: Context, container: Container, contentsManager: ContentsManager): CompatFix {
        val messages = mutableListOf<String>()
        val variant = container.containerVariant ?: Container.GLIBC
        val wineId = container.wineVersion ?: return CompatFix(false)

        // 1. libc mismatch (the __libc_init crash) — detect from the actual binaries.
        val wineInfo = runCatching { WineInfo.fromIdentifier(context, contentsManager, wineId) }.getOrNull()
        val wineDir = wineInfo?.path?.takeIf { it.isNotEmpty() }?.let(::File)
        val needLibc = containerLibc(container)
        val wineLibc = detectWineLibc(wineDir)
        if (wineLibc != Libc.UNKNOWN && wineLibc != needLibc) {
            val fallback = if (needLibc == Libc.BIONIC) FALLBACK_WINE_BIONIC else FALLBACK_WINE_GLIBC
            messages += context.getString(
                app.gamenative.R.string.runtime_compat_wine_libc_fixed,
                wineId, wineLibc.name.lowercase(), variant, fallback,
            )
            container.wineVersion = fallback
        }

        // 2. Box64 minimum for the (possibly corrected) wine series.
        val effectiveWine = container.wineVersion ?: wineId
        val minBox64 = minBox64For(effectiveWine, variant)
        val currentBox64 = container.box64Version ?: ""
        if (minBox64 != null && currentBox64.isNotEmpty() && compareVersions(currentBox64, minBox64) < 0) {
            val pick = pickBox64(minBox64, variant)
            messages += context.getString(
                app.gamenative.R.string.runtime_compat_box64_adjusted,
                effectiveWine, minBox64, pick,
            )
            container.box64Version = pick
        }

        if (messages.isEmpty()) return CompatFix(false)

        runCatching { container.saveData() }
        val fullMessage = messages.joinToString("\n")
        Timber.w("RuntimeCompatibility: $fullMessage")
        appendFriendlyLog(context, fullMessage)
        return CompatFix(true, fullMessage)
    }

    /** Appends an incident to a human-readable log the user can consult. */
    private fun appendFriendlyLog(context: Context, message: String) {
        runCatching {
            val dir = File(context.filesDir, "logs").apply { mkdirs() }
            File(dir, "runtime_compat.log").appendText(
                "[${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}]\n$message\n\n",
            )
        }
    }
}
