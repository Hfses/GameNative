package com.winlator.box86_64

import android.content.Context
import com.winlator.PrefManager
import com.winlator.core.envvars.EnvVars
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import java.util.concurrent.CompletableFuture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for custom preset persistence in [Box86_64PresetManager].
 *
 * Custom presets used to be stored as "id|name|envVars" entries joined with
 * commas, which corrupted the whole preset list as soon as a name or an env
 * value contained a comma or pipe (e.g. ZINK_DEBUG=compact,deck_emu). They
 * are now stored as JSON; the legacy format must still load and migrate.
 */
class Box86_64PresetManagerTest {

    private val store = mutableMapOf<String, String>()
    private val context = mockk<Context>(relaxed = true)

    @Before
    fun setUp() {
        mockkObject(PrefManager)
        every { PrefManager.init(any()) } just Runs
        every { PrefManager.getString(any(), any()) } answers { store[firstArg()] ?: secondArg() }
        every { PrefManager.putString(any(), any()) } answers {
            store[firstArg<String>()] = secondArg()
            CompletableFuture.completedFuture(Unit)
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
        store.clear()
    }

    @Test
    fun `preset with comma and pipe in env values survives a round trip`() {
        val envVars = EnvVars()
        envVars.put("ZINK_DEBUG", "compact,deck_emu")
        envVars.put("BOX64_DYNAREC_BIGBLOCK", "3")

        val id = Box86_64PresetManager.editPreset("box64", context, null, "My|Weird, Name", envVars)

        val loaded = Box86_64PresetManager.getEnvVars("box64", context, id)
        assertEquals("compact,deck_emu", loaded.get("ZINK_DEBUG"))
        assertEquals("3", loaded.get("BOX64_DYNAREC_BIGBLOCK"))

        val preset = Box86_64PresetManager.getPreset("box64", context, id)
        assertEquals("My|Weird, Name", preset!!.name)
    }

    @Test
    fun `legacy pipe format is still read`() {
        store["box64_custom_presets"] = "custom-1|Old preset|BOX64_DYNAREC_SAFEFLAGS=2"

        val loaded = Box86_64PresetManager.getEnvVars("box64", context, "custom-1")
        assertEquals("2", loaded.get("BOX64_DYNAREC_SAFEFLAGS"))
        assertEquals("Old preset", Box86_64PresetManager.getPreset("box64", context, "custom-1")!!.name)
    }

    @Test
    fun `legacy corrupted entries are skipped instead of crashing`() {
        // A legacy value that was corrupted by a comma inside an env value:
        // the second fragment has no id|name|env structure.
        store["box64_custom_presets"] = "custom-1|Ok|VAR=compact,deck_emu,custom-2|Fine|OTHER=1"

        val presets = Box86_64PresetManager.getPresets("box64", context)
        val customIds = presets.map { it.id }.filter { it.startsWith(Box86_64Preset.CUSTOM) }
        // Fragments "deck_emu" (no pipes) must be dropped; well-formed ones kept.
        assertTrue(customIds.contains("custom-1"))
        assertTrue(customIds.contains("custom-2"))
        assertFalse(customIds.contains("deck_emu"))
    }

    @Test
    fun `editing an existing preset updates it in place after migration`() {
        store["box64_custom_presets"] = "custom-1|Old|BOX64_AVX=0"

        val envVars = EnvVars()
        envVars.put("BOX64_AVX", "2")
        val id = Box86_64PresetManager.editPreset("box64", context, "custom-1", "Renamed", envVars)

        assertEquals("custom-1", id)
        assertTrue(store["box64_custom_presets"]!!.trim().startsWith("[")) // migrated to JSON
        assertEquals("2", Box86_64PresetManager.getEnvVars("box64", context, "custom-1").get("BOX64_AVX"))
        assertEquals("Renamed", Box86_64PresetManager.getPreset("box64", context, "custom-1")!!.name)
    }

    @Test
    fun `removePreset deletes only the matching preset`() {
        val envVars = EnvVars()
        envVars.put("BOX64_AVX", "1")
        val id1 = Box86_64PresetManager.editPreset("box64", context, null, "One", envVars)
        val id2 = Box86_64PresetManager.editPreset("box64", context, null, "Two", envVars)

        Box86_64PresetManager.removePreset("box64", context, id1)

        val ids = Box86_64PresetManager.getPresets("box64", context).map { it.id }
        assertFalse(ids.contains(id1))
        assertTrue(ids.contains(id2))
    }

    @Test
    fun `getNextPresetId increments beyond existing custom presets`() {
        val envVars = EnvVars()
        envVars.put("BOX64_AVX", "1")
        val id1 = Box86_64PresetManager.editPreset("box64", context, null, "One", envVars)
        val id2 = Box86_64PresetManager.editPreset("box64", context, null, "Two", envVars)

        assertEquals(Box86_64Preset.CUSTOM + "-1", id1)
        assertEquals(Box86_64Preset.CUSTOM + "-2", id2)
    }

    @Test
    fun `box86 and box64 preset stores are independent`() {
        val envVars = EnvVars()
        envVars.put("BOX86_DYNAREC_BIGBLOCK", "1")
        Box86_64PresetManager.editPreset("box86", context, null, "Box86 only", envVars)

        val box64Custom = Box86_64PresetManager.getPresets("box64", context)
            .map { it.id }.filter { it.startsWith(Box86_64Preset.CUSTOM) }
        assertTrue(box64Custom.isEmpty())
    }
}
