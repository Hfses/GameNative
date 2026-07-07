package app.gamenative.gamehub

import app.gamenative.data.GameSource

/**
 * Game Hub — unified, source-agnostic game model.
 *
 * Every [StoreProvider] converts its own native representation (SteamApp, GOGGame, EpicGame,
 * AmazonGame, a scanned local folder, …) into this single shape so the rest of the app — the
 * unified library, search, install queue, launch flow — never has to branch on the origin store.
 *
 * This is deliberately a plain immutable data class with no framework dependencies so it can be
 * used from the domain layer, persisted, and unit-tested without Android.
 */
data class GameModel(
    /**
     * Stable, globally-unique id across all stores. By convention `"${source}_${storeGameId}"`
     * (e.g. `"GOG_1207658930"`), matching the existing LibraryItem.appId scheme so the two models
     * interoperate during the migration. Use [storeGameId] to recover the raw per-store id.
     */
    val id: String,
    val name: String,
    val source: GameSource,
    val description: String = "",
    /** Cover / capsule art URL (or a file:// path for local games). */
    val coverUrl: String = "",
    /** Optional wide hero/banner art URL. */
    val heroUrl: String = "",
    val developer: String = "",
    /** Installed build version/manifest id, when known. Empty if not installed or unknown. */
    val version: String = "",
    /** Absolute install directory once installed, else null. */
    val installPath: String? = null,
    /** Windows-relative launch executable (e.g. `game/bin/game.exe`) once known, else null. */
    val executable: String? = null,
    /** On-disk (or download) size in bytes; 0 when unknown. */
    val sizeBytes: Long = 0L,
    val installState: InstallState = InstallState.NOT_INSTALLED,
    /** Epoch millis of the last launch, or 0 if never played. */
    val lastPlayedAt: Long = 0L,
    val isFavorite: Boolean = false,
    /**
     * Id of the per-game execution profile (Wine/Box64/DXVK/resolution/env). Null means "use the
     * default container profile". The profile itself is owned by the existing container system;
     * the Game Hub only stores the association.
     */
    val configurationProfileId: String? = null,
) {
    val isInstalled: Boolean get() = installState == InstallState.INSTALLED

    /** The raw per-store game id with the `"${source}_"` prefix removed. */
    val storeGameId: String get() = id.removePrefix("${source.name}_")

    companion object {
        /** Build the canonical unified id from a store and its native game id. */
        fun buildId(source: GameSource, storeGameId: String): String = "${source.name}_$storeGameId"
    }
}

/** Lifecycle of a game within the unified library. */
enum class InstallState {
    NOT_INSTALLED,
    QUEUED,
    DOWNLOADING,
    INSTALLING,
    INSTALLED,
    UPDATE_AVAILABLE,
    PAUSED,
    FAILED,
}
