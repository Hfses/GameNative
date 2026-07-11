package app.gamenative.utils

import kotlinx.serialization.Serializable

@Serializable
data class ManifestEntry(
    val id: String,
    val name: String,
    val url: String,
    val variant: String? = null,
    val arch: String? = null,
)

@Serializable
data class ManifestData(
    val version: Int?,
    val updatedAt: String?,
    val items: Map<String, List<ManifestEntry>>,
) {
    /**
     * Unions another catalog into this one: for each content type the entries are concatenated and
     * de-duplicated by id, with [other]'s entries taking precedence (so a custom source can override
     * an upstream entry of the same id, and new ids are simply appended as extra download options).
     */
    fun merge(other: ManifestData): ManifestData {
        if (other.items.isEmpty()) return this
        if (this.items.isEmpty()) return other
        val types = this.items.keys + other.items.keys
        val mergedItems = types.associateWith { type ->
            val base = this.items[type].orEmpty()
            val overlay = other.items[type].orEmpty()
            val byId = LinkedHashMap<String, ManifestEntry>()
            base.forEach { byId[it.id] = it }
            overlay.forEach { byId[it.id] = it }
            byId.values.toList()
        }
        return ManifestData(
            version = other.version ?: this.version,
            updatedAt = other.updatedAt ?: this.updatedAt,
            items = mergedItems,
        )
    }

    companion object {
        fun empty(): ManifestData = ManifestData(null, null, emptyMap())
    }
}

object ManifestContentTypes {
    const val DRIVER = "driver"
    const val DXVK = "dxvk"
    const val VKD3D = "vkd3d"
    const val BOX64 = "box64"
    const val WOWBOX64 = "wowbox64"
    const val FEXCORE = "fexcore"
    const val WINE = "wine"
    const val PROTON = "proton"
}
