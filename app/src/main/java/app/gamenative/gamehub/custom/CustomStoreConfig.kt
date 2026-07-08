package app.gamenative.gamehub.custom

import org.json.JSONArray
import org.json.JSONObject

/**
 * Game Hub — user-defined store adapter, config-driven.
 *
 * Describes how to talk to a legitimate store's official "my library" API so a new store can be
 * added at runtime (a filled-in form) without recompiling. This is deliberately generic over any
 * store that exposes an authenticated endpoint returning the games the user owns; it does NOT and
 * must not be used to import arbitrary download-link lists.
 *
 * All fields are plain strings so they map 1:1 to a form the user fills in.
 */
data class CustomStoreConfig(
    /** Stable unique id (slug), e.g. "itchio". Used as the provider key. */
    val id: String,
    /** Human-facing store name shown in the Stores tab and library, e.g. "itch.io". */
    val name: String,
    /** Optional store icon URL. */
    val iconUrl: String = "",

    // --- Authentication ---
    /** How the endpoint is authenticated. */
    val authType: AuthType = AuthType.NONE,
    /** Header carrying the credential (for API_KEY / BEARER), e.g. "Authorization". */
    val authHeaderName: String = "Authorization",
    /** For BEARER, the value prefix (usually "Bearer "). Ignored for other types. */
    val authScheme: String = "Bearer ",
    /** The secret token / API key the user pastes in. Stored locally only. */
    val authToken: String = "",

    // --- Library request ---
    /** HTTP method for the library request. */
    val httpMethod: String = "GET",
    /** URL returning the user's owned games (JSON). */
    val libraryEndpoint: String = "",
    /** Optional extra request headers, one per line as "Name: Value". */
    val extraHeaders: String = "",

    // --- Response parsing ---
    /** Dot-path to the array of games in the JSON response (blank = the root is the array). */
    val gamesArrayPath: String = "",
    /** Key in each game object holding the store's game id. */
    val fieldId: String = "id",
    /** Key holding the game title. */
    val fieldName: String = "name",
    /** Key holding a cover/image URL (optional). */
    val fieldCover: String = "cover",
    /** Key holding the developer (optional). */
    val fieldDeveloper: String = "developer",
    /** Key holding an "installed"/owned flag (optional; blank = treat as not-installed). */
    val fieldInstalled: String = "",

    /** Whether this store is active (registered into the hub). */
    val enabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("iconUrl", iconUrl)
        .put("authType", authType.name)
        .put("authHeaderName", authHeaderName)
        .put("authScheme", authScheme)
        .put("authToken", authToken)
        .put("httpMethod", httpMethod)
        .put("libraryEndpoint", libraryEndpoint)
        .put("extraHeaders", extraHeaders)
        .put("gamesArrayPath", gamesArrayPath)
        .put("fieldId", fieldId)
        .put("fieldName", fieldName)
        .put("fieldCover", fieldCover)
        .put("fieldDeveloper", fieldDeveloper)
        .put("fieldInstalled", fieldInstalled)
        .put("enabled", enabled)

    companion object {
        fun fromJson(obj: JSONObject): CustomStoreConfig = CustomStoreConfig(
            id = obj.optString("id"),
            name = obj.optString("name"),
            iconUrl = obj.optString("iconUrl"),
            authType = runCatching { AuthType.valueOf(obj.optString("authType", "NONE")) }
                .getOrDefault(AuthType.NONE),
            authHeaderName = obj.optString("authHeaderName", "Authorization"),
            authScheme = obj.optString("authScheme", "Bearer "),
            authToken = obj.optString("authToken"),
            httpMethod = obj.optString("httpMethod", "GET"),
            libraryEndpoint = obj.optString("libraryEndpoint"),
            extraHeaders = obj.optString("extraHeaders"),
            gamesArrayPath = obj.optString("gamesArrayPath"),
            fieldId = obj.optString("fieldId", "id"),
            fieldName = obj.optString("fieldName", "name"),
            fieldCover = obj.optString("fieldCover", "cover"),
            fieldDeveloper = obj.optString("fieldDeveloper", "developer"),
            fieldInstalled = obj.optString("fieldInstalled"),
            enabled = obj.optBoolean("enabled", true),
        )

        fun listToJson(configs: List<CustomStoreConfig>): String {
            val arr = JSONArray()
            configs.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJson(json: String?): List<CustomStoreConfig> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(json)
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { fromJson(it) }
                }.filter { it.id.isNotBlank() }
            }.getOrDefault(emptyList())
        }
    }
}

/** Supported authentication schemes for a [CustomStoreConfig]. */
enum class AuthType {
    /** No auth header sent. */
    NONE,

    /** Send the token verbatim in [CustomStoreConfig.authHeaderName]. */
    API_KEY,

    /** Send "[CustomStoreConfig.authScheme]<token>" in [CustomStoreConfig.authHeaderName]. */
    BEARER,
}
