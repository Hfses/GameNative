package app.gamenative.gamehub

import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the Game Hub store registry and aggregation. */
class StoreManagerTest {

    private fun item(source: GameSource, id: String, name: String) = LibraryItem(
        appId = GameModel.buildId(source, id),
        name = name,
        gameSource = source,
        capsuleImageUrl = "https://img/$id.jpg", // non-empty so clientIconUrl (Android) is never hit
        isInstalled = false,
    )

    private fun provider(
        source: GameSource,
        games: List<LibraryItem>,
        canSearch: Boolean = false,
        searchResults: List<GameModel> = emptyList(),
        refreshCount: Int = games.size,
    ) = DelegatingStoreProvider.fromLibraryItems(
        source = source,
        displayName = source.name,
        capabilities = StoreCapabilities(canSearch = canSearch),
        libraryItems = flowOf(games),
        onRefresh = { refreshCount },
        onSearch = { searchResults },
    )

    @Test
    fun `registered sources reflect registration order and removal`() = runBlocking {
        val manager = StoreManager()
        manager.register(provider(GameSource.EPIC, emptyList()))
        manager.register(provider(GameSource.GOG, emptyList()))

        // Sorted by enum ordinal (GOG=2 before EPIC=3), regardless of registration order.
        assertEquals(listOf(GameSource.GOG, GameSource.EPIC), manager.registeredSources.value)

        manager.unregister(GameSource.EPIC)
        assertEquals(listOf(GameSource.GOG), manager.registeredSources.value)
    }

    @Test
    fun `unified library merges every store`() = runBlocking {
        val manager = StoreManager()
        manager.register(provider(GameSource.GOG, listOf(item(GameSource.GOG, "1", "Witcher"))))
        manager.register(
            provider(GameSource.EPIC, listOf(item(GameSource.EPIC, "2", "Fortnite"), item(GameSource.EPIC, "3", "Alan"))),
        )

        val merged = manager.unifiedLibrary().first()
        assertEquals(3, merged.size)
        assertEquals(setOf("Witcher", "Fortnite", "Alan"), merged.map { it.name }.toSet())
        assertTrue(merged.any { it.source == GameSource.GOG })
        assertTrue(merged.any { it.source == GameSource.EPIC })
    }

    @Test
    fun `unified library is empty with no providers`() = runBlocking {
        assertTrue(StoreManager().unifiedLibrary().first().isEmpty())
    }

    @Test
    fun `searchAll only queries searchable stores`() = runBlocking {
        val manager = StoreManager()
        val hit = GameModel(id = "STEAM_10", name = "Portal", source = GameSource.STEAM)
        manager.register(provider(GameSource.STEAM, emptyList(), canSearch = true, searchResults = listOf(hit)))
        manager.register(provider(GameSource.GOG, emptyList(), canSearch = false, searchResults = listOf(hit)))

        assertEquals(1, manager.searchableProviders().size)
        val results = manager.searchAll("por")
        assertEquals(listOf("Portal"), results.map { it.name })
    }

    @Test
    fun `searchAll returns empty for blank query`() = runBlocking {
        val manager = StoreManager()
        manager.register(provider(GameSource.STEAM, emptyList(), canSearch = true, searchResults = listOf(
            GameModel(id = "STEAM_1", name = "X", source = GameSource.STEAM),
        )))
        assertTrue(manager.searchAll("   ").isEmpty())
    }

    @Test
    fun `refreshAll reports per-source counts`() = runBlocking {
        val manager = StoreManager()
        manager.register(provider(GameSource.GOG, emptyList(), refreshCount = 5))
        manager.register(provider(GameSource.AMAZON, emptyList(), refreshCount = 2))

        val result = manager.refreshAll()
        assertEquals(5, result[GameSource.GOG]?.getOrNull())
        assertEquals(2, result[GameSource.AMAZON]?.getOrNull())
    }

    @Test
    fun `provider lookup returns the registered provider`() = runBlocking {
        val manager = StoreManager()
        val gog = provider(GameSource.GOG, emptyList())
        manager.register(gog)
        assertEquals(gog, manager.provider(GameSource.GOG))
        assertFalse(manager.provider(GameSource.EPIC) != null)
    }
}
