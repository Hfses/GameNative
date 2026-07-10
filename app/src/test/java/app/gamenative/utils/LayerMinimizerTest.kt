package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Decision-table tests for the translation-layer minimizer (pure logic, no Android deps). */
class LayerMinimizerTest {

    private fun facts(vararg imports: String, is64: Boolean = true) = PEFacts(
        machine = if (is64) 0x8664 else 0x014C,
        is64Bit = is64,
        isNativeArm = false,
        isDll = false,
        isGuiSubsystem = true,
        importedDlls = imports.map { it.lowercase() }.toSet(),
        parseOk = true,
    )

    @Test fun `d3d12 picks vkd3d`() {
        val v = LayerMinimizer.decide(facts("d3d12.dll", "kernel32.dll"))
        assertEquals("vkd3d", v.dxwrapper)
        assertEquals(LayerMinimizer.Confidence.SURE, v.confidence)
    }

    @Test fun `d3d11 picks dxvk`() {
        assertEquals("dxvk", LayerMinimizer.decide(facts("d3d11.dll", "dxgi.dll")).dxwrapper)
    }

    @Test fun `d3d9 picks dxvk`() {
        assertEquals("dxvk", LayerMinimizer.decide(facts("d3d9.dll")).dxwrapper)
    }

    @Test fun `d3d8 picks d8vk`() {
        assertEquals("d8vk", LayerMinimizer.decide(facts("d3d8.dll")).dxwrapper)
    }

    @Test fun `opengl only picks wined3d gl and no dx wrapper`() {
        val v = LayerMinimizer.decide(facts("opengl32.dll", "kernel32.dll"))
        assertEquals("wined3d", v.dxwrapper)
        assertEquals("gl", v.dxwrapperConfigPatch["renderer"])
    }

    @Test fun `pure vulkan needs no translation`() {
        val v = LayerMinimizer.decide(facts("vulkan-1.dll"))
        assertEquals("dxvk", v.dxwrapper) // installed but inert
        assertEquals(LayerMinimizer.Confidence.SURE, v.confidence)
    }

    @Test fun `ddraw legacy picks wined3d`() {
        assertEquals("wined3d", LayerMinimizer.decide(facts("ddraw.dll")).dxwrapper)
    }

    @Test fun `no graphics import is unknown, keeps default`() {
        val v = LayerMinimizer.decide(facts("kernel32.dll", "user32.dll"))
        assertEquals(LayerMinimizer.Confidence.UNKNOWN, v.confidence)
        assertNull(v.dxwrapper)
    }

    @Test fun `parse failure is unknown`() {
        assertEquals(LayerMinimizer.Confidence.UNKNOWN, LayerMinimizer.decide(null).confidence)
    }

    @Test fun `poor vulkan demotes low-dx to wined3d`() {
        val v = LayerMinimizer.decide(facts("d3d9.dll"), vkCapsGood = false)
        assertEquals("wined3d", v.dxwrapper)
    }

    @Test fun `d3d12 takes priority over d3d11 when both present`() {
        // UE titles import both; D3D12 wins (vkd3d branch also installs DXVK d3d11).
        assertEquals("vkd3d", LayerMinimizer.decide(facts("d3d11.dll", "d3d12.dll", "dxgi.dll")).dxwrapper)
    }
}
