package com.wanderwildwood.oboegaki.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CopyAsideTest {

    @get:Rule val tmp = TemporaryFolder()

    private val a = "https://cloud.example.org\nada\nNotes"
    private val b = "https://cloud.example.org\nbea\nNotes"

    private fun dir() = File(tmp.root, "nextcloud")
    private fun aside() = File(tmp.root, "aside")
    private fun note(text: String) {
        File(dir(), "notes").mkdirs()
        File(dir(), "notes/a.md").writeText(text)
    }
    private fun noteNow(): String? = File(dir(), "notes/a.md").takeIf { it.isFile }?.readText()

    @Test fun sameAccountOrNoneKnownLeavesTheCopy() {
        note("ada's")
        assertFalse(setCopyAside(dir(), aside(), a, a))
        assertFalse(setCopyAside(dir(), aside(), null, b))
        assertEquals("ada's", noteNow())
    }

    @Test fun anotherAccountNeverSeesTheCopy() {
        note("ada's, not sent")
        assertTrue(setCopyAside(dir(), aside(), a, b))
        assertNull(noteNow())
        assertFalse(dir().exists())
    }

    @Test fun backToTheFirstAccountItsCopyComesBack() {
        note("ada's, not sent")
        setCopyAside(dir(), aside(), a, b)
        note("bea's")
        assertTrue(setCopyAside(dir(), aside(), b, a))
        assertEquals("ada's, not sent", noteNow())
        assertTrue(setCopyAside(dir(), aside(), a, b))
        assertEquals("bea's", noteNow())
    }

    @Test fun aCopyAlreadyAsideIsNeverWrittenOver() {
        note("first")
        setCopyAside(dir(), aside(), a, b)
        // As if it could not be brought back: a second copy of ada's is set aside beside it.
        File(aside(), asideName(a)).renameTo(File(tmp.root, "held"))
        File(tmp.root, "held").copyRecursively(File(aside(), asideName(a)))
        note("second")
        setCopyAside(dir(), aside(), a, b)
        assertEquals(2, aside().listFiles()!!.size)
        assertEquals("first", File(aside(), asideName(a) + "/notes/a.md").readText())
    }

    @Test fun asideNamesShowNothingOfTheAccount() {
        val name = asideName(a)
        assertFalse(name.contains("ada"))
        assertEquals(name, asideName(a))
        assertFalse(name == asideName(b))
    }
}
