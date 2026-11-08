package com.playingkarrde.rommexplorer.data.storage

import java.io.InputStream
import java.io.OutputStream

data class StorageEntry(val name: String, val isDirectory: Boolean, val size: Long)

/**
 * All file access to the library goes through here. Paths are relative to the library root,
 * use `/` as the separator, and "" is the root itself. Implementations must be thread-safe.
 */
interface LibraryStorage {
    fun exists(path: String): Boolean
    fun isDirectory(path: String): Boolean
    /** Children of a directory; empty when it doesn't exist. */
    fun list(path: String): List<StorageEntry>
    /** File size, or the total size of a folder. 0 if missing. */
    fun size(path: String): Long
    fun createDirs(path: String)
    /** Creates (or truncates) a file, creating parent folders as needed. */
    fun openWrite(path: String): OutputStream
    fun openRead(path: String): InputStream
    /** Moves or renames. The destination must not exist; its parent is created if needed. */
    fun move(from: String, to: String)
    /** Deletes a file or folder recursively. Returns false if it didn't exist. */
    fun delete(path: String): Boolean
    /** Drops any cached listings (call at the start of an operation that may see outside changes). */
    fun invalidate() {}
}

object Paths {
    fun join(vararg parts: String): String = parts.filter { it.isNotEmpty() }.joinToString("/")
    fun parent(path: String): String = path.substringBeforeLast('/', "")
    fun name(path: String): String = path.substringAfterLast('/')

    /** True if [path] is a non-empty relative path that stays inside the root. */
    fun isSafeRelative(path: String): Boolean {
        if (path.isBlank()) return false
        if (path.startsWith("/") || path.startsWith("\\")) return false
        if (path.length >= 2 && path[1] == ':') return false
        val segs = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        return segs.isNotEmpty() && segs.none { it == ".." || it == "." }
    }
}
