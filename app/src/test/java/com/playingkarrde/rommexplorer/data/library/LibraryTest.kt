package com.playingkarrde.rommexplorer.data.library

import com.playingkarrde.rommexplorer.data.api.Rom
import com.playingkarrde.rommexplorer.data.storage.FileLibraryStorage
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.cancellation.CancellationException

class LibraryTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var root: File
    private lateinit var lib: Library

    @Before fun setUp() {
        root = tmp.newFolder("lib")
        lib = Library(FileLibraryStorage(root), now = { 1730937600 })
        lib.open()
    }

    private fun rom(id: Long, fsName: String, multi: Boolean = false, fsSlug: String = "snes", slug: String = "snes", name: String = "Super Nintendo") =
        Rom.parse(
            Json.parseToJsonElement(
                """{"id":$id,"name":"Game $id","fs_name":${Json.encodeToString(String.serializer(), fsName)},"platform_id":7,
                   "platform_slug":"$slug","platform_fs_slug":"$fsSlug","platform_name":"$name","has_multiple_files":$multi}"""
            ) as JsonObject
        )

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, body) in entries) {
                z.putNextEntry(ZipEntry(name))
                if (!name.endsWith("/")) z.write(body.toByteArray())
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun leftovers(dir: File = root): List<String> =
        dir.walkTopDown().filter { it != dir }.map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()

    @Test fun singleFileInstallAndUninstall() {
        val r = rom(1, "Super Mario World (USA).sfc")
        val e = lib.install(r, FolderNaming.FsSlug, true) { it.write(ByteArray(1000)) }
        assertEquals("snes/Super Mario World (USA).sfc", e.relPath)
        assertEquals(1000, e.sizeBytes)
        assertEquals("Super Nintendo", e.platformName)
        assertEquals(1730937600, e.installedAt)
        lib.record(e)
        assertTrue(File(root, "snes/Super Mario World (USA).sfc").isFile)
        assertFalse(File(root, "snes/Super Mario World (USA).sfc.part").exists())

        // Manifest is on disk and readable by a fresh Library.
        val again = Library(FileLibraryStorage(root)).apply { open() }
        assertEquals(e, again.entries[1])

        lib.uninstall(1)
        assertNull(lib.entries[1])
        assertEquals(listOf(MANIFEST_NAME), leftovers())
    }

    @Test fun replacesExistingFile() {
        File(root, "snes").mkdirs()
        File(root, "snes/a.sfc").writeText("old")
        val e = lib.install(rom(1, "a.sfc"), FolderNaming.FsSlug, true) { it.write("new!".toByteArray()) }
        assertEquals("new!", File(root, e.relPath).readText())
    }

    @Test fun emptyFsNameUsesRomId() {
        val e = lib.install(rom(42, ""), FolderNaming.FsSlug, true) { it.write(1) }
        assertEquals("snes/rom-42", e.relPath)
    }

    @Test fun sanitisesNames() {
        val e = lib.install(rom(1, "What?: A Game.sfc", name = "x", fsSlug = "", slug = "", ), FolderNaming.FsSlug, true) { it.write(1) }
        assertEquals("x/What__ A Game.sfc", e.relPath)
    }

    @Test fun multiFileExtractsAndFlattens() {
        val r = rom(2, "Final Fantasy VII (USA)", multi = true, fsSlug = "psx")
        val data = zip("Final Fantasy VII (USA)/" to "", "Final Fantasy VII (USA)/disc1.bin" to "aaaa", "Final Fantasy VII (USA)/game.cue" to "bb")
        val e = lib.install(r, FolderNaming.FsSlug, true) { it.write(data) }
        assertEquals("psx/Final Fantasy VII (USA)", e.relPath)
        assertEquals(6, e.sizeBytes)
        assertEquals(
            listOf("psx", "psx/Final Fantasy VII (USA)", "psx/Final Fantasy VII (USA)/disc1.bin", "psx/Final Fantasy VII (USA)/game.cue"),
            leftovers(),
        )
    }

    @Test fun multiFileWithoutWrapperFolder() {
        val r = rom(2, "Game", multi = true, fsSlug = "psx")
        val data = zip("a.bin" to "1", "sub/b.bin" to "22")
        val e = lib.install(r, FolderNaming.FsSlug, true) { it.write(data) }
        assertEquals(listOf("psx", "psx/Game", "psx/Game/a.bin", "psx/Game/sub", "psx/Game/sub/b.bin"), leftovers())
        assertEquals(3, e.sizeBytes)
    }

    @Test fun differentlyNamedSingleFolderIsNotFlattened() {
        val r = rom(2, "Game", multi = true, fsSlug = "psx")
        val e = lib.install(r, FolderNaming.FsSlug, true) { it.write(zip("Other/a.bin" to "1")) }
        assertTrue(File(root, "${e.relPath}/Other/a.bin").isFile)
    }

    @Test fun zipSlipEntriesAreSkipped() {
        val r = rom(2, "Game", multi = true, fsSlug = "psx")
        lib.install(r, FolderNaming.FsSlug, true) { it.write(zip("../../evil.txt" to "x", "/abs.txt" to "x", "ok.txt" to "y")) }
        assertFalse(File(tmp.root, "evil.txt").exists())
        assertFalse(File(root, "evil.txt").exists())
        assertEquals(listOf("psx", "psx/Game", "psx/Game/ok.txt"), leftovers())
    }

    @Test fun multiFileKeptAsZip() {
        val r = rom(2, "Game", multi = true, fsSlug = "psx")
        val data = zip("a.bin" to "1")
        val e = lib.install(r, FolderNaming.FsSlug, false) { it.write(data) }
        assertEquals("psx/Game.zip", e.relPath)
        assertEquals(data.size.toLong(), e.sizeBytes)
        assertEquals(listOf("psx", "psx/Game.zip"), leftovers())
    }

    @Test fun failedDownloadLeavesNothing() {
        try {
            lib.install(rom(1, "a.sfc"), FolderNaming.FsSlug, true) { it.write(ByteArray(10)); throw IOException("network down") }
            fail()
        } catch (_: IOException) {}
        assertEquals(listOf("snes"), leftovers())
    }

    @Test fun cancelledExtractionLeavesNothing() {
        val r = rom(2, "Game", multi = true, fsSlug = "psx")
        var calls = 0
        try {
            lib.install(r, FolderNaming.FsSlug, true, isCancelled = { ++calls > 1 }) { it.write(zip("a.bin" to "1", "b.bin" to "2")) }
            fail()
        } catch (_: CancellationException) {}
        assertEquals(listOf("psx"), leftovers())
    }

    @Test fun corruptZipLeavesNothing() {
        val r = rom(2, "Game", multi = true, fsSlug = "psx")
        try {
            lib.install(r, FolderNaming.FsSlug, true) { it.write("not a zip at all".toByteArray()) }
            fail("should reject a non-zip")
        } catch (_: IOException) {}
        assertEquals(listOf("psx"), leftovers())
    }

    @Test fun uninstallRefusesUnsafePaths() {
        File(root, MANIFEST_NAME).writeText(
            """{"version":1,"roms":{"1":{"rom_id":1,"name":"a","platform_id":1,"rel_path":"../outside","size_bytes":0,"installed_at":0},
               "2":{"rom_id":2,"name":"b","platform_id":1,"rel_path":"","size_bytes":0,"installed_at":0}}}"""
        )
        lib.open()
        for (id in listOf(1L, 2L)) {
            try {
                lib.uninstall(id)
                fail("should refuse $id")
            } catch (_: IOException) {}
        }
        assertEquals(2, lib.entries.size)
    }

    @Test fun uninstallKeepsNonEmptyPlatformFolder() {
        val a = lib.install(rom(1, "a.sfc"), FolderNaming.FsSlug, true) { it.write(1) }.also(lib::record)
        lib.install(rom(2, "b.sfc"), FolderNaming.FsSlug, true) { it.write(1) }.also(lib::record)
        lib.uninstall(a.romId)
        assertTrue(File(root, "snes/b.sfc").exists())
    }

    @Test fun uninstallHandlesBackslashPaths() {
        File(root, "psx/Game").mkdirs()
        File(root, "psx/Game/a.bin").writeText("x")
        File(root, MANIFEST_NAME).writeText(
            """{"version":1,"roms":{"9":{"rom_id":9,"name":"G","platform_id":1,"rel_path":"psx\\Game","size_bytes":1,"installed_at":0}}}"""
        )
        lib.open()
        lib.uninstall(9)
        assertEquals(listOf(MANIFEST_NAME), leftovers())
    }

    @Test fun verifyForgetsMissing() {
        lib.install(rom(1, "a.sfc"), FolderNaming.FsSlug, true) { it.write(1) }.also(lib::record)
        lib.install(rom(2, "b.sfc"), FolderNaming.FsSlug, true) { it.write(1) }.also(lib::record)
        File(root, "snes/a.sfc").delete()
        assertEquals(1, lib.verify())
        assertEquals(setOf(2L), lib.entries.keys)
    }

    @Test fun adoptExisting() {
        File(root, "snes").mkdirs()
        File(root, "snes/a.sfc").writeText("12345")
        File(root, "psx").mkdirs()
        File(root, "psx/Multi.zip").writeText("zz")
        File(root, "psx/Folder").mkdirs()
        File(root, "psx/Folder/x.bin").writeText("abc")
        val roms = listOf(
            rom(1, "a.sfc"),
            rom(2, "Multi", multi = true, fsSlug = "psx"),
            rom(3, "Folder", multi = true, fsSlug = "psx"),
            rom(4, "missing.sfc"),
            rom(5, "Multi", multi = false, fsSlug = "psx"), // single-file ROMs don't match .zip
        )
        assertEquals(3, lib.adoptExisting(roms, FolderNaming.FsSlug))
        assertEquals("snes/a.sfc", lib.entries[1]!!.relPath)
        assertEquals(5, lib.entries[1]!!.sizeBytes)
        assertEquals("psx/Multi.zip", lib.entries[2]!!.relPath)
        assertEquals("psx/Folder", lib.entries[3]!!.relPath)
        assertEquals(3, lib.entries[3]!!.sizeBytes)
        // Already installed ones are skipped next time.
        assertEquals(0, lib.adoptExisting(roms, FolderNaming.FsSlug))
    }

    @Test fun reorganize() {
        lib.install(rom(1, "a.sfc"), FolderNaming.FsSlug, true) { it.write(1) }.also(lib::record)
        lib.install(rom(2, "b.sfc"), FolderNaming.FsSlug, true) { it.write(1) }.also(lib::record)
        // A blocker at the destination of b.
        File(root, "Super Nintendo").mkdirs()
        File(root, "Super Nintendo/b.sfc").writeText("other")

        val r = lib.reorganize(FolderNaming.DisplayName)
        assertEquals(ReorganizeResult(moved = 1, skipped = 1), r)
        assertEquals("Super Nintendo/a.sfc", lib.entries[1]!!.relPath)
        assertEquals("snes/b.sfc", lib.entries[2]!!.relPath)
        assertTrue(File(root, "Super Nintendo/a.sfc").exists())

        // Back again; the old folder is removed once empty.
        File(root, "Super Nintendo/b.sfc").delete()
        lib.uninstall(2)
        assertEquals(ReorganizeResult(1, 0), lib.reorganize(FolderNaming.FsSlug))
        assertFalse(File(root, "Super Nintendo").exists())
        assertTrue(File(root, "snes/a.sfc").exists())
    }

    @Test fun recoversFromLeftoverTempManifest() {
        File(root, MANIFEST_TMP_NAME).writeText("""{"version":1,"roms":{}}""")
        lib.open()
        lib.record(lib.entryFor(rom(1, "a"), "snes/a", 1))
        assertTrue(File(root, MANIFEST_NAME).exists())
        assertFalse(File(root, MANIFEST_TMP_NAME).exists())
    }

    @Test(expected = ManifestCorruptException::class)
    fun corruptManifestIsNotOverwritten() {
        File(root, MANIFEST_NAME).writeText("garbage")
        try {
            lib.open()
        } finally {
            assertEquals("garbage", File(root, MANIFEST_NAME).readText())
        }
    }

    @Test fun safeEntryPath() {
        assertEquals("a/b", Library.safeEntryPath("a\\b"))
        assertEquals("a/b", Library.safeEntryPath("./a//b"))
        assertNull(Library.safeEntryPath("../a"))
        assertNull(Library.safeEntryPath("a/../../b"))
        assertNull(Library.safeEntryPath("/etc/passwd"))
        assertNull(Library.safeEntryPath("C:\\x"))
    }
}
