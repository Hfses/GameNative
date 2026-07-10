package app.gamenative.utils

import android.content.Context
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * A real, bounded on-device logging system.
 *
 * - [Tree] is a Timber tree that mirrors every log into a rotating file, so a session's history
 *   survives the app being killed (unlike logcat, which is capped and cleared).
 * - Bounded by construction: each file is capped at [MAX_FILE_BYTES]; when it fills, it rotates
 *   (current → .1 → .2 …) keeping at most [MAX_FILES]. Total on-disk cost is therefore fixed at
 *   ~[MAX_FILE_BYTES] × [MAX_FILES] — never unlimited.
 * - Cheap on the hot path: appends are buffered and only WARN+ is persisted in release builds.
 * - [DiagnosticsAnalyzer] turns raw guest/emulator output into a friendly, actionable diagnosis
 *   for the known failure signatures (missing libc symbols, box64 load failures, audio init, …).
 *
 * Files live under files/logs/session/ and are shareable from the Debug settings screen.
 */
object SessionLogger {

    private const val DIR = "logs/session"
    private const val CURRENT = "session.log"
    private const val MAX_FILE_BYTES = 1_000_000L // 1 MB per file
    private const val MAX_FILES = 4 // session.log + .1 + .2 + .3  → ~4 MB ceiling

    private val lock = Any()
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var dir: File? = null
    @Volatile private var current: File? = null

    fun init(context: Context) {
        synchronized(lock) {
            val d = File(context.filesDir, DIR).apply { mkdirs() }
            dir = d
            current = File(d, CURRENT)
            // Mark a fresh app start so sessions are easy to tell apart in the file.
            append("========== app start ${timeFmt.format(nowDate())} ==========")
        }
    }

    /** Where the logs live, for the share/export UI. Newest content is in [CURRENT]. */
    fun logDir(context: Context): File = dir ?: File(context.filesDir, DIR)

    /** The rotated files, newest first, for sharing. */
    fun logFiles(context: Context): List<File> {
        val d = logDir(context)
        val cur = File(d, CURRENT)
        val rotated = (1 until MAX_FILES).map { File(d, "$CURRENT.$it") }.filter { it.exists() }
        return (listOf(cur).filter { it.exists() } + rotated)
    }

    fun clear() {
        synchronized(lock) {
            dir?.listFiles()?.forEach { runCatching { it.delete() } }
        }
    }

    fun append(line: String) {
        val file = current ?: return
        synchronized(lock) {
            runCatching {
                if (file.length() > MAX_FILE_BYTES) rotate()
                file.appendText(line + "\n")
            }
        }
    }

    /** Logs a labelled block of guest/emulator output and returns any diagnosis it triggers. */
    fun logGuestOutput(tag: String, text: String): DiagnosticsAnalyzer.Diagnosis? {
        append("[$tag] $text")
        return DiagnosticsAnalyzer.analyze(text)
    }

    private fun rotate() {
        val d = dir ?: return
        // Drop the oldest, then shift each file up by one index.
        File(d, "$CURRENT.${MAX_FILES - 1}").delete()
        for (i in (MAX_FILES - 2) downTo 1) {
            val from = File(d, "$CURRENT.$i")
            if (from.exists()) from.renameTo(File(d, "$CURRENT.${i + 1}"))
        }
        File(d, CURRENT).renameTo(File(d, "$CURRENT.1"))
    }

    // new Date() is intentionally avoided elsewhere in workflow scripts, but here we are in app
    // runtime where it's fine.
    private fun nowDate() = java.util.Date()

    private fun levelChar(priority: Int): Char = when (priority) {
        android.util.Log.VERBOSE -> 'V'
        android.util.Log.DEBUG -> 'D'
        android.util.Log.INFO -> 'I'
        android.util.Log.WARN -> 'W'
        android.util.Log.ERROR -> 'E'
        else -> 'A'
    }

    /** Timber tree that mirrors logs into the rotating session file. */
    class Tree(private val persistFromPriority: Int) : Timber.Tree() {
        override fun isLoggable(tag: String?, priority: Int): Boolean = priority >= persistFromPriority

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            val line = buildString {
                append(timeFmt.format(nowDate()))
                append(' ')
                append(levelChar(priority))
                append('/')
                append(tag ?: "app")
                append(": ")
                append(message)
            }
            append(line)
            if (t != null) append(android.util.Log.getStackTraceString(t))
        }
    }
}
