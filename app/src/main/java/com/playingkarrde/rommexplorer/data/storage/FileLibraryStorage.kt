package com.playingkarrde.rommexplorer.data.storage

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** [LibraryStorage] on a plain folder. Used by the JVM tests. */
class FileLibraryStorage(private val root: File) : LibraryStorage {
    private fun f(path: String) = if (path.isEmpty()) root else File(root, path)

    override fun exists(path: String) = f(path).exists()
    override fun isDirectory(path: String) = f(path).isDirectory
    override fun list(path: String): List<StorageEntry> =
        f(path).listFiles()?.map { StorageEntry(it.name, it.isDirectory, if (it.isFile) it.length() else 0) } ?: emptyList()

    override fun size(path: String): Long {
        fun walk(x: File): Long = if (x.isFile) x.length() else x.listFiles()?.sumOf(::walk) ?: 0
        return f(path).let { if (it.exists()) walk(it) else 0 }
    }

    override fun createDirs(path: String) {
        val d = f(path)
        if (!d.isDirectory && !d.mkdirs()) throw IOException("Could not create folder $path")
    }

    override fun openWrite(path: String): OutputStream {
        f(path).parentFile?.mkdirs()
        return f(path).outputStream()
    }

    override fun openRead(path: String): InputStream {
        val x = f(path)
        if (!x.isFile) throw FileNotFoundException(path)
        return x.inputStream()
    }

    override fun move(from: String, to: String) {
        val src = f(from)
        val dst = f(to)
        if (dst.exists()) throw IOException("$to already exists")
        dst.parentFile?.mkdirs()
        if (!src.renameTo(dst)) throw IOException("Could not move $from to $to")
    }

    override fun delete(path: String): Boolean {
        val x = f(path)
        if (!x.exists()) return false
        if (!x.deleteRecursively()) throw IOException("Could not delete $path")
        return true
    }
}
