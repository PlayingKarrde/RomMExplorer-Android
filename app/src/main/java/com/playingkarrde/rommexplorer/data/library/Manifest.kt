package com.playingkarrde.rommexplorer.data.library

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

const val MANIFEST_NAME = ".romm-explorer.json"
const val MANIFEST_TMP_NAME = ".romm-explorer.json.tmp"

/** One installed ROM. [relPath] is always held with `/` separators. [extra] keeps fields we don't know. */
data class ManifestEntry(
    val romId: Long,
    val name: String,
    val platformId: Long,
    val platformSlug: String,
    val platformFsSlug: String,
    val platformName: String,
    val relPath: String,
    val sizeBytes: Long,
    val installedAt: Long,
    val extra: JsonObject = JsonObject(emptyMap()),
)

data class Manifest(
    val version: Int = 1,
    val roms: Map<Long, ManifestEntry> = emptyMap(),
    /** Entries we couldn't understand, written back untouched, keyed by their original key. */
    val unparsed: Map<String, JsonElement> = emptyMap(),
    /** Unknown top-level fields. */
    val extra: JsonObject = JsonObject(emptyMap()),
)

class ManifestCorruptException(message: String, cause: Throwable? = null) : Exception(message, cause)

object ManifestCodec {
    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }
    private val ENTRY_KEYS = setOf(
        "rom_id", "name", "platform_id", "platform_slug", "platform_fs_slug",
        "platform_name", "rel_path", "size_bytes", "installed_at",
    )

    /** Accepts `/` or `\` (the Windows desktop build writes backslashes); always returns `/`. */
    fun normalizeRelPath(p: String): String = p.replace('\\', '/').trim('/')

    fun decode(text: String): Manifest {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            throw ManifestCorruptException("The library manifest ($MANIFEST_NAME) is not valid JSON.", e)
        } ?: throw ManifestCorruptException("The library manifest ($MANIFEST_NAME) has an unexpected format.")

        val version = (root["version"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 1
        val romsObj = when (val r = root["roms"]) {
            null -> JsonObject(emptyMap())
            is JsonObject -> r
            else -> throw ManifestCorruptException("The library manifest's \"roms\" section is invalid.")
        }
        val roms = LinkedHashMap<Long, ManifestEntry>()
        val unparsed = LinkedHashMap<String, JsonElement>()
        for ((key, value) in romsObj) {
            val entry = (value as? JsonObject)?.let(::decodeEntry)
            if (entry == null) unparsed[key] = value else roms[entry.romId] = entry
        }
        val extra = JsonObject(root.filterKeys { it != "version" && it != "roms" })
        return Manifest(version, roms, unparsed, extra)
    }

    private fun decodeEntry(o: JsonObject): ManifestEntry? {
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: ""
        fun long(k: String) = (o[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }
        val id = long("rom_id") ?: return null
        val rel = (o["rel_path"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        return ManifestEntry(
            romId = id,
            name = str("name"),
            platformId = long("platform_id") ?: 0,
            platformSlug = str("platform_slug"),
            platformFsSlug = str("platform_fs_slug"),
            platformName = str("platform_name"),
            relPath = normalizeRelPath(rel),
            sizeBytes = long("size_bytes") ?: 0,
            installedAt = long("installed_at") ?: 0,
            extra = JsonObject(o.filterKeys { it !in ENTRY_KEYS }),
        )
    }

    fun encodeEntry(e: ManifestEntry): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        m["rom_id"] = JsonPrimitive(e.romId)
        m["name"] = JsonPrimitive(e.name)
        m["platform_id"] = JsonPrimitive(e.platformId)
        m["platform_slug"] = JsonPrimitive(e.platformSlug)
        m["platform_fs_slug"] = JsonPrimitive(e.platformFsSlug)
        m["platform_name"] = JsonPrimitive(e.platformName)
        m["rel_path"] = JsonPrimitive(normalizeRelPath(e.relPath))
        m["size_bytes"] = JsonPrimitive(e.sizeBytes)
        m["installed_at"] = JsonPrimitive(e.installedAt)
        for ((k, v) in e.extra) if (k !in m) m[k] = v
        return JsonObject(m)
    }

    fun encode(m: Manifest): String {
        val all = LinkedHashMap<String, JsonElement>()
        for ((id, e) in m.roms) all[id.toString()] = encodeEntry(e)
        for ((k, v) in m.unparsed) if (k !in all) all[k] = v
        // Sort numerically, like the desktop app's BTreeMap<i64, _>.
        val roms = LinkedHashMap<String, JsonElement>()
        all.entries.sortedWith(compareBy<Map.Entry<String, JsonElement>>({ it.key.toLongOrNull() ?: Long.MAX_VALUE }, { it.key }))
            .forEach { roms[it.key] = it.value }
        val root = LinkedHashMap<String, JsonElement>()
        root["version"] = JsonPrimitive(m.version)
        root["roms"] = JsonObject(roms)
        for ((k, v) in m.extra) if (k !in root) root[k] = v
        return json.encodeToString(JsonElement.serializer(), JsonObject(root))
    }
}
