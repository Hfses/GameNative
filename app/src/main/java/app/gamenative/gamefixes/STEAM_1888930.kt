package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * The Last of Us Part I
 *
 * The engine probes for a large contiguous virtual address range at startup
 * ("Memory::FindAvailableVirtualMemoryStartAddress") and HALTs with a
 * breakpoint (0x80000003) when the probe fails under Android's constrained
 * address space. Capping the memory size Wine reports keeps the probe inside
 * the usable range. The Box64 settings harden JIT translation for the game's
 * heavy self-referencing code (same profile validated on Winlator).
 */
private val TLOU_PART1_ENV_VARS = mapOf(
    "WINEVMEMMAXSIZE" to "8192",
    "BOX64_DYNAREC_BIGBLOCK" to "0",
    "BOX64_DYNAREC_STRONGMEM" to "2",
    "BOX64_DYNAREC_SAFEFLAGS" to "2",
)

/** Steam install (appId 1888930). */
val STEAM_Fix_1888930: KeyedGameFix = KeyedWineEnvVarFix(
    gameSource = GameSource.STEAM,
    gameId = "1888930",
    envVarsToSet = TLOU_PART1_ENV_VARS,
)

/** Same fix for sideloaded copies, matched by executable name. */
internal val TLOU_PART1_EXE_FIX: GameFix = WineEnvVarFix(TLOU_PART1_ENV_VARS)
