package com.playingkarrde.rommexplorer.data.library

import org.junit.Assert.assertEquals
import org.junit.Test

class NamingTest {
    @Test fun folderNamesMatchDesktop() {
        // Same cases as the desktop app's library.rs tests.
        assertEquals("snes", platformFolder(FolderNaming.FsSlug, "snes", "snes", "Super Nintendo"))
        assertEquals("Sega Mega Drive_Genesis", platformFolder(FolderNaming.DisplayName, "md", "genesis", "Sega Mega Drive/Genesis"))
        assertEquals("unknown", platformFolder(FolderNaming.Slug, "", "", ""))
    }

    @Test fun fallbackOrder() {
        assertEquals("genesis", platformFolder(FolderNaming.FsSlug, " ", "genesis", "Mega Drive"))
        assertEquals("md", platformFolder(FolderNaming.Slug, "md", "", "Mega Drive"))
        assertEquals("Mega Drive", platformFolder(FolderNaming.Slug, "", "", "Mega Drive"))
        assertEquals("md", platformFolder(FolderNaming.DisplayName, "md", "genesis", ""))
        assertEquals("genesis", platformFolder(FolderNaming.DisplayName, "", "genesis", ""))
    }

    @Test fun sanitize() {
        assertEquals("a_b_c_d_e_f_g_h_i_j", sanitizeComponent("a<b>c:d\"e/f\\g|h?i*j"))
        assertEquals("tab_here", sanitizeComponent("tab\there"))
        assertEquals("name", sanitizeComponent("  name. . "))
        assertEquals("_", sanitizeComponent(""))
        assertEquals("_", sanitizeComponent("   "))
        assertEquals("_", sanitizeComponent(".."))
        assertEquals("_", sanitizeComponent("..."))
        assertEquals("Final Fantasy VII (USA)", sanitizeComponent("Final Fantasy VII (USA)"))
        assertEquals(".hidden", sanitizeComponent(".hidden"))
    }

    @Test fun parseNaming() {
        assertEquals(FolderNaming.DisplayName, FolderNaming.parse("DisplayName"))
        assertEquals(FolderNaming.FsSlug, FolderNaming.parse("bogus"))
        assertEquals(FolderNaming.FsSlug, FolderNaming.parse(null))
    }
}
