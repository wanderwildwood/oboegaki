package com.wanderwildwood.oboegaki.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrashTest {

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun theKeepingTimeIsSevenDaysAndIsOneConstant() {
        assertEquals(7, TRASH_DAYS)
        assertEquals(1_000_000L - 7 * day, trashCutoff(1_000_000L))
        assertEquals(500L + 7 * day, trashedUntil(500L))
    }

    @Test
    fun aNoteIsOldEnoughOnlyPastSevenDays() {
        val now = 100 * day
        assertFalse(trashExpired(now, now))
        assertFalse(trashExpired(now - 6 * day, now))
        assertFalse(trashExpired(now - 7 * day, now))
        assertTrue(trashExpired(now - 7 * day - 1, now))
        assertTrue(trashExpired(0, now))
    }

    @Test
    fun expiredInKeepsOnlyTheOldOnes() {
        val now = 100 * day
        val fresh = Trashed("a", "fresh", "fresh.md", now - day)
        val stale = Trashed("b", "stale", "Garden/stale.md", now - 8 * day)
        assertEquals(listOf(stale), expiredIn(listOf(fresh, stale), now))
    }

    @Test
    fun trashPathsAreRecognisedAndMapped() {
        assertTrue(isTrashPath(".trash"))
        assertTrue(isTrashPath(".trash/123/kiln.md"))
        assertFalse(isTrashPath("trash/kiln.md"))
        assertFalse(isTrashPath(".trashed/kiln.md"))
        assertFalse(isTrashPath("Garden/.trash/kiln.md"))
        assertTrue(isHiddenPath("Garden/.trash/kiln.md"))
        assertFalse(isHiddenPath("Garden/kiln.md"))
        assertEquals(".trash/42/kiln.md", trashedPath("42", "Garden/deep/kiln.md"))
        assertEquals(".trash/42/kiln.md", trashedPath("42", "kiln.md"))
    }

    @Test
    fun aSlotIsTheMomentAndMovesOnWhileTaken() {
        assertEquals("100", trashSlot(100, emptySet()))
        assertEquals("101", trashSlot(100, setOf("100")))
        assertEquals("102", trashSlot(100, setOf("100", "101", "7")))
    }

    @Test
    fun theMetaFileRoundTripsAndRefusesWhatCouldClimbOut() {
        val meta = TrashMeta("Garden/kiln.md", 1761832800000L)
        assertEquals(meta, parseTrashMeta(trashMetaText(meta)))
        assertEquals(TrashMeta("a b (1).md", 5), parseTrashMeta("from: a b (1).md\nat: 5\n"))
        assertNull(parseTrashMeta(null))
        assertNull(parseTrashMeta(""))
        assertNull(parseTrashMeta("from: kiln.md\n"))
        assertNull(parseTrashMeta("at: 5\n"))
        assertNull(parseTrashMeta("from: ../../etc/x.md\nat: 5\n"))
        assertNull(parseTrashMeta("from: /abs.md\nat: 5\n"))
        assertNull(parseTrashMeta("from: kiln.md\nat: soon\n"))
    }

    @Test
    fun aTrashedNoteShowsItsTitleOriginAndDate() {
        val files = listOf(Entry(".meta", false, 9), Entry("kiln.m4a", false, 9), Entry("kiln.md", false, 9))
        val item = trashEntry("77", files, "from: Garden/kiln.md\nat: 1234\n")
        assertEquals(Trashed("77", "kiln", "Garden/kiln.md", 1234), item)
        assertEquals("Garden", item!!.folder)
        assertEquals(".trash/77/kiln.md", item.path)
    }

    @Test
    fun aTrashFolderWithNoRecordStillShowsAndComesBackToTheTop() {
        val files = listOf(Entry("old note.md", false, 31))
        assertEquals(Trashed("88", "old note", "old note.md", 88), trashEntry("88", files, null))
        assertEquals(Trashed("abc", "old note", "old note.md", 31), trashEntry("abc", files, "junk"))
        // The record names a file that is not there: the note that is there is shown instead.
        assertEquals(Trashed("5", "other", "other.md", 5), trashEntry("5", listOf(Entry("other.md", false, 1)), "from: gone.md\nat: 5\n"))
    }

    @Test
    fun aFolderWithNoNoteIsNotATrashedNote() {
        assertNull(trashEntry("1", listOf(Entry(".meta", false, 1), Entry("kiln.m4a", false, 1)), "from: kiln.md\nat: 1\n"))
        assertNull(trashEntry("1", emptyList(), null))
    }

    @Test
    fun restoreGoesBackToTheFolderItCameFrom() {
        assertEquals("Garden/kiln.md", restoreTarget("Garden/kiln.md", true) { false })
        assertEquals("kiln.md", restoreTarget("kiln.md", true) { false })
        assertEquals("Archive/Garden/kiln.md", restoreTarget("Archive/Garden/kiln.md", true) { false })
    }

    @Test
    fun restoreFallsBackToTheTopWhenTheFolderIsGone() {
        assertEquals("kiln.md", restoreTarget("Garden/deep/kiln.md", false) { false })
    }

    @Test
    fun restoreNeverWritesOverAnExistingNote() {
        val taken = setOf("Garden/kiln.md", "Garden/kiln (1).md")
        assertEquals("Garden/kiln (2).md", restoreTarget("Garden/kiln.md", true) { it in taken })
        assertEquals("kiln (1).md", restoreTarget("Garden/kiln.md", false) { it == "kiln.md" })
        assertEquals("a (1).txt", restoreTarget("a.txt", true) { it == "a.txt" })
        assertEquals("noext (1)", restoreTarget("noext", true) { it == "noext" })
    }

    @Test
    fun aRequestForAnOldVersionIsStale() {
        assertFalse(trashIsStale(null, "abc"))
        assertFalse(trashIsStale("", "abc"))
        assertFalse(trashIsStale("abc", "abc"))
        assertTrue(trashIsStale("abd", "abc"))
    }

    // ----------------------------------------------------------- the folder on disk

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun noListingSeesTheTrashButItsOwnEntriesDo() {
        val shelf = FileShelf(tmp.newFolder("notes"))
        shelf.write("Garden/kiln.md", "fire\n")
        shelf.write("keep.md", "stays\n")
        shelf.write("Garden/.trash-lookalike.md", "hidden file\n")
        // What deleting does, step by step.
        shelf.write(".trash/42/.meta", trashMetaText(TrashMeta("Garden/kiln.md", 42)))
        shelf.writeBytes(".trash/42/kiln.m4a", byteArrayOf(1, 2, 3), "audio/mp4")
        shelf.move("Garden/kiln.md", ".trash/42/kiln.md")

        assertEquals(listOf("keep.md"), shelf.list().map { it.path })
        assertTrue(shelf.files().none { isTrashPath(it) })
        assertFalse(shelf.exists("Garden/kiln.md"))

        val slots = shelf.entries(TRASH_FOLDER)!!
        assertEquals(listOf("42"), slots.map { it.name })
        assertTrue(slots[0].isFolder)
        val item = trashEntry("42", shelf.entries(".trash/42")!!, shelf.read(".trash/42/.meta"))
        assertEquals(Trashed("42", "kiln", "Garden/kiln.md", 42), item)
        assertEquals("fire\n", shelf.read(item!!.path))

        // Restore: the folder is still there, so it goes back to it.
        val target = restoreTarget(item.from, shelf.entries(item.folder) != null, shelf::exists)
        assertEquals("Garden/kiln.md", target)
        shelf.move(item.path, target)
        assertEquals(listOf("Garden/kiln.md", "keep.md"), shelf.list().map { it.path }.sorted())
    }

    @Test
    fun anEmptyTrashFolderAndAMissingOneAreBothNothing() {
        val shelf = FileShelf(tmp.newFolder("notes2"))
        assertNull(shelf.entries(TRASH_FOLDER))
        shelf.write(".trash/1/a.md", "x")
        shelf.delete(".trash/1/a.md")
        shelf.delete(".trash/1")
        assertNotNull(shelf.entries(TRASH_FOLDER))
        assertTrue(shelf.entries(TRASH_FOLDER)!!.isEmpty())
    }
}
