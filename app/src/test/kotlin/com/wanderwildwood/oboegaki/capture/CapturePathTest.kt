package com.wanderwildwood.oboegaki.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CapturePathTest {

    @Test
    fun acceptsNotesInFolders() {
        assertEquals("Dreams/2026-10-05 0712.md", capturePath("Dreams/2026-10-05 0712.md"))
        assertEquals("a.md", capturePath("a.md"))
        assertEquals("Logs/2026/October/the river.md", capturePath("Logs/2026/October/the river.md"))
    }

    @Test
    fun refusesWhatIsNotANoteHere() {
        for (bad in listOf(
            null, "", ".md", "/Dreams/a.md", "Dreams/a.txt", "Dreams/a.md/", "Dreams/a",
            "../a.md", "Dreams/../../a.md", "Dreams/./a.md", "Dreams//a.md", "./a.md",
            ".pinned.md", "Dreams/.hidden.md", ".obsidian/a.md", "Dreams\\a.md", "C:/a.md",
            "Dreams/a\n.md", "Dreams/a?.md", " Dreams/a.md", "Dreams /a.md", "Dreams/.md",
        )) {
            assertNull(bad, capturePath(bad))
        }
    }
}
