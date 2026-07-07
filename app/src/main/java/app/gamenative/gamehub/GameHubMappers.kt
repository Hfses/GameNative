package app.gamenative.gamehub

import app.gamenative.data.AmazonGame
import app.gamenative.data.EpicGame
import app.gamenative.data.GOGGame
import app.gamenative.data.GameSource
import app.gamenative.data.SteamApp

/**
 * Game Hub — per-store adapters that convert each source's own Room entity into the unified
 * [GameModel]. This is the "translate the native representation" half of a [StoreProvider]; it lives
 * next to the hub (not in the core) because it necessarily knows the concrete entity types.
 *
 * The unified id follows [GameModel.buildId] (`"${SOURCE}_${rawId}"`), matching the existing
 * LibraryItem.appId scheme so both models interoperate during the migration.
 */

private const val STEAM_CDN = "https://cdn.cloudflare.steamstatic.com/steam/apps"

/**
 * Steam has no per-app "installed" column on the entity, so the caller supplies it (via
 * SteamService.isAppInstalled). Art uses the stable public CDN paths keyed by app id.
 */
fun SteamApp.toGameModel(installed: Boolean): GameModel = GameModel(
    id = GameModel.buildId(GameSource.STEAM, id.toString()),
    name = name,
    source = GameSource.STEAM,
    developer = developer,
    coverUrl = "$STEAM_CDN/$id/header.jpg",
    heroUrl = "$STEAM_CDN/$id/library_hero.jpg",
    installState = if (installed) InstallState.INSTALLED else InstallState.NOT_INSTALLED,
)

fun GOGGame.toGameModel(): GameModel = GameModel(
    id = GameModel.buildId(GameSource.GOG, id),
    name = title,
    source = GameSource.GOG,
    description = description,
    coverUrl = verticalCoverUrl.ifEmpty { imageUrl },
    heroUrl = backgroundUrl,
    developer = developer,
    installPath = installPath.ifEmpty { null },
    sizeBytes = if (installSize > 0) installSize else downloadSize,
    installState = if (isInstalled) InstallState.INSTALLED else InstallState.NOT_INSTALLED,
    lastPlayedAt = lastPlayed,
)

fun EpicGame.toGameModel(): GameModel = GameModel(
    id = GameModel.buildId(GameSource.EPIC, id.toString()),
    name = title.ifEmpty { appName },
    source = GameSource.EPIC,
    description = description,
    coverUrl = artCover.ifEmpty { artSquare },
    heroUrl = artPortrait,
    developer = developer,
    version = version,
    installPath = installPath.ifEmpty { null },
    executable = executable.ifEmpty { null },
    sizeBytes = if (installSize > 0) installSize else downloadSize,
    installState = if (isInstalled) InstallState.INSTALLED else InstallState.NOT_INSTALLED,
    lastPlayedAt = lastPlayed,
)

fun AmazonGame.toGameModel(): GameModel = GameModel(
    id = GameModel.buildId(GameSource.AMAZON, appId.toString()),
    name = title,
    source = GameSource.AMAZON,
    developer = developer,
    coverUrl = artUrl,
    heroUrl = heroUrl,
    version = versionId,
    installPath = installPath.ifEmpty { null },
    sizeBytes = if (installSize > 0) installSize else downloadSize,
    installState = if (isInstalled) InstallState.INSTALLED else InstallState.NOT_INSTALLED,
    lastPlayedAt = lastPlayed,
)
