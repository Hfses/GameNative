package app.gamenative.utils

import android.os.FileObserver
import timber.log.Timber
import java.io.File

/**
 * Watches the user's custom-game folders for filesystem changes (new game copied in, exe/cover
 * added, folder removed) and notifies the library so it can refresh itself in real time —
 * without the user having to pull-to-refresh manually.
 *
 * One inotify observer is registered per watched folder (FileObserver is not recursive); the
 * caller re-arms via [start] whenever the folder set changes. Events are collapsed by the caller
 * (debounce) since copies emit storms of CLOSE_WRITE.
 */
object CustomGameWatcher {

    private const val MASK = FileObserver.CREATE or FileObserver.DELETE or
        FileObserver.MOVED_TO or FileObserver.MOVED_FROM or FileObserver.CLOSE_WRITE

    private val observers = mutableListOf<FileObserver>()

    /**
     * (Re)starts watching [folders]. Any previous observers are stopped first, so this is safe
     * to call whenever the manual-folder set changes. [onChanged] fires on the FileObserver
     * thread for every relevant event — debounce on the caller side.
     */
    @Synchronized
    fun start(folders: Collection<String>, onChanged: () -> Unit) {
        stop()
        for (path in folders) {
            val dir = File(path)
            if (!dir.isDirectory) continue
            // The deprecated (path, mask) constructor is intentional: the File-based one
            // requires API 29 and the app's legacy flavor runs down to minSdk 26.
            @Suppress("DEPRECATION")
            val obs = object : FileObserver(dir.absolutePath, MASK) {
                override fun onEvent(event: Int, file: String?) {
                    // Ignore our own derived artifacts so icon extraction doesn't re-trigger a scan loop.
                    if (file != null && file.endsWith(".extracted.ico", ignoreCase = true)) return
                    onChanged()
                }
            }
            runCatching { obs.startWatching() }
                .onSuccess { observers.add(obs) }
                .onFailure { Timber.w(it, "CustomGameWatcher: could not watch $path") }
        }
        Timber.d("CustomGameWatcher: watching ${observers.size} folder(s)")
    }

    @Synchronized
    fun stop() {
        observers.forEach { runCatching { it.stopWatching() } }
        observers.clear()
    }
}
