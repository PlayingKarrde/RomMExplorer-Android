package com.playingkarrde.rommexplorer.data.library

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestTest {
    /** As written by the Windows desktop build (serde_json pretty, PathBuf with backslashes). */
    private val windowsDesktop = """
        {
          "version": 1,
          "roms": {
            "7": {
              "rom_id": 7,
              "name": "Final Fantasy VII",
              "platform_id": 3,
              "platform_slug": "psx",
              "platform_fs_slug": "psx",
              "platform_name": "PlayStation",
              "rel_path": "psx\\Final Fantasy VII (USA)",
              "size_bytes": 2147483648000,
              "installed_at": 1730937600
            },
            "1234": {
              "rom_id": 1234,
              "name": "Super Mario World",
              "platform_id": 7,
              "rel_path": "snes/Super Mario World (USA).sfc",
              "size_bytes": 524288,
              "installed_at": 1730937600
            }
          }
        }
    """.trimIndent()

    @Test fun readsDesktopManifest() {
        val m = ManifestCodec.decode(windowsDesktop)
        assertEquals(1, m.version)
        assertEquals(2, m.roms.size)
        val ff = m.roms.getValue(7)
        assertEquals("psx/Final Fantasy VII (USA)", ff.relPath)
        assertEquals(2147483648000L, ff.sizeBytes)
        // Missing platform fields in older manifests default to "".
        val smw = m.roms.getValue(1234)
        assertEquals("", smw.platformSlug)
        assertEquals("", smw.platformFsSlug)
        assertEquals("", smw.platformName)
    }

    @Test fun writesForwardSlashesAndSortsNumerically() {
        val text = ManifestCodec.encode(ManifestCodec.decode(windowsDesktop))
        assertTrue(text.contains("\"rel_path\": \"psx/Final Fantasy VII (USA)\""))
        assertTrue(text.indexOf("\"7\"") < text.indexOf("\"1234\""))
        // Re-reading what we wrote gives the same entries.
        assertEquals(ManifestCodec.decode(windowsDesktop).roms, ManifestCodec.decode(text).roms)
    }

    @Test fun keepsUnknownFields() {
        val src = """
            {"version": 1, "future_top": {"a": 1},
             "roms": {"5": {"rom_id": 5, "name": "X", "platform_id": 1, "rel_path": "a/b", "size_bytes": 1, "installed_at": 2, "future_field": "keep me"},
                      "bad": "not an object"}}
        """.trimIndent()
        val out = Json.parseToJsonElement(ManifestCodec.encode(ManifestCodec.decode(src))).jsonObject
        assertEquals(JsonObject(mapOf("a" to JsonPrimitive(1))), out["future_top"])
        assertEquals("keep me", out["roms"]!!.jsonObject["5"]!!.jsonObject["future_field"]!!.jsonPrimitive.content)
        assertEquals("not an object", out["roms"]!!.jsonObject["bad"]!!.jsonPrimitive.content)
    }

    @Test fun nullPlatformFieldsBecomeEmpty() {
        val m = ManifestCodec.decode("""{"version":1,"roms":{"1":{"rom_id":1,"name":"A","platform_id":2,"platform_slug":null,"rel_path":"x/y","size_bytes":0,"installed_at":0}}}""")
        assertEquals("", m.roms.getValue(1).platformSlug)
    }

    @Test(expected = ManifestCorruptException::class)
    fun corruptManifestThrows() {
        ManifestCodec.decode("{ this is not json")
    }

    @Test(expected = ManifestCorruptException::class)
    fun wrongShapeThrows() {
        ManifestCodec.decode("""{"version":1,"roms":[]}""")
    }

    @Test fun emptyManifest() {
        val text = ManifestCodec.encode(Manifest())
        val m = ManifestCodec.decode(text)
        assertEquals(1, m.version)
        assertTrue(m.roms.isEmpty())
    }
}
