package app.gamenative.utils

import java.io.File

/**
 * Picks the MINIMAL translation-layer stack for a game from its PE imports, so an unnecessary
 * layer (e.g. DXVK on a pure-OpenGL game, or VKD3D on a D3D11 game) isn't stacked on top —
 * fewer layers = more FPS. Pure logic + a thin file entry point; applies nothing on its own.
 *
 * Deliberately conservative: it only returns a concrete [dxwrapper] when the imports make the
 * DirectX/GL family unambiguous ([Confidence.SURE]). On packed exes, LoadLibrary-only engines, or
 * any parse failure it returns [Confidence.UNKNOWN] and the caller keeps the existing default.
 */
object LayerMinimizer {

    enum class Confidence { SURE, UNKNOWN }

    data class Verdict(
        /** Bare dxwrapper family: "dxvk" | "vkd3d" | "d8vk" | "wined3d" | null (keep default). */
        val dxwrapper: String?,
        /** Extra dxwrapperConfig keys to merge (e.g. renderer=gl for the wined3d/GL branch). */
        val dxwrapperConfigPatch: Map<String, String>,
        val confidence: Confidence,
        /** Human-readable reason shown to the user (never a silent override). */
        val evidence: String,
    )

    private val UNKNOWN = Verdict(null, emptyMap(), Confidence.UNKNOWN, "")

    /**
     * @param vkCapsGood whether the device has a working Vulkan/Turnip driver good enough for
     *   DXVK/VKD3D. When false we prefer the GL path (wined3d→Zink) for low DX versions.
     */
    fun decide(facts: PEFacts?, vkCapsGood: Boolean = true): Verdict {
        if (facts == null || !facts.parseOk) return UNKNOWN
        val imports = facts.importedDlls
        if (imports.none { it in GRAPHICS }) return UNKNOWN

        fun has(vararg names: String) = names.any { it in imports }
        val ev = "imports=${imports.filter { it in GRAPHICS }.sorted()}"

        return when {
            // Pure Vulkan: no DX/GL translation at all. Keep dxvk installed but inert.
            has("vulkan-1.dll") && !hasAnyDx(imports) ->
                Verdict("dxvk", emptyMap(), Confidence.SURE, "$ev → nativo Vulkan (sem tradução gráfica)")

            // OpenGL only → Wine GL on Zink/Turnip; no DX wrapper.
            has("opengl32.dll") && !hasAnyDx(imports) ->
                Verdict("wined3d", mapOf("renderer" to "gl"), Confidence.SURE, "$ev → OpenGL direto (Zink), sem DXVK")

            // D3D12 → VKD3D (which still needs DXVK's dxgi/d3d11; the vkd3d branch installs both).
            has("d3d12.dll", "d3d12core.dll") && facts.is64Bit && vkCapsGood ->
                Verdict("vkd3d", emptyMap(), Confidence.SURE, "$ev → D3D12 (VKD3D)")

            // D3D10/11 family → DXVK.
            has("d3d11.dll", "d3d10.dll", "d3d10_1.dll", "d3d10core.dll", "dxgi.dll") && vkCapsGood ->
                Verdict("dxvk", emptyMap(), Confidence.SURE, "$ev → D3D10/11 (DXVK)")

            // D3D9 → DXVK d3d9 frontend.
            has("d3d9.dll") && vkCapsGood ->
                Verdict("dxvk", emptyMap(), Confidence.SURE, "$ev → D3D9 (DXVK)")

            // D3D8 → D8VK.
            has("d3d8.dll") && vkCapsGood ->
                Verdict("d8vk", emptyMap(), Confidence.SURE, "$ev → D3D8 (D8VK)")

            // Legacy DirectDraw or no working Vulkan → WineD3D on GL.
            has("ddraw.dll", "d3drm.dll") || !vkCapsGood ->
                Verdict("wined3d", mapOf("renderer" to "gl"), Confidence.SURE, "$ev → legado (WineD3D/GL)")

            else -> UNKNOWN
        }
    }

    /** Convenience: scan a game exe (and sibling engine DLLs) then decide. */
    fun analyze(exe: File, vkCapsGood: Boolean = true): Verdict =
        decide(PEInspector.inspectModuleSet(exe), vkCapsGood)

    private val GRAPHICS = setOf(
        "d3d8.dll", "d3d9.dll", "d3d10.dll", "d3d10_1.dll", "d3d10core.dll",
        "d3d11.dll", "d3d12.dll", "d3d12core.dll", "dxgi.dll",
        "opengl32.dll", "vulkan-1.dll", "ddraw.dll", "d3drm.dll",
    )

    private fun hasAnyDx(imports: Set<String>): Boolean = imports.any {
        it.startsWith("d3d") || it == "dxgi.dll" || it == "ddraw.dll"
    }
}
