package com.wanderwildwood.oboegaki.sync

import com.wanderwildwood.oboegaki.notes.FileShelf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A server's listing never names a file outside the notes folder. */
class SafePathTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun ordinaryPathsAreKept() {
        assertTrue(isSafeRelative("Shopping.md"))
        assertTrue(isSafeRelative("Dreams/2026-10-05 0712.md"))
        assertTrue(isSafeRelative("Dreams/"))
        assertTrue(isSafeRelative("a..b.md"))
    }

    @Test fun climbingOutIsRefused() {
        assertFalse(isSafeRelative("../tasks/x.md"))
        assertFalse(isSafeRelative("Notes/../../files/x.md"))
        assertFalse(isSafeRelative("./x.md"))
        assertFalse(isSafeRelative("a//b.md"))
        assertFalse(isSafeRelative("/abs.md"))
        assertFalse(isSafeRelative("a\\..\\b.md"))
        assertFalse(isSafeRelative("a\u0000.md"))
        assertFalse(isSafeRelative(""))
    }

    @Test fun insideRefusesWhatLandsOutsideTheRoot() {
        val root = tmp.newFolder("notes")
        assertNotNull(inside(root, "Dreams/x.md"))
        assertNull(inside(root, "../x.md"))
        assertNull(inside(root, "Dreams/../../x.md"))
        assertNull(inside(root, ""))
    }

    @Test fun theShelfWritesNothingOutsideItsFolder() {
        val root = tmp.newFolder("mirror")
        val shelf = FileShelf(root)
        shelf.write("../escaped.md", "no")
        shelf.writeBytes("../escaped.png", byteArrayOf(1), "image/png")
        assertFalse(File(tmp.root, "escaped.md").exists())
        assertFalse(File(tmp.root, "escaped.png").exists())
        shelf.write("kept.md", "yes")
        assertEquals("yes", shelf.read("kept.md"))
    }
}
