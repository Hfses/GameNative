# GameNative LayerMinimizer — Implementation Spec

All line references verified against the current working tree at `/home/user/GameNative`.

---

## 1. PE Analyzer — `PEInspector.kt`

**New file:** `app/src/main/java/app/gamenative/utils/PEInspector.kt`
**Pattern source:** clone the header walk from `app/src/main/java/app/gamenative/utils/ExeIconExtractor.kt` lines 24–77 (DOS `e_lfanew` at `0x3C`, `PE\0\0` check, COFF at `peHeaderOff+4`, optional header magic `0x10B/0x20B`, data-directory offset `+96/+112`, section-table RVA→file-offset walk in `extractFromHeaders`).

### Fields to read (all little-endian)

| Field | Location | Purpose |
|---|---|---|
| `Machine` (u16) | `coffStart + 0` | `0x014C` i386 / `0x8664` x64 / `0xAA64` ARM64 / `0xA64E` ARM64X → wow64/emulator decision. ExeIconExtractor already computes `coffStart` (line 45) but never reads +0. |
| `Characteristics` (u16) | `coffStart + 18` | bit `0x2000` = DLL (skip DLLs when picking the "main exe"). |
| `Magic` (u16) | `optionalHeaderStart + 0` | `0x010B`/`0x020B`, cross-check of Machine. |
| `Subsystem` (u16) | `optionalHeaderStart + 68` | 2=GUI, 3=console → deprioritize console exes when auto-picking. |
| DataDirectory[1] Import Table | `dataDirectoriesStart + 1*8` (u32 RVA, u32 Size) | 20-byte `IMAGE_IMPORT_DESCRIPTOR`s, Name RVA = u32 at +12, stop at all-zero descriptor. |
| DataDirectory[13] Delay Imports | `dataDirectoriesStart + 13*8` | 32-byte descriptors, DllNameRVA = u32 at +4. **Required** — UE delay-loads d3d12. |
| DataDirectory[14] CLR header | `dataDirectoriesStart + 14*8` | nonzero → .NET. Read CLR Flags u32 at CLR+16; if `0x2` (32BITREQUIRED) clear and Machine==0x014C → treat as **64-bit** (AnyCPU). |
| DataDirectory[10] Load Config | `dataDirectoriesStart + 10*8` | `CHPEMetadataPointer` u64 at offset `0xC8` of `IMAGE_LOAD_CONFIG_DIRECTORY64` (check `Size` covers it); nonzero + Machine 0x8664 → ARM64EC hybrid → native. |

### Result type + sketch

```kotlin
// app/src/main/java/app/gamenative/utils/PEInspector.kt
data class PEFacts(
    val machine: Int,            // raw COFF Machine, AnyCPU-corrected via effectiveBitness
    val is64Bit: Boolean,        // Magic 0x20B, overridden by CLR AnyCPU rule
    val isNativeArm: Boolean,    // 0xAA64 / 0xA64E / (0x8664 + CHPE pointer)
    val isDll: Boolean,
    val isGuiSubsystem: Boolean,
    val importedDlls: Set<String>,   // lowercase, union of import + delay-import
    val parseOk: Boolean,            // false => caller must treat as UNKNOWN
)

object PEInspector {
    private const val MAX_HEADER_READ = 4096          // same as ExeIconExtractor
    private const val MAX_IMPORT_BYTES = 256 * 1024   // bound reads; packed exes lie

    fun inspect(file: File): PEFacts? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            // 1. header walk == ExeIconExtractor.tryExtractMainIcon lines 30-56,
            //    plus getShort(coffStart) [Machine], getShort(coffStart+18),
            //    getShort(optionalHeaderStart+68) [Subsystem]
            // 2. section table walk == extractFromHeaders (rvaToOff helper)
            // 3. importDlls(dir1) + delayImportDlls(dir13): iterate descriptors,
            //    stop at zero descriptor or MAX_IMPORT_BYTES, read NUL-terminated
            //    ASCII names via rvaToOff, .lowercase()
            // 4. CLR AnyCPU + CHPE checks per table above
        }
    }.onFailure { Timber.w(it, "PE inspect failed for ${file.name}") }.getOrNull()

    /** exe + sibling engine DLLs > 1 MB (UnityPlayer.dll, GameAssembly.dll, UE modules) */
    fun inspectModuleSet(exe: File): PEFacts {
        val main = inspect(exe) ?: return PEFacts(parseOk = false, /* … */)
        if (main.importedDlls.any { it.isGraphicsDll() }) return main
        val siblings = exe.parentFile?.listFiles { f ->
            f.extension.equals("dll", true) && f.length() > 1_000_000
        }.orEmpty()
        return main.copy(importedDlls = main.importedDlls +
            siblings.mapNotNull { inspect(it)?.importedDlls }.flatten())
    }
}
```

Fallback string scan (for LoadLibrary-only engines): if union scan finds no graphics DLL, `grep` the largest engine DLL's raw bytes for ASCII `d3d11.dll` / `d3d12.dll` / `vulkan-1.dll` (bounded read, e.g. first 32 MB) as weak evidence only — it can suggest, never suppress.

---

## 2. Decision table (single source of truth)

**New file:** `app/src/main/java/app/gamenative/utils/LayerMinimizer.kt`. Inputs: `PEFacts` (module-set union), `vkCaps` tier, `wineInfo.isArm64EC()` (`com/winlator/core/WineInfo.java:72`). Output:

```kotlin
data class LayerVerdict(
    val dxwrapper: String,          // bare family: "dxvk"|"vkd3d"|"wined3d"|"cnc-ddraw" — NEVER pre-versioned (see risk in §3)
    val dxwrapperConfigPatch: Map<String, String>, // e.g. renderer=gl for wined3d branch
    val wow64Mode: Boolean, val mmap32Needed: Boolean,
    val emulator: String?,          // "FEXCore"|"Box64"|null(=native)
    val confidence: Confidence,     // SURE | WEAK(string-scan) | UNKNOWN
    val evidence: String,           // "Machine=0x8664, imports=[d3d11.dll, dxgi.dll]"
)
```

**Graphics rows — first match wins** (`imports` = union set; `vkCaps` GOOD = Turnip/Vulkan-1.3 + required extensions probed at container-setup time, cached per driver version; probe lives next to `RuntimeCompatibility.kt` lines 80–147 pattern):

| # | Condition | dxwrapper | Skipped layers |
|---|---|---|---|
| 1 | `vulkan-1.dll`, no `d3d*` | none needed — but **keep `"dxvk"` installed as inert default**, flag "no translation" in UI (winevulkan→Turnip direct) | DXVK/VKD3D/WineD3D active paths |
| 2 | `opengl32.dll`, no `d3d*` | `"wined3d"` with `renderer=gl` config key (already in `DEFAULT_DXWRAPPERCONFIG`, `Container.java:47`) so Wine GL→Zink→Turnip; d3d* left builtin | DXVK, VKD3D, D8VK |
| 3 | `d3d12.dll`/`d3d12core.dll` (incl. delay) AND `is64Bit` AND vkCaps GOOD | `"vkd3d"` — the `"vkd3d"` branch in `XServerScreen.extractDXWrapperFiles()` (≈4945–4969) already pairs DXVK for dxgi/d3d11, which is required since VKD3D-Proton ships no dxgi | D8VK, WineD3D |
| 4 | `d3d11`/`d3d10*`/`dxgi` AND vkCaps GOOD | `"dxvk"` | VKD3D, WineD3D |
| 5 | `d3d9.dll` AND vkCaps GOOD | `"dxvk"` (d3d9 frontend) | VKD3D, WineD3D |
| 6 | `d3d8.dll` AND vkCaps GOOD | `"d8vk"` (existing else-branch at ≈4973–4983) | DXVK d3d9-12, VKD3D |
| 7 | `ddraw.dll`/`d3drm.dll` only, **or** vkCaps POOR with any d3d≤9 | `"wined3d"` (+ `ddrawrapper` key) or `"cnc-ddraw"` for ddraw-only | DXVK, VKD3D, D8VK |
| 8 | no graphics DLL found (packed / LoadLibrary-only, string-scan empty) | **keep current default untouched**, confidence=UNKNOWN | nothing (safe default) |

**CPU/emulation rows** (independent of graphics rows):

| # | Machine | wow64Mode | Emulation |
|---|---|---|---|
| A | 0xAA64 / 0xA64E / ARM64EC-hybrid | false | native under arm64ec Wine; no Box64, no FEX (bionic path, `BionicProgramLauncherComponent.java:102/397`) |
| B | 0x8664 (AnyCPU-corrected) | keep **on** (capability) but suppress 32-bit *tuning*: don't set `BOX64_MMAP32=1` (`GuestProgramLauncherComponent.java:360` currently sets it whenever wow64Mode; Bionic already skips it for arm64ec at line 255) and skip `addBox86EnvVars` (line 189) | arm64ec Wine + FEXCore (`HODLL=libwow64fex.dll`, Bionic:400) if available, else Box64 |
| C | 0x014C | true, `BOX64_MMAP32=1` required | wow64 path (`HODLL=wowbox64.dll` Bionic:402 or FEX32); if arm64ec build lacks 32-bit emulation → fall back to glibc variant + Box64 and surface the reason |

Multi-API note: UE titles importing both d3d11 and d3d12 hit row 3 (vkd3d branch already installs DXVK d3d11 alongside); if vkCaps lacks VKD3D-required features, demote to row 4 and record why.

---

## 3. Where it runs and how it applies (suggestion, not silent override)

### Analyze — three hooks, all verified

1. **Container creation** — `ContainerUtils.createNewContainer()`: **replace** the network `fetchDirect3DMajor` block at `ContainerUtils.kt:955–994` (the `runBlocking` + 10 s `withTimeout` PCGamingWiki call) with the local PE scan — strictly faster, works offline, works for all sources not just Steam. Insertion point: after the `applyBestConfigMapToContainerData` merge (line 933–937) so an explicit BestConfig/`customConfig?.dxwrapper` (line 948) always outranks the heuristic. Exe resolution: install dir already resolved per source in this function; for the file path reuse the pattern in `GameFixesRegistry.kt:86–92` (`getADrivePath(container.drives)` + `container.executablePath`) and `CustomGameScanner.findUniqueExeRelativeToFolder` (used at 798–812). Prefer `container.executablePath`; fallback = largest GUI-subsystem non-DLL exe.
2. **First boot of pre-existing containers** — `XServerScreen.kt:2137` `firstTimeBoot = container.getExtra("appVersion").isEmpty() || containerVariantChanged`; run the scan when `firstTimeBoot && container.getExtra("layerAnalysis").isEmpty()`.
3. **Cheap per-launch re-check** — the `GameFixesRegistry.applyFor(context, appId, container)` call site at `XServerScreen.kt:3416` (inside `setupXEnvironment`, before `setupWineSystemFiles` extraction). Re-run only when cache key (exe path + size + mtime) changed. Cache verdict via `container.putExtra("layerAnalysis", json)` mirroring the existing `putExtra("dxwrapper")` pattern.

### Apply — with consent

- **Persist a suggestion, not a config**: store `LayerVerdict` JSON in `container.putExtra("layerSuggestion", ...)` plus `putExtra("layerSuggestionState", "pending"|"accepted"|"dismissed")`.
- **Consent UI**: surface in `app/src/main/java/app/gamenative/ui/component/dialog/GraphicsTab.kt` (and optionally a one-time pre-launch snackbar/dialog): "Detected: D3D11, 64-bit x86 → suggest DXVK only, skip VKD3D/D8VK" with **Apply** / **Keep current** buttons and the `evidence` string. Exception where auto-apply is safe: at `createNewContainer` time when no BestConfig/customConfig exists — there is no user setting to override yet (this exactly replaces the current silent `containerData.dxwrapper = newDxWrapper` mutation at line 987, so it's not a behavior regression).
- **Application mechanics**: on Apply, build the patch as a `Map<String, Any?>` and route through the existing whitelist `ContainerUtils.applyBestConfigMapToContainerData()` (line 377 — already whitelists `dxwrapper`, `dxwrapperConfig`, `emulator`, `wineVersion`, `containerVariant`, box64/fexcore keys), then `ContainerUtils.applyToContainer(context, container, containerData)` (writes dxWrapper/Config at 489–494, wow64Mode at 511, emulator at 523, saves at 594).
- **No extra re-extraction plumbing needed**: `setupWineSystemFiles` diffs `xServerState.value.dxwrapper != container.getExtra("dxwrapper")` at `XServerScreen.kt:4702` — any dxwrapper change made before XServerScreen seeds state (line 483–494) triggers DLL re-extraction automatically. Emulator/wow64 are read live per launch (`BionicProgramLauncherComponent`/`setupXEnvironment:3431+`).
- **Dry-run / per-launch trial**: reuse `getOrCreateContainerWithOverride()` (`ContainerUtils.kt:1071–1107`) with `applyToContainer(saveToDisk=false)` for a "try once" button.
- **Critical formatting rule**: write the **bare** family name (`"dxvk"`, `"vkd3d"`, `"wined3d"`, `"d8vk"`); versions belong in `dxwrapperConfig` (`version=`, `vkd3dVersion=`). `setupWineSystemFiles` does the `"dxvk"→"dxvk-<version>"` normalization itself (≈4672–4684); writing a pre-versioned string desyncs the `getExtra("dxwrapper")` change-detection marker.
- **Observability**: `Timber.i` the verdict with raw evidence (machine hex, matched DLL list, vkCaps tier, which row fired); count row-fired/accepted/dismissed in `DiagnosticsAnalyzer.kt` so bad heuristics are visible in aggregate.

---

## 4. Risks per rule

| Rule | Risk | Mitigation |
|---|---|---|
| Row 8 / parser | Packed/DRM-protected exes (UPX, stubs) show only `kernel32` imports | `parseOk=false` or empty graphics set ⇒ UNKNOWN ⇒ never change anything; current stacked default stays. Header-only, read-only, bounded reads (`MAX_HEADER_READ`/`MAX_IMPORT_BYTES`) — no DRM circumvention. |
| Exe selection | Scanning the launcher, not the game (Rockstar/2K, Unity stub exes) | Module-set union (siblings > 1 MB) covers Unity; prefer `container.executablePath`; **rescan trigger**: if the launched child process exe differs from the scanned exe, invalidate the cache and re-suggest on next launch. |
| Rows 2–7 | LoadLibrary-at-runtime engines with clean import tables | String-scan fallback marked `confidence=WEAK` — WEAK verdicts are suggestion-only, never auto-applied at creation. |
| Row 2 | Zink/Turnip GL conformance gaps (compat profile, legacy extensions) | Keep one-tap fallback in GraphicsTab to WineD3D-on-GL/GL4ES; `glu32`/`glut32` imports flag "legacy GL" and bias toward showing the GL4ES option. |
| Row 3 | VKD3D needs Vulkan features the driver lacks; UE D3D12 delay-load with D3D11 fallback | vkCaps probe gates row 3; demote to DXVK d3d11 (row 4) when POOR and record reason; probe must run against the **same ICD the container will use** (Turnip vs system), cached per driver version, invalidated on driver update. |
| Row 7 | cnc-ddraw vs wined3d ambiguity for ddraw titles | Default `wined3d` + `ddrawrapper` config key; expose cnc-ddraw as alternative in the same suggestion card. |
| Row B | 64-bit exe spawning 32-bit helpers (launchers, anti-cheat, codecs) | Never disable wow64 **capability** — only skip MMAP32/box86 *tuning*. `BOX64_MMAP32` off widens address space for 64-bit but breaks 32-bit children ⇒ keep a per-game "32-bit helpers" toggle that restores row C behavior. |
| Row A / arm64ec | x86 anti-tamper breaking under ARM64EC hybrid thunking | Per-game override to force classic Box64 + x86_64 Wine (glibc variant), surfaced next to the suggestion. |
| CLR AnyCPU | `Prefer32Bit` AnyCPU exes misread as 64-bit | Only apply the AnyCPU correction when the `0x2` flag is clear **and** corroborated (e.g. `steam_api64.dll` import — pattern exists in `SteamUtils.kt:234–238`); otherwise trust Machine. |
| Hook 1 | Adding latency to `createNewContainer` | PE scan is a few KB of local I/O replacing a 10 s network timeout — net latency win; still wrap in `runCatching`. |
| Consent flow | Suggestion nags every launch | `layerSuggestionState=dismissed` suppresses re-prompt until cache key (exe path+size+mtime) changes. |

**Files to create:** `app/src/main/java/app/gamenative/utils/PEInspector.kt`, `LayerMinimizer.kt` (verdict + table), `VulkanCapsProbe.kt` (next to `RuntimeCompatibility.kt`).
**Files to modify:** `ContainerUtils.kt` (replace 955–994; extend whitelist call site), `XServerScreen.kt` (hook at 2137 and 3416), `GraphicsTab.kt` (suggestion card), `GuestProgramLauncherComponent.java` (gate line 360 MMAP32 on verdict, not bare wow64Mode), `DiagnosticsAnalyzer.kt` (counters). Matches pending task #47 "Minimizador de camadas de tradução (auto-detect)".