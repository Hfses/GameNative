package com.winlator.container

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Round-trip and schema-migration tests for [Container] persistence.
 *
 * saveData() serializes all fields to the ".container" JSON file and loadData()
 * parses them back. These guard the config format that lives on users' devices:
 * a regression here silently corrupts or drops existing container settings.
 */
@RunWith(RobolectricTestRunner::class)
class ContainerPersistenceTest {

    private lateinit var rootDir: File

    @Before
    fun setUp() {
        rootDir = File.createTempFile("container_persist_", null).apply {
            delete()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        rootDir.deleteRecursively()
    }

    private fun reload(): Container {
        val configText = File(rootDir, ".container").readText()
        val reloaded = Container("test-1")
        reloaded.rootDir = rootDir
        reloaded.loadData(JSONObject(configText))
        return reloaded
    }

    @Test
    fun `representative fields survive a save load round trip`() {
        val c = Container("test-1")
        c.rootDir = rootDir
        c.name = "My Container"
        c.screenSize = "1280x720"
        c.envVars = "WINEESYNC=1 ZINK_DEBUG=compact,deck_emu BOX64_DYNAREC_BIGBLOCK=3"
        c.graphicsDriver = "vortek"
        c.setCPUList("0,1,2,3")
        c.setContainerVariant(Container.BIONIC)
        c.setBox64Preset("PERFORMANCE")
        c.putExtra("appliedWineVersion", "proton-9.0-x86_64")
        c.saveData()

        val r = reload()
        assertEquals("My Container", r.name)
        assertEquals("1280x720", r.screenSize)
        assertEquals("WINEESYNC=1 ZINK_DEBUG=compact,deck_emu BOX64_DYNAREC_BIGBLOCK=3", r.envVars)
        assertEquals("vortek", r.graphicsDriver)
        assertEquals("0,1,2,3", r.getCPUList())
        assertEquals(Container.BIONIC, r.getContainerVariant())
        assertEquals("PERFORMANCE", r.getBox64Preset())
        assertEquals("proton-9.0-x86_64", r.getExtra("appliedWineVersion"))
    }

    @Test
    fun `env vars containing commas are preserved verbatim`() {
        val c = Container("test-1")
        c.rootDir = rootDir
        c.envVars = "TU_DEBUG=noconform ZINK_DEBUG=compact,deck_emu"
        c.saveData()
        assertEquals("TU_DEBUG=noconform ZINK_DEBUG=compact,deck_emu", reload().envVars)
    }

    @Test
    fun `legacy useLegacyRenderer migrates to displayRendererMode`() {
        val legacyOn = JSONObject().put("useLegacyRenderer", true)
        Container.checkObsoleteOrMissingProperties(legacyOn)
        assertEquals("gl", legacyOn.getString("displayRendererMode"))
        assertEquals(false, legacyOn.has("useLegacyRenderer"))

        val legacyOff = JSONObject().put("useLegacyRenderer", false)
        Container.checkObsoleteOrMissingProperties(legacyOff)
        assertEquals(Container.DEFAULT_DISPLAY_RENDERER, legacyOff.getString("displayRendererMode"))
    }

    @Test
    fun `legacy graphics driver names migrate`() {
        val turnipZink = JSONObject().put("graphicsDriver", "turnip-zink")
        Container.checkObsoleteOrMissingProperties(turnipZink)
        assertEquals("turnip", turnipZink.getString("graphicsDriver"))

        val llvmpipe = JSONObject().put("graphicsDriver", "llvmpipe")
        Container.checkObsoleteOrMissingProperties(llvmpipe)
        assertEquals("virgl", llvmpipe.getString("graphicsDriver"))
    }

    @Test
    fun `legacy dxcomponents key migrates to wincomponents`() {
        val data = JSONObject().put("dxcomponents", Container.DEFAULT_WINCOMPONENTS)
        Container.checkObsoleteOrMissingProperties(data)
        assertEquals(false, data.has("dxcomponents"))
        assertEquals(true, data.has("wincomponents"))
    }
}
