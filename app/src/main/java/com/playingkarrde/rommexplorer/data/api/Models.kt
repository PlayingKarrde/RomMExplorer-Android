package com.playingkarrde.rommexplorer.data.api

import com.playingkarrde.rommexplorer.data.library.firstNonBlank
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

data class Platform(
    val id: Long,
    val slug: String,
    val fsSlug: String,
    val name: String,
    val customName: String,
    val displayName: String,
    val romCount: Long,
) {
    val label: String get() = firstNonBlank(displayName, customName, name, slug)

    companion object {
        fun parse(o: JsonObject) = Platform(
            id = o.long("id") ?: 0,
            slug = o.str("slug"),
            fsSlug = o.str("fs_slug"),
            name = o.str("name"),
            customName = o.str("custom_name"),
            displayName = o.str("display_name"),
            romCount = o.long("rom_count") ?: 0,
        )
    }
}

/**
 * A ROM as RomM describes it. Parsed leniently: unknown fields are ignored and nulls count as missing.
 * [raw] keeps the original JSON so a queued download can be persisted and restored.
 */
data class Rom(
    val id: Long,
    val name: String,
    val fsName: String,
    val fsNameNoExt: String,
    val fsSizeBytes: Long,
    val platformId: Long,
    val platformSlug: String,
    val platformFsSlug: String,
    val platformDisplayName: String,
    val platformCustomName: String,
    val platformName: String,
    val summary: String,
    val regions: List<String>,
    val languages: List<String>,
    val revision: String,
    val pathCoverSmall: String,
    val pathCoverLarge: String,
    val urlCover: String,
    val hasMultipleFiles: Boolean,
    val multi: Boolean,
    val metadatum: JsonObject?,
    val igdbMetadata: JsonObject?,
    val raw: JsonObject,
) {
    val title: String get() = firstNonBlank(name, fsNameNoExt, fsName)
    val isMulti: Boolean get() = hasMultipleFiles || multi
    val platformLabel: String get() = firstNonBlank(platformDisplayName, platformCustomName, platformName, platformSlug)

    /** The file or folder name RomM stores this ROM under (unsanitised). */
    val fileName: String get() = fsName.ifEmpty { "rom-$id" }

    private fun metaList(key: String): List<String> {
        for (m in listOf(metadatum, igdbMetadata)) {
            val list = stringList(m?.get(key))
            if (list.isNotEmpty()) return list
        }
        return emptyList()
    }

    val genres: List<String> get() = metaList("genres")
    val developers: List<String> get() = metaList("developers").ifEmpty { metaList("companies") }

    /** Release date in unix seconds, or null when unknown. */
    val releaseTimestamp: Long?
        get() {
            for (m in listOf(metadatum, igdbMetadata)) {
                val p = m?.get("first_release_date") as? JsonPrimitive ?: continue
                val ts = p.longOrNull ?: p.doubleOrNull?.toLong() ?: p.contentOrNull?.trim()?.toLongOrNull() ?: continue
                if (ts == 0L) continue
                return if (kotlin.math.abs(ts) > 100_000_000_000L) ts / 1000 else ts
            }
            return null
        }

    /** Rating on a 0–100 scale. */
    val rating: Double?
        get() {
            for ((m, key) in listOf(metadatum to "average_rating", igdbMetadata to "total_rating")) {
                val p = m?.get(key) as? JsonPrimitive ?: continue
                val r = p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull() ?: continue
                if (r > 0) return if (r <= 10) r * 10 else r
            }
            return null
        }

    companion object {
        fun parse(o: JsonObject) = Rom(
            id = o.long("id") ?: 0,
            name = o.str("name"),
            fsName = o.str("fs_name"),
            fsNameNoExt = o.str("fs_name_no_ext"),
            fsSizeBytes = o.long("fs_size_bytes") ?: 0,
            platformId = o.long("platform_id") ?: 0,
            platformSlug = o.str("platform_slug"),
            platformFsSlug = o.str("platform_fs_slug"),
            platformDisplayName = o.str("platform_display_name"),
            platformCustomName = o.str("platform_custom_name"),
            platformName = o.str("platform_name"),
            summary = o.str("summary"),
            regions = stringList(o["regions"]),
            languages = stringList(o["languages"]),
            revision = o.str("revision"),
            pathCoverSmall = o.str("path_cover_small"),
            pathCoverLarge = o.str("path_cover_large"),
            urlCover = o.str("url_cover"),
            hasMultipleFiles = o.bool("has_multiple_files"),
            multi = o.bool("multi"),
            metadatum = o["metadatum"] as? JsonObject,
            igdbMetadata = o["igdb_metadata"] as? JsonObject,
            raw = o,
        )
    }
}

internal fun JsonObject.str(key: String): String {
    val p = this[key] as? JsonPrimitive ?: return ""
    if (p is JsonNull) return ""
    return p.contentOrNull ?: ""
}

internal fun JsonObject.long(key: String): Long? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.longOrNull ?: p.doubleOrNull?.toLong() ?: p.contentOrNull?.toLongOrNull()
}

internal fun JsonObject.bool(key: String): Boolean {
    val p = this[key] as? JsonPrimitive ?: return false
    if (p is JsonNull) return false
    return p.booleanOrNull ?: (p.longOrNull?.let { it != 0L } ?: false)
}

/** A list of strings, or of objects with a `name`. Anything else is skipped. */
internal fun stringList(e: JsonElement?): List<String> {
    val arr = e as? JsonArray ?: return emptyList()
    return arr.mapNotNull { item ->
        when (item) {
            is JsonNull -> null
            is JsonPrimitive -> item.contentOrNull
            is JsonObject -> (item["name"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
            else -> null
        }
    }.filter { it.isNotBlank() }
}
