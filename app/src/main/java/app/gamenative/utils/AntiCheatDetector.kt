package app.gamenative.utils

import java.io.File

/**
 * Detects kernel-level / server-verified anti-cheat shipped with a game. Under Wine on Android
 * these block online modes (e.g. GTA Online with BattlEye) regardless of emulation settings,
 * so the user is warned instead of chasing a "bug" no setting can fix.
 */
object AntiCheatDetector {

    enum class AntiCheat(val displayName: String) {
        BATTLEYE("BattlEye"),
        EASY_ANTI_CHEAT("Easy Anti-Cheat"),
    }

    fun detect(gameDir: String?): Set<AntiCheat> {
        if (gameDir.isNullOrBlank()) return emptySet()
        val root = File(gameDir)
        if (!root.isDirectory) return emptySet()
        val found = mutableSetOf<AntiCheat>()
        runCatching {
            root.walkTopDown().maxDepth(3).forEach { f ->
                val name = f.name.lowercase()
                when {
                    name == "battleye" || name.startsWith("beservice") || name.startsWith("beclient") ->
                        found += AntiCheat.BATTLEYE
                    name == "easyanticheat" || name.startsWith("easyanticheat_eos") ||
                        name == "start_protected_game.exe" -> found += AntiCheat.EASY_ANTI_CHEAT
                }
            }
        }
        return found
    }
}
