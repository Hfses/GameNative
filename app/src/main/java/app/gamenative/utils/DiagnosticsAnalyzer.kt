package app.gamenative.utils

/**
 * Turns raw guest/emulator output into a friendly, actionable diagnosis for the failure
 * signatures we actually see in the field. Used by [SessionLogger.logGuestOutput] and the
 * in-game output capture so a cryptic loader error becomes a clear "here's what to change".
 *
 * Add a [Signature] here whenever a new recurring error pattern is identified; keep it ordered
 * most-specific first.
 */
object DiagnosticsAnalyzer {

    data class Diagnosis(
        val id: String,
        /** Short, user-facing explanation of what went wrong and what to do. */
        val message: String,
        val severity: Severity,
    )

    enum class Severity { INFO, WARNING, ERROR }

    private data class Signature(
        val id: String,
        val severity: Severity,
        val message: String,
        val matches: (String) -> Boolean,
    )

    private fun containsAll(text: String, vararg needles: String): Boolean {
        val t = text.lowercase()
        return needles.all { t.contains(it.lowercase()) }
    }

    private val SIGNATURES: List<Signature> = listOf(
        Signature(
            id = "libc-mismatch",
            severity = Severity.ERROR,
            message = "A build de Wine é incompatível com a libc do container (erro __libc_init). " +
                "Troque a variante do container (glibc↔bionic) ou escolha um Wine compatível — o app " +
                "tenta corrigir isso automaticamente antes de abrir.",
        ) { containsAll(it, "__libc_init") || containsAll(it, "R_X86_64_JUMP_SLOT", "not found") },
        Signature(
            id = "kernel32-c0000135",
            severity = Severity.ERROR,
            message = "O tradutor (Box64) não carregou, então o Wine não achou a kernel32.dll " +
                "(status c0000135). Geralmente é a versão do Box64 inválida/ausente: reinstale o " +
                "Box64 do container ou selecione outra versão nas configurações.",
        ) { containsAll(it, "could not load", "kernel32") || containsAll(it, "c0000135") },
        Signature(
            id = "wwise-akaudio",
            severity = Severity.WARNING,
            message = "O motor de áudio do jogo (Wwise/AkAudio) falhou ao iniciar. Tente alternar o " +
                "driver de áudio (PulseAudio → ALSA → desativar) nas configurações do container.",
        ) { containsAll(it, "akaudiodevice") || containsAll(it, "gnrsakiohook") },
        Signature(
            id = "box64-missing-lib",
            severity = Severity.WARNING,
            message = "O Box64 não encontrou uma biblioteca necessária. Instale o Visual C++/DirectX " +
                "pelo gerenciador de dependências, ou ative BOX64_ALLOWMISSINGLIBS se for opcional.",
        ) { containsAll(it, "box64", "cannot open shared object") || containsAll(it, "box64", "library not found") },
        Signature(
            id = "vulkan-device-lost",
            severity = Severity.ERROR,
            message = "O driver Vulkan perdeu o dispositivo (device lost) — normalmente é o driver " +
                "gráfico (Turnip/Vortek) ou memória de vídeo. Tente outra versão do driver ou reduza a " +
                "resolução/limite de VRAM.",
        ) { containsAll(it, "device lost") || containsAll(it, "vk_error_device_lost") },
        Signature(
            id = "d3d-feature-level",
            severity = Severity.WARNING,
            message = "O jogo pediu um nível de DirectX que o wrapper atual não suporta. Tente trocar o " +
                "DX wrapper (DXVK↔WineD3D) ou o modelo de shader nas configurações gráficas.",
        ) { containsAll(it, "feature level") && containsAll(it, "not supported") },
    )

    /** Returns the first matching diagnosis for [text], or null if nothing recognised. */
    fun analyze(text: String): Diagnosis? {
        if (text.isBlank()) return null
        val sig = SIGNATURES.firstOrNull { it.matches(text) } ?: return null
        return Diagnosis(sig.id, sig.message, sig.severity)
    }
}
