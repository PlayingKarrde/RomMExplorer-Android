package com.playingkarrde.rommexplorer.data.library

import com.playingkarrde.rommexplorer.data.api.Rom
import com.playingkarrde.rommexplorer.data.storage.LibraryStorage
import com.playingkarrde.rommexplorer.data.storage.Paths
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException

fun romFolder(rom: Rom, naming: FolderNaming) =
    platformFolder(naming, rom.platformFsSlug, rom.platformSlug, rom.platformLabel)

fun romFileName(rom: Rom) = sanitizeComponent(rom.fileName)

data class ReorganizeResult(val moved: Int, val skipped: Int)

/**
 * The local library: a folder of platform subfolders plus `.romm-explorer.json`.
 * The on-disk format is shared with the desktop app; see CLAUDE.md.
 * Manifest-changing methods are synchronized; [install] isn't, so two downloads can run at once.
 */
class Library(
    val storage: LibraryStorage,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val lock = Any()
    private var manifest = Manifest()

    val entries: Map<Long, ManifestEntry> get() = synchronized(lock) { manifest.roms }

    /** Loads the manifest. Throws [ManifestCorruptException] rather than ever replacing a bad one. */
    fun open() = synchronized(lock) {
        storage.invalidate()
        val path = when {
            storage.exists(MANIFEST_NAME) -> MANIFEST_NAME
            // A crash between "delete old" and "rename new" leaves only the temp file.
            storage.exists(MANIFEST_TMP_NAME) -> MANIFEST_TMP_NAME
            else -> null
        }
        manifest = if (path == null) Manifest() else {
            val text = storage.openRead(path).use { it.readBytes().toString(Charsets.UTF_8) }
            ManifestCodec.decode(text)
        }
    }

    private fun save() {
        val bytes = ManifestCodec.encode(manifest).toByteArray(Charsets.UTF_8)
        storage.delete(MANIFEST_TMP_NAME)
        storage.openWrite(MANIFEST_TMP_NAME).use { it.write(bytes) }
        storage.delete(MANIFEST_NAME)
        storage.move(MANIFEST_TMP_NAME, MANIFEST_NAME)
    }

    private fun update(block: (MutableMap<Long, ManifestEntry>) -> Unit) {
        val roms = LinkedHashMap(manifest.roms)
        block(roms)
        manifest = manifest.copy(roms = roms)
        save()
    }

    fun record(entry: ManifestEntry) = synchronized(lock) { update { it[entry.romId] = entry } }

    private fun removeIfEmpty(dir: String) {
        if (dir.isNotEmpty() && storage.isDirectory(dir) && storage.list(dir).isEmpty()) storage.delete(dir)
    }

    fun uninstall(romId: Long) = synchronized(lock) {
        val entry = manifest.roms[romId] ?: return@synchronized
        if (!Paths.isSafeRelative(entry.relPath)) {
            throw IOException("Refusing to delete \"${entry.relPath}\" because it points outside the library folder.")
        }
        storage.delete(entry.relPath)
        removeIfEmpty(Paths.parent(entry.relPath))
        update { it.remove(romId) }
    }

    /** Forgets entries whose files are gone. Returns how many. */
    fun verify(): Int = synchronized(lock) {
        storage.invalidate()
        val missing = manifest.roms.values.filter { !storage.exists(it.relPath) }.map { it.romId }
        if (missing.isNotEmpty()) update { m -> missing.forEach { m.remove(it) } }
        missing.size
    }

    /** Adds server ROMs whose files are already where we'd put them. Returns how many. */
    fun adoptExisting(roms: List<Rom>, naming: FolderNaming): Int = synchronized(lock) {
        storage.invalidate()
        val found = mutableListOf<ManifestEntry>()
        val listings = HashMap<String, Set<String>>()
        for (rom in roms) {
            if (rom.id in manifest.roms) continue
            val folder = romFolder(rom, naming)
            val names = listings.getOrPut(folder) { storage.list(folder).map { it.name }.toSet() }
            if (names.isEmpty()) continue
            val name = romFileName(rom)
            val candidates = buildList {
                add(name)
                if (rom.isMulti) add("$name.zip")
            }
            // Case-insensitive like the storage it usually lives on; record the name as it is on disk.
            val hit = candidates.firstNotNullOfOrNull { c -> if (c in names) c else names.firstOrNull { it.equals(c, ignoreCase = true) } } ?: continue
            val rel = Paths.join(folder, hit)
            found += entryFor(rom, rel, storage.size(rel))
        }
        if (found.isNotEmpty()) update { m -> found.forEach { m[it.romId] = it } }
        found.size
    }

    /** Moves installed ROMs to match [naming]. */
    fun reorganize(naming: FolderNaming): ReorganizeResult = synchronized(lock) {
        storage.invalidate()
        var moved = 0
        var skipped = 0
        val changed = mutableListOf<ManifestEntry>()
        for (e in manifest.roms.values.toList()) {
            val folder = platformFolder(naming, e.platformFsSlug, e.platformSlug, e.platformName)
            val leaf = Paths.name(e.relPath)
            if (leaf.isEmpty()) continue
            val newRel = Paths.join(folder, leaf)
            if (newRel == e.relPath) continue
            if (storage.exists(newRel) || !storage.exists(e.relPath)) {
                skipped++
                continue
            }
            storage.createDirs(folder)
            storage.move(e.relPath, newRel)
            removeIfEmpty(Paths.parent(e.relPath))
            changed += e.copy(relPath = newRel)
            moved++
        }
        if (changed.isNotEmpty()) update { m -> changed.forEach { m[it.romId] = it } }
        ReorganizeResult(moved, skipped)
    }

    fun entryFor(rom: Rom, relPath: String, size: Long) = ManifestEntry(
        romId = rom.id,
        name = rom.title,
        platformId = rom.platformId,
        platformSlug = rom.platformSlug,
        platformFsSlug = rom.platformFsSlug,
        platformName = rom.platformLabel,
        relPath = relPath,
        sizeBytes = size,
        installedAt = now(),
    )

    /**
     * Downloads [rom] into the library via [download] (which writes the whole body to the stream and
     * should throw [CancellationException] when cancelled) and returns the manifest entry to record.
     * On any failure nothing is left behind.
     */
    fun install(
        rom: Rom,
        naming: FolderNaming,
        extractMulti: Boolean,
        isCancelled: () -> Boolean = { false },
        onExtracting: () -> Unit = {},
        download: (OutputStream) -> Unit,
    ): ManifestEntry {
        val folder = romFolder(rom, naming)
        val name = romFileName(rom)
        storage.createDirs(folder)

        val rel: String
        if (rom.isMulti) {
            val part = Paths.join(folder, ".$name.zip.part")
            writePart(part, download)
            if (extractMulti) {
                onExtracting()
                val staging = Paths.join(folder, ".$name.extracting")
                val target = Paths.join(folder, name)
                try {
                    storage.delete(staging)
                    extractZip(part, staging, isCancelled)
                    // Flatten an archive whose only top-level entry is a folder named like the target.
                    val children = storage.list(staging)
                    val src = if (children.size == 1 && children[0].isDirectory && children[0].name == name) {
                        Paths.join(staging, name)
                    } else staging
                    storage.delete(target)
                    storage.move(src, target)
                    storage.delete(staging)
                } catch (t: Throwable) {
                    runCatching { storage.delete(staging) }
                    throw t
                } finally {
                    runCatching { storage.delete(part) }
                }
                rel = target
            } else {
                rel = Paths.join(folder, "$name.zip")
                finishPart(part, rel)
            }
        } else {
            val part = Paths.join(folder, "$name.part")
            writePart(part, download)
            rel = Paths.join(folder, name)
            finishPart(part, rel)
        }
        return entryFor(rom, rel, storage.size(rel))
    }

    private fun writePart(part: String, download: (OutputStream) -> Unit) {
        try {
            storage.openWrite(part).buffered(1 shl 16).use(download)
        } catch (t: Throwable) {
            runCatching { storage.delete(part) }
            throw t
        }
    }

    private fun finishPart(part: String, dest: String) {
        try {
            storage.delete(dest)
            storage.move(part, dest)
        } catch (t: Throwable) {
            runCatching { storage.delete(part) }
            throw t
        }
    }

    /** Extracts the zip at [zipPath] into folder [target], skipping entries that would escape it. */
    fun extractZip(zipPath: String, target: String, isCancelled: () -> Boolean) {
        storage.createDirs(target)
        val created = HashSet<String>()
        var entries = 0
        ZipInputStream(storage.openRead(zipPath).buffered(1 shl 16)).use { zip ->
            while (true) {
                if (isCancelled()) throw CancellationException("Cancelled")
                val entry = zip.nextEntry ?: break
                entries++
                val rel = safeEntryPath(entry.name) ?: continue
                val out = Paths.join(target, rel)
                if (entry.isDirectory) {
                    if (created.add(out)) storage.createDirs(out)
                } else {
                    val parent = Paths.parent(out)
                    if (created.add(parent)) storage.createDirs(parent)
                    storage.openWrite(out).buffered(1 shl 16).use { zip.copyTo(it, 1 shl 16) }
                }
            }
        }
        // ZipInputStream reports "no entries" rather than failing on data that isn't a zip.
        if (entries == 0) throw IOException("The downloaded archive is empty or isn't a valid zip file.")
    }

    companion object {
        /** Normalises a zip entry name, or returns null if it's absolute or escapes with `..`. */
        fun safeEntryPath(name: String): String? {
            val n = name.replace('\\', '/')
            if (n.startsWith("/") || (n.length >= 2 && n[1] == ':')) return null
            val segs = n.split('/').filter { it.isNotEmpty() && it != "." }
            if (segs.isEmpty() || segs.any { it == ".." }) return null
            return segs.joinToString("/")
        }
    }
}
