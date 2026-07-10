package app.gamenative.utils

import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal PE (Windows exe/dll) header reader used by the [LayerMinimizer]: reads the CPU
 * architecture and the imported/delay-imported DLL names so we can pick the minimal translation
 * layer stack per game (skip a layer the game doesn't use → FPS). The header walk mirrors
 * [ExeIconExtractor] (DOS e_lfanew → PE signature → COFF → optional header → section table).
 *
 * Reads at most a few hundred KB per file and never throws — on any malformed/packed input it
 * returns null (parseOk=false) so callers treat the result as UNKNOWN and keep the safe default.
 */
data class PEFacts(
    val machine: Int,
    val is64Bit: Boolean,
    val isNativeArm: Boolean,
    val isDll: Boolean,
    val isGuiSubsystem: Boolean,
    val importedDlls: Set<String>,
    val parseOk: Boolean,
)

object PEInspector {
    private const val MAX_HEADER_READ = 8192
    private const val MAX_IMPORT_BYTES = 256 * 1024

    private const val MACHINE_I386 = 0x014C
    private const val MACHINE_AMD64 = 0x8664
    private const val MACHINE_ARM64 = 0xAA64
    private const val MACHINE_ARM64X = 0xA64E

    private val GRAPHICS_DLLS = setOf(
        "d3d8.dll", "d3d9.dll", "d3d10.dll", "d3d10_1.dll", "d3d10core.dll",
        "d3d11.dll", "d3d12.dll", "d3d12core.dll", "dxgi.dll",
        "opengl32.dll", "vulkan-1.dll", "ddraw.dll", "d3drm.dll",
    )

    fun Set<String>.hasAnyGraphicsDll(): Boolean = any { it in GRAPHICS_DLLS }

    /** Parse a single PE file. Returns null on any error / non-PE input. */
    fun inspect(file: File): PEFacts? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val fileSize = raf.length()
            if (fileSize < 0x100) return@use null

            val headerSize = MAX_HEADER_READ.toLong().coerceAtMost(fileSize).toInt()
            val hdr = ByteArray(headerSize)
            raf.readFully(hdr)
            val hb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)

            val peOff = hb.getInt(0x3C)
            if (peOff <= 0 || peOff + 24 > headerSize) return@use null
            if (hb.get(peOff).toInt() != 'P'.code || hb.get(peOff + 1).toInt() != 'E'.code ||
                hb.get(peOff + 2).toInt() != 0 || hb.get(peOff + 3).toInt() != 0
            ) return@use null

            val coff = peOff + 4
            val machine = hb.getShort(coff).toInt() and 0xFFFF
            val numSections = hb.getShort(coff + 2).toInt() and 0xFFFF
            val sizeOfOptional = hb.getShort(coff + 16).toInt() and 0xFFFF
            val characteristics = hb.getShort(coff + 18).toInt() and 0xFFFF
            val optStart = coff + 20
            if (optStart + 70 > headerSize) return@use null
            val magic = hb.getShort(optStart).toInt() and 0xFFFF
            val dirStart = optStart + when (magic) {
                0x10B -> 96
                0x20B -> 112
                else -> return@use null
            }
            if (dirStart + 16 * 8 > headerSize) return@use null
            val subsystem = hb.getShort(optStart + 68).toInt() and 0xFFFF

            val importRva = hb.getInt(dirStart + 1 * 8)
            val delayRva = hb.getInt(dirStart + 13 * 8)

            // Section table (may extend past our first read — re-read if needed).
            val secTable = optStart + sizeOfOptional
            val secBuf: ByteBuffer
            if (secTable + numSections * 40 > headerSize) {
                val needed = secTable + numSections * 40
                if (needed > fileSize || needed > MAX_IMPORT_BYTES) return@use null
                val big = ByteArray(needed)
                raf.seek(0); raf.readFully(big)
                secBuf = ByteBuffer.wrap(big).order(ByteOrder.LITTLE_ENDIAN)
            } else {
                secBuf = hb
            }
            val sections = ArrayList<IntArray>(numSections)
            for (i in 0 until numSections) {
                val b = secTable + i * 40
                if (b + 24 > secBuf.capacity()) break
                // va, rawSize, rawPtr, virtualSize
                sections.add(intArrayOf(secBuf.getInt(b + 12), secBuf.getInt(b + 16), secBuf.getInt(b + 20), secBuf.getInt(b + 8)))
            }

            val imports = HashSet<String>()
            imports += readImportNames(raf, fileSize, sections, importRva, descSize = 20, nameField = 12)
            imports += readImportNames(raf, fileSize, sections, delayRva, descSize = 32, nameField = 4)

            val is64 = magic == 0x20B
            val nativeArm = machine == MACHINE_ARM64 || machine == MACHINE_ARM64X
            PEFacts(
                machine = machine,
                is64Bit = is64,
                isNativeArm = nativeArm,
                isDll = (characteristics and 0x2000) != 0,
                isGuiSubsystem = subsystem == 2,
                importedDlls = imports,
                parseOk = true,
            )
        }
    }.onFailure { Timber.w(it, "PEInspector: failed on ${file.name}") }.getOrNull()

    /**
     * Inspect the exe plus its sibling engine DLLs (>1 MB) when the exe itself imports no graphics
     * DLL — Unity/UE launchers import almost nothing; the real d3d imports live in UnityPlayer.dll
     * / the engine module next to it.
     */
    fun inspectModuleSet(exe: File): PEFacts? {
        val main = inspect(exe) ?: return null
        if (main.importedDlls.hasAnyGraphicsDll()) return main
        val siblings = exe.parentFile?.listFiles { f ->
            f.isFile && f.extension.equals("dll", true) && f.length() > 1_000_000L
        }?.sortedByDescending { it.length() }?.take(6).orEmpty()
        val extra = siblings.mapNotNull { inspect(it)?.importedDlls }.flatten().toSet()
        return if (extra.isEmpty()) main else main.copy(importedDlls = main.importedDlls + extra)
    }

    private fun rvaToOffset(rva: Int, sections: List<IntArray>): Long {
        for (s in sections) {
            val va = s[0].toLong() and 0xFFFFFFFFL
            val rawSize = s[1].toLong() and 0xFFFFFFFFL
            val rawPtr = s[2].toLong() and 0xFFFFFFFFL
            val vSize = s[3].toLong() and 0xFFFFFFFFL
            val r = rva.toLong() and 0xFFFFFFFFL
            if (r >= va && r < va + maxOf(rawSize, vSize) && rawPtr > 0) {
                return r - va + rawPtr
            }
        }
        return -1
    }

    private fun readImportNames(
        raf: RandomAccessFile,
        fileSize: Long,
        sections: List<IntArray>,
        tableRva: Int,
        descSize: Int,
        nameField: Int,
    ): Set<String> {
        if (tableRva == 0) return emptySet()
        val tableOff = rvaToOffset(tableRva, sections)
        if (tableOff < 0 || tableOff >= fileSize) return emptySet()
        val out = HashSet<String>()
        var descOff = tableOff
        var guard = 0
        while (guard++ < 4096) {
            if (descOff + descSize > fileSize) break
            val desc = ByteArray(descSize)
            raf.seek(descOff); raf.readFully(desc)
            val db = ByteBuffer.wrap(desc).order(ByteOrder.LITTLE_ENDIAN)
            // A whole-zero descriptor terminates the list.
            if (desc.all { it.toInt() == 0 }) break
            val nameRva = db.getInt(nameField)
            val nameOff = rvaToOffset(nameRva, sections)
            if (nameOff in 0 until fileSize) {
                readCString(raf, nameOff)?.lowercase()?.let { if (it.endsWith(".dll")) out.add(it) }
            }
            descOff += descSize
        }
        return out
    }

    private fun readCString(raf: RandomAccessFile, offset: Long): String? {
        raf.seek(offset)
        val sb = StringBuilder()
        var i = 0
        while (i++ < 260) {
            val b = raf.read()
            if (b <= 0) break
            sb.append(b.toChar())
        }
        return sb.toString().ifEmpty { null }
    }
}
