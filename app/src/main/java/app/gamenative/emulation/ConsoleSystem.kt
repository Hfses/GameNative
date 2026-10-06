package app.gamenative.emulation

/**
 * Every console "mode" the app offers. Each one is backed by an open-source libretro core
 * that is downloaded on demand (see [CoreManager]) and run in-app by [ConsoleGameActivity].
 *
 * [coreName] is the libretro buildbot name: `<coreName>_libretro_android.so`.
 * [coreName] == null means no Android-capable emulator exists yet; the mode is still listed
 * so users can see why (instead of silently missing).
 */
enum class ConsoleSystem(
    val id: String,
    val displayName: String,
    val maker: String,
    val coreName: String?,
    val extensions: Set<String>,
    /** BIOS/firmware files the user must provide in the system folder (empty = none needed). */
    val biosFiles: List<String> = emptyList(),
    /** Needs a high-end SoC to run full speed. */
    val heavy: Boolean = false,
    /** Core works but compatibility is limited. */
    val experimental: Boolean = false,
    /** Shown when [coreName] is null. */
    val unavailableReason: String? = null,
) {
    NES("nes", "NES / Famicom", "Nintendo", "fceumm", setOf("nes", "fds", "unf", "unif")),
    SNES("snes", "Super Nintendo", "Nintendo", "snes9x", setOf("smc", "sfc", "swc", "fig", "bs")),
    N64("n64", "Nintendo 64", "Nintendo", "mupen64plus_next_gles3", setOf("n64", "z64", "v64")),
    GB("gb", "Game Boy / Color", "Nintendo", "gambatte", setOf("gb", "gbc", "dmg")),
    GBA("gba", "Game Boy Advance", "Nintendo", "mgba", setOf("gba")),
    NDS("nds", "Nintendo DS", "Nintendo", "melonds", setOf("nds")),
    N3DS(
        "3ds", "Nintendo 3DS", "Nintendo", "citra", setOf("3ds", "3dsx", "cci", "cxi", "app"),
        heavy = true, experimental = true,
    ),
    GAMECUBE_WII(
        "gc_wii", "GameCube / Wii", "Nintendo", "dolphin",
        setOf("iso", "gcm", "gcz", "rvz", "wbfs", "ciso", "wad", "dol", "elf"), heavy = true,
    ),
    SWITCH(
        "switch", "Nintendo Switch", "Nintendo", null, setOf("nsp", "xci"),
        unavailableReason = "Não existe core libretro de Switch para Android; os emuladores de Switch para Android são apps separados.",
    ),
    PS1(
        "ps1", "PlayStation", "Sony", "pcsx_rearmed",
        setOf("cue", "bin", "chd", "pbp", "iso", "img", "m3u"),
        biosFiles = listOf("scph5501.bin (opcional, melhora compatibilidade)"),
    ),
    PS2(
        "ps2", "PlayStation 2", "Sony", "play", setOf("iso", "chd", "cso", "bin", "cue", "elf"),
        heavy = true, experimental = true,
    ),
    PSP("psp", "PlayStation Portable", "Sony", "ppsspp", setOf("iso", "cso", "pbp", "elf", "chd")),
    PS3(
        "ps3", "PlayStation 3", "Sony", null, setOf("iso", "pkg"),
        unavailableReason = "O RPCS3 não tem core libretro; os ports para Android (aPS3e/RPCSX) ainda são apps experimentais à parte.",
    ),
    XBOX360(
        "x360", "Xbox 360", "Microsoft", null, setOf("iso", "xex"),
        unavailableReason = "O Xenia só roda em PC x86 com Windows/Linux; não existe versão para Android/ARM.",
    ),
    MEGA_DRIVE(
        "md", "Mega Drive / Master System / Game Gear", "Sega", "genesis_plus_gx",
        setOf("md", "gen", "smd", "bin", "sms", "gg", "sg", "68k"),
    ),
    SEGA_CD("segacd", "Sega CD", "Sega", "genesis_plus_gx", setOf("cue", "chd", "iso"), biosFiles = listOf("bios_CD_U.bin", "bios_CD_E.bin", "bios_CD_J.bin")),
    SATURN("saturn", "Sega Saturn", "Sega", "yabasanshiro", setOf("cue", "chd", "iso", "ccd", "mds"), heavy = true, experimental = true),
    DREAMCAST("dc", "Dreamcast / Naomi", "Sega", "flycast", setOf("cdi", "gdi", "chd", "cue", "lst", "zip"), heavy = true),
    PC_ENGINE("pce", "PC Engine / TurboGrafx", "NEC", "mednafen_pce_fast", setOf("pce", "cue", "chd", "ccd")),
    ARCADE("arcade", "Arcade (FinalBurn Neo)", "Arcade", "fbneo", setOf("zip", "7z")),
    ATARI_2600("a2600", "Atari 2600", "Atari", "stella2014", setOf("a26", "bin")),
    ;

    val available: Boolean get() = coreName != null

    companion object {
        fun fromId(id: String?): ConsoleSystem? = entries.firstOrNull { it.id == id }
    }
}
