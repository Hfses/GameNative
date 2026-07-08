package app.gamenative.gamehub

import app.gamenative.data.LibraryItem

/**
 * Game Hub — interop bridge between the existing [LibraryItem] (the current list-view model that
 * the library UI already renders) and the unified [GameModel].
 *
 * During the migration both models coexist: providers can produce [GameModel]s from their native
 * types, while screens that still consume [LibraryItem] keep working. This mapper lets either side
 * convert without duplicating the per-source art/id conventions that already live on [LibraryItem].
 */
object GameModelMapper {

    /** Convert an existing library list item into a unified [GameModel]. */
    fun fromLibraryItem(item: LibraryItem): GameModel = GameModel(
        id = item.appId,
        name = item.name,
        source = item.gameSource,
        // Prefer the capsule; fall back to the source-aware client icon LibraryItem already resolves.
        coverUrl = item.capsuleImageUrl.ifEmpty { item.clientIconUrl },
        heroUrl = item.heroImageUrl,
        sizeBytes = item.sizeBytes,
        installState = if (item.isInstalled) InstallState.INSTALLED else InstallState.NOT_INSTALLED,
        isFavorite = false,
    )

    /**
     * Project a unified [GameModel] back onto a [LibraryItem] for screens not yet migrated.
     * Fields the list item doesn't need (description, developer, executable, profile) are dropped.
     */
    fun toLibraryItem(model: GameModel, index: Int = 0): LibraryItem = LibraryItem(
        index = index,
        appId = model.id,
        name = model.name,
        gameSource = model.source,
        capsuleImageUrl = model.coverUrl,
        heroImageUrl = model.heroUrl,
        sizeBytes = model.sizeBytes,
        isInstalled = model.isInstalled,
    )
}
