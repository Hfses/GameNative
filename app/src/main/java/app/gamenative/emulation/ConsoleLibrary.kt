package app.gamenative.emulation

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Console games the user imported. ROMs are copied into app-specific external storage
 * (cores need a real file path, and SAF URIs can't be opened by native code).
 */
object ConsoleLibrary {

    data class ConsoleGame(val systemId: String, val title: String, val path: String) {
        val system: ConsoleSystem? get() = ConsoleSystem.fromId(systemId)
        val file: File get() = File(path)
    }

    private val _games = MutableStateFlow<List<ConsoleGame>>(emptyList())
    val games: StateFlow<List<ConsoleGame>> = _games.asStateFlow()
    @Volatile private var loaded = false

    private fun indexFile(context: Context) = File(context.filesDir, "libretro/library.json")

    private fun romsDir(context: Context, system: ConsoleSystem) =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "console/roms/${system.id}").apply { mkdirs() }

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val f = indexFile(context)
        if (!f.isFile) return
        _games.value = runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                ConsoleGame(it.getString("system"), it.getString("title"), it.getString("path"))
            }.filter { File(it.path).isFile }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context) {
        val arr = JSONArray()
        _games.value.forEach {
            arr.put(JSONObject().put("system", it.systemId).put("title", it.title).put("path", it.path))
        }
        indexFile(context).apply { parentFile?.mkdirs() }.writeText(arr.toString())
    }

    /** Copies [uri] into the library for [system]. Returns the imported game. */
    suspend fun import(context: Context, system: ConsoleSystem, uri: Uri): ConsoleGame = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val rawName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "game"
        // Strip any path pieces from the provider-supplied name.
        val name = rawName.substringAfterLast('/').substringAfterLast('\\').ifBlank { "game" }
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in system.extensions && ext != "zip" && ext != "7z") {
            throw IOException("Formato .$ext não é suportado por ${system.displayName}. Use: ${system.extensions.joinToString()}")
        }
        val dest = File(romsDir(context, system), name)
        val tmp = File(dest.parentFile, "$name.part")
        resolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { input.copyTo(it, 1024 * 1024) }
        } ?: throw IOException("Não foi possível ler o arquivo")
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) throw IOException("Falha ao salvar o jogo")
        val game = ConsoleGame(system.id, name.substringBeforeLast('.'), dest.absolutePath)
        load(context)
        _games.value = _games.value.filterNot { it.path == game.path } + game
        save(context)
        game
    }

    /** Imports a BIOS/firmware file into the shared libretro system dir. */
    suspend fun importBios(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }?.substringAfterLast('/')?.ifBlank { null } ?: throw IOException("Nome de arquivo inválido")
        val dest = File(CoreManager.systemDir(context), name)
        resolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
            ?: throw IOException("Não foi possível ler o arquivo")
        name
    }

    fun remove(context: Context, game: ConsoleGame) {
        File(game.path).delete()
        _games.value = _games.value.filterNot { it.path == game.path }
        save(context)
    }
}
