package com.playingkarrde.rommexplorer.data.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * [LibraryStorage] over a Storage Access Framework tree URI. Uses DocumentsContract queries directly
 * (DocumentFile.findFile is a query per call) and caches each folder's listing until something changes it.
 */
class SafLibraryStorage(context: Context, private val treeUri: Uri) : LibraryStorage {
    private val resolver: ContentResolver = context.contentResolver
    private val rootId: String = DocumentsContract.getTreeDocumentId(treeUri)

    private data class Child(val docId: String, val name: String, val isDir: Boolean, val size: Long)

    /** Folder path -> children by name. */
    private val listings = ConcurrentHashMap<String, Map<String, Child>>()

    private fun docUri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)

    private fun query(dirId: String): Map<String, Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId)
        val proj = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)
        val out = HashMap<String, Child>()
        resolver.query(uri, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val isDir = c.getString(2) == Document.MIME_TYPE_DIR
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                out[name] = Child(id, name, isDir, size)
            }
        } ?: throw IOException("The library folder can't be read. Pick it again in Settings.")
        return out
    }

    private fun rootChild() = Child(rootId, "", true, 0)

    private fun children(dir: String): Map<String, Child>? {
        listings[dir]?.let { return it }
        val d = resolve(dir) ?: return null
        if (!d.isDir) return null
        return query(d.docId).also { listings[dir] = it }
    }

    /**
     * Exact name first, then case-insensitive: shared storage and SD cards (FAT/exFAT) ignore case, so an
     * existing "Switch" folder *is* "switch" there, and asking the provider to create "switch" would give
     * us "switch (1)" instead. This also matches what the Windows desktop build sees.
     */
    private fun resolve(path: String): Child? {
        if (path.isEmpty()) return rootChild()
        val kids = children(Paths.parent(path)) ?: return null
        val name = Paths.name(path)
        return kids[name] ?: kids.values.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun require(path: String) = resolve(path) ?: throw FileNotFoundException("$path doesn't exist")

    private fun changed(dir: String) {
        listings.remove(dir)
    }

    private fun forgetTree(path: String) {
        listings.keys.removeAll { it == path || it.startsWith("$path/") }
    }

    override fun invalidate() = listings.clear()

    override fun exists(path: String) = resolve(path) != null
    override fun isDirectory(path: String) = resolve(path)?.isDir == true

    override fun list(path: String): List<StorageEntry> =
        children(path)?.values?.map { StorageEntry(it.name, it.isDir, it.size) } ?: emptyList()

    override fun size(path: String): Long {
        val c = resolve(path) ?: return 0
        if (!c.isDir) return c.size
        return list(path).sumOf { if (it.isDirectory) size(Paths.join(path, it.name)) else it.size }
    }

    override fun createDirs(path: String) {
        if (path.isEmpty()) return
        var cur = ""
        for (seg in path.split('/')) {
            val next = Paths.join(cur, seg)
            val existing = resolve(next)
            if (existing == null) {
                val parent = require(cur)
                val created = DocumentsContract.createDocument(resolver, docUri(parent.docId), Document.MIME_TYPE_DIR, seg)
                    ?: throw IOException("Couldn't create the folder \"$next\" in your library. Check the folder is writable, or pick it again in Settings.")
                changed(cur)
                if (resolve(next)?.isDir != true) {
                    val actual = displayName(created)
                    runCatching { DocumentsContract.deleteDocument(resolver, created) }
                    throw IOException("Couldn't create the folder \"$next\" (the storage made \"${actual ?: "?"}\" instead). Rename or remove whatever is in the way and retry.")
                }
            } else if (!existing.isDir) {
                throw IOException("\"$next\" in your library is a file, but a folder is needed there. Rename or remove it and retry.")
            }
            cur = next
        }
    }

    override fun openWrite(path: String): OutputStream {
        val parentPath = Paths.parent(path)
        val name = Paths.name(path)
        createDirs(parentPath)
        val existing = resolve(path)
        val uri = if (existing != null) {
            docUri(existing.docId)
        } else {
            val parent = require(parentPath)
            val created = DocumentsContract.createDocument(resolver, docUri(parent.docId), "application/octet-stream", name)
                ?: throw IOException("Could not create $path")
            changed(parentPath)
            if (resolve(path) == null) {
                // The provider renamed it (e.g. added an extension); undo and fail loudly.
                runCatching { DocumentsContract.deleteDocument(resolver, created) }
                throw IOException("The storage provider wouldn't create a file named \"$name\".")
            }
            created
        }
        val stream = resolver.openOutputStream(uri, "wt") ?: throw IOException("Could not write $path")
        return object : java.io.FilterOutputStream(stream) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() {
                try { super.close() } finally { changed(parentPath) }
            }
        }
    }

    override fun openRead(path: String): InputStream {
        val c = require(path)
        return resolver.openInputStream(docUri(c.docId)) ?: throw IOException("Could not read $path")
    }

    override fun move(from: String, to: String) {
        val src = require(from)
        if (resolve(to) != null) throw IOException("$to already exists")
        val fromParent = Paths.parent(from)
        val toParent = Paths.parent(to)
        val newName = Paths.name(to)
        createDirs(toParent)
        var uri = docUri(src.docId)
        if (fromParent != toParent) {
            val moved = runCatching {
                DocumentsContract.moveDocument(resolver, uri, docUri(require(fromParent).docId), docUri(require(toParent).docId))
            }.getOrNull()
            if (moved == null) {
                copyTree(from, to)
                delete(from)
                return
            }
            uri = moved
            forgetTree(from)
            changed(fromParent)
            changed(toParent)
        }
        if (src.name != newName) {
            DocumentsContract.renameDocument(resolver, uri, newName) ?: throw IOException("Could not rename $from")
            forgetTree(from)
            changed(toParent)
        }
        if (resolve(to) == null) throw IOException("Moving $from to $to failed")
    }

    private fun copyTree(from: String, to: String) {
        val c = require(from)
        if (c.isDir) {
            createDirs(to)
            for (child in list(from)) copyTree(Paths.join(from, child.name), Paths.join(to, child.name))
        } else {
            openRead(from).use { input -> openWrite(to).use { input.copyTo(it, 1 shl 16) } }
        }
    }

    override fun delete(path: String): Boolean {
        if (path.isEmpty()) throw IOException("Refusing to delete the library root")
        val c = resolve(path) ?: return false
        if (!DocumentsContract.deleteDocument(resolver, docUri(c.docId))) throw IOException("Could not delete $path")
        forgetTree(path)
        changed(Paths.parent(path))
        return true
    }
}
