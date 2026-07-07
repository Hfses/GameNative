package app.gamenative.gamehub

import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the LibraryItem <-> GameModel bridge and GameModel id helpers. */
class GameModelMapperTest {

    @Test
    fun `fromLibraryItem maps core fields`() {
        val item = LibraryItem(
            appId = "GOG_1207658930",
            name = "The Witcher",
            gameSource = GameSource.GOG,
            capsuleImageUrl = "https://img/capsule.jpg",
            heroImageUrl = "https://img/hero.jpg",
            sizeBytes = 42L,
            isInstalled = true,
        )
        val model = GameModelMapper.fromLibraryItem(item)

        assertEquals("GOG_1207658930", model.id)
        assertEquals("The Witcher", model.name)
        assertEquals(GameSource.GOG, model.source)
        assertEquals("https://img/capsule.jpg", model.coverUrl)
        assertEquals("https://img/hero.jpg", model.heroUrl)
        assertEquals(42L, model.sizeBytes)
        assertTrue(model.isInstalled)
        assertEquals(InstallState.INSTALLED, model.installState)
    }

    @Test
    fun `not installed maps to NOT_INSTALLED`() {
        val item = LibraryItem(
            appId = "EPIC_9",
            name = "X",
            gameSource = GameSource.EPIC,
            capsuleImageUrl = "https://img/x.jpg",
            isInstalled = false,
        )
        assertEquals(InstallState.NOT_INSTALLED, GameModelMapper.fromLibraryItem(item).installState)
        assertFalse(GameModelMapper.fromLibraryItem(item).isInstalled)
    }

    @Test
    fun `toLibraryItem round-trips the key fields`() {
        val model = GameModel(
            id = "AMAZON_abc",
            name = "Game",
            source = GameSource.AMAZON,
            coverUrl = "https://img/c.jpg",
            heroUrl = "https://img/h.jpg",
            sizeBytes = 7L,
            installState = InstallState.INSTALLED,
        )
        val item = GameModelMapper.toLibraryItem(model, index = 3)

        assertEquals(3, item.index)
        assertEquals("AMAZON_abc", item.appId)
        assertEquals("Game", item.name)
        assertEquals(GameSource.AMAZON, item.gameSource)
        assertEquals("https://img/c.jpg", item.capsuleImageUrl)
        assertEquals(7L, item.sizeBytes)
        assertTrue(item.isInstalled)
    }

    @Test
    fun `buildId and storeGameId are inverses`() {
        val id = GameModel.buildId(GameSource.GOG, "1207658930")
        assertEquals("GOG_1207658930", id)
        val model = GameModel(id = id, name = "n", source = GameSource.GOG)
        assertEquals("1207658930", model.storeGameId)
    }
}
