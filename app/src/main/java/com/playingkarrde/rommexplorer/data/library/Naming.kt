package com.playingkarrde.rommexplorer.data.library

/** How platform folders inside the library are named. Names match the desktop app's config values. */
enum class FolderNaming(val label: String, val example: String) {
    FsSlug("RomM folder name", "snes, gba, psx"),
    Slug("Platform slug", "snes, gba, ps"),
    DisplayName("Platform name", "Super Nintendo Entertainment System");

    companion object {
        fun parse(value: String?): FolderNaming = entries.firstOrNull { it.name == value } ?: FsSlug
    }
}

/** First non-blank string, or "" when none is. */
fun firstNonBlank(vararg values: String?): String = values.firstOrNull { !it.isNullOrBlank() } ?: ""

/** Picks the platform folder name for [naming], falling back to "unknown", then sanitises it. */
fun platformFolder(naming: FolderNaming, fsSlug: String, slug: String, display: String): String {
    val order = when (naming) {
        FolderNaming.FsSlug -> listOf(fsSlug, slug, display)
        FolderNaming.Slug -> listOf(slug, fsSlug, display)
        FolderNaming.DisplayName -> listOf(display, fsSlug, slug)
    }
    return sanitizeComponent(order.firstOrNull { it.isNotBlank() } ?: "unknown")
}

private val ILLEGAL = setOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')

/** Makes a string safe as a single path component on every platform. Mirrors the desktop rules exactly. */
fun sanitizeComponent(s: String): String {
    val cleaned = buildString(s.length) {
        for (c in s) append(if (c in ILLEGAL || Character.isISOControl(c)) '_' else c)
    }
    val t = cleaned.trim().trimEnd('.', ' ')
    return if (t.isEmpty() || t == "..") "_" else t
}
