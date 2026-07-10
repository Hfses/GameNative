package app.gamenative.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Tiny shared Compose state that makes the app-wide animated wallpaper react the moment the user
 * changes it from the Layout panel — instead of only after an app restart.
 *
 * The wallpaper settings live in PrefManager (DataStore), which isn't a Compose State, so
 * readers (PluviaMain's global background and the library's transparency gate) read them inside
 * `remember(version) { ... }`. The Layout panel calls [changed] after writing any wallpaper pref,
 * which bumps [version] and forces every reader to re-read PrefManager and recompose.
 */
object AppWallpaperState {
    var version by mutableIntStateOf(0)
        private set

    /** Call after writing any library/app wallpaper pref so the background updates live. */
    fun changed() {
        version++
    }
}
