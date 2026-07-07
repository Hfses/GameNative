package app.gamenative.gamehub

import app.gamenative.data.AmazonGame
import app.gamenative.data.EpicGame
import app.gamenative.data.GOGGame
import app.gamenative.data.GameSource
import app.gamenative.data.SteamApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the per-store entity -> [GameModel] adapters. */
class GameHubMappersTest {

    @Test
    fun `steam app maps id, source and cdn art, honouring the supplied install flag`() {
        val model = SteamApp(id = 440, name = "Team Fortress 2", developer = "Valve")
            .toGameModel(installed = true)

        assertEquals("STEAM_440", model.id)
        assertEquals("440", model.storeGameId)
        assertEquals(GameSource.STEAM, model.source)
        assertEquals("Team Fortress 2", model.name)
        assertEquals("Valve", model.developer)
        assertTrue(model.isInstalled)
        assertTrue("cover art should reference the app id", model.coverUrl.contains("/440/"))
    }

    @Test
    fun `steam not-installed maps to NOT_INSTALLED`() {
        val model = SteamApp(id = 10, name = "X").toGameModel(installed = false)
        assertFalse(model.isInstalled)
        assertEquals(InstallState.NOT_INSTALLED, model.installState)
    }

    @Test
    fun `gog game maps title, install path and size`() {
        val model = GOGGame(
            id = "1207658930",
            title = "The Witcher",
            developer = "CD PROJEKT RED",
            isInstalled = true,
            installPath = "/games/witcher",
            installSize = 100L,
        ).toGameModel()

        assertEquals("GOG_1207658930", model.id)
        assertEquals(GameSource.GOG, model.source)
        assertEquals("The Witcher", model.name)
        assertEquals("/games/witcher", model.installPath)
        assertEquals(100L, model.sizeBytes)
        assertTrue(model.isInstalled)
    }

    @Test
    fun `gog not-installed has null install path`() {
        val model = GOGGame(id = "1", title = "X").toGameModel()
        assertNull(model.installPath)
        assertFalse(model.isInstalled)
    }

    @Test
    fun `epic game maps executable and falls back to appName when title is blank`() {
        val model = EpicGame(
            id = 5,
            appName = "Fortnite",
            title = "",
            executable = "game/bin/game.exe",
            installPath = "/games/epic",
            isInstalled = true,
        ).toGameModel()

        assertEquals("EPIC_5", model.id)
        assertEquals("Fortnite", model.name) // title blank -> appName fallback
        assertEquals("game/bin/game.exe", model.executable)
        assertEquals("/games/epic", model.installPath)
        assertTrue(model.isInstalled)
    }

    @Test
    fun `amazon game maps unified id from the numeric appId`() {
        val model = AmazonGame(appId = 7, productId = "amzn1.adg.product.abc", title = "Y")
            .toGameModel()

        assertEquals("AMAZON_7", model.id)
        assertEquals("7", model.storeGameId)
        assertEquals(GameSource.AMAZON, model.source)
        assertEquals("Y", model.name)
        assertNull(model.installPath)
        assertFalse(model.isInstalled)
    }
}
