package com.wanderwildwood.oboegaki.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MoveTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun aFolderIsTidiedAndCheckedBeforeANoteMoves() {
        assertEquals("Garden" to null, moveTarget("kiln.md", " /Garden/ "))
        assertEquals("" to null, moveTarget("Garden/kiln.md", ""))
        assertEquals("Garden/Beds" to null, moveTarget("Garden/kiln.md", "Garden/Beds"))
        assertEquals(null to MoveRefusal.SAME_FOLDER, moveTarget("Garden/kiln.md", "Garden/"))
        assertEquals(null to MoveRefusal.SAME_FOLDER, moveTarget("kiln.md", ""))
        assertEquals(null to MoveRefusal.BAD_FOLDER, moveTarget("kiln.md", "../Elsewhere"))
        assertEquals(null to MoveRefusal.BAD_FOLDER, moveTarget("kiln.md", ".trash"))
        assertEquals(null to MoveRefusal.BAD_FOLDER, moveTarget("kiln.md", "Archive"))
        assertEquals(null to MoveRefusal.ARCHIVED, moveTarget("Archive/Garden/kiln.md", "Garden"))
    }

    @Test
    fun anAttachmentOnlyThisNoteLinksGoesWithItOneSharedIsCopied() {
        val there = setOf("Garden/kiln.m4a", "Garden/plan.jpg", "Garden/scan.pdf", "Shed/scan.pdf")
        val plan = attachmentPlan(
            text = "![[kiln.m4a]]\n![[plan.jpg]]\n![[scan.pdf]]\n![[missing.jpg]]\n![[kiln.m4a]]",
            fromFolder = "Garden",
            toFolder = "Shed",
            exists = { it in there },
            linkedByOthers = { it == "Garden/plan.jpg" },
        )
        assertEquals(listOf("Garden/kiln.m4a" to "Shed/kiln.m4a"), plan.move)
        assertEquals(listOf("Garden/plan.jpg" to "Shed/plan.jpg"), plan.copy)
    }

    @Test
    fun aNoteMovesOnTheShelfWithItsRecordingAndKeepsASharedPictureForTheOther() {
        val shelf = FileShelf(tmp.newFolder("notes"))
        shelf.write("Garden/kiln.md", "Firing\n![[kiln.m4a]]\n![[plan.jpg]]\n")
        shelf.write("Garden/beds.md", "![[plan.jpg]]\n")
        shelf.writeBytes("Garden/kiln.m4a", byteArrayOf(1, 2, 3), "audio/mp4")
        shelf.writeBytes("Garden/plan.jpg", byteArrayOf(4, 5), "image/jpeg")

        val moved = moveOnShelf(shelf, "Garden/kiln.md", "Shed/Pottery", shelf.list())

        assertEquals("Shed/Pottery/kiln.md", moved)
        assertEquals("Firing\n![[kiln.m4a]]\n![[plan.jpg]]\n", shelf.read("Shed/Pottery/kiln.md"))
        assertFalse(shelf.exists("Garden/kiln.md"))
        assertTrue(shelf.exists("Shed/Pottery/kiln.m4a"))
        assertFalse(shelf.exists("Garden/kiln.m4a"))
        // The other note still finds its picture, and the moved one has its own copy.
        assertTrue(shelf.exists("Garden/plan.jpg"))
        assertTrue(shelf.readBytes("Shed/Pottery/plan.jpg")!!.contentEquals(byteArrayOf(4, 5)))
    }

    @Test
    fun aNameTakenInTheNewFolderIsNeverWrittenOver() {
        val shelf = FileShelf(tmp.newFolder("notes2"))
        shelf.write("kiln.md", "mine")
        shelf.write("Shed/kiln.md", "already there")
        assertEquals("Shed/kiln 2.md", moveOnShelf(shelf, "kiln.md", "Shed", shelf.list()))
        assertEquals("already there", shelf.read("Shed/kiln.md"))
        assertEquals("mine", shelf.read("Shed/kiln 2.md"))
    }

    @Test
    fun aNoteThatIsGoneIsNotMoved() {
        val shelf = FileShelf(tmp.newFolder("notes3"))
        assertNull(moveOnShelf(shelf, "nowhere.md", "Shed", emptyList()))
    }
}
