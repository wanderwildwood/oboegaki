package com.wanderwildwood.oboegaki.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewTest {

    private val notes = listOf(
        Note("Groceries.md", "Groceries", "", modified = 300, text = "- [ ] milk\n- [ ] bread"),
        Note("ideas/kiln.md", "kiln", "", modified = 100, text = "wood fired, two chambers"),
        Note("ideas/glaze/ash.md", "ash", "", modified = 200, text = "wood ash glaze"),
        Note("reading-highlights/Braiding Sweetgrass.md", "Braiding Sweetgrass", "", modified = 50, text = "gift"),
        Note("Voice 0214.md", "Voice 0214", "", modified = 40, text = "![[Voice 0214.m4a]]\n\nkiln wood"),
        Note("Scan 0236.md", "Scan 0236", "", modified = 30, text = "![[Scan 0236.pdf]]\n"),
        Note("Archive/ideas/old kiln.md", "old kiln", "", modified = 500, text = "wood fired once"),
    )

    private fun titles(list: List<Note>) = list.map { it.title }

    @Test
    fun newestFirstAcrossEveryFolder() {
        assertEquals(
            listOf("Groceries", "ash", "kiln", "Braiding Sweetgrass", "Voice 0214", "Scan 0236"),
            titles(arrange(notes, emptySet(), Showing.ALL, Order.CHANGED, "")),
        )
    }

    @Test
    fun byTitleIgnoresCase() {
        assertEquals(
            listOf("ash", "Braiding Sweetgrass", "Groceries", "kiln", "Scan 0236", "Voice 0214"),
            titles(arrange(notes, emptySet(), Showing.ALL, Order.TITLE, "")),
        )
    }

    @Test
    fun aFolderIncludesTheFoldersInsideIt() {
        assertEquals(listOf("ash", "kiln"), titles(arrange(notes, emptySet(), Showing.folder("ideas"), Order.CHANGED, "")))
        assertEquals(listOf("ash"), titles(arrange(notes, emptySet(), Showing.folder("ideas/glaze"), Order.CHANGED, "")))
    }

    @Test
    fun sharedAndLists() {
        assertEquals(listOf("kiln"), titles(arrange(notes, setOf("ideas/kiln.md"), Showing.SHARED, Order.CHANGED, "")))
        assertEquals(listOf("Groceries"), titles(arrange(notes, emptySet(), Showing.LISTS, Order.CHANGED, "")))
    }

    @Test
    fun searchNeedsEveryWordInTitleOrText() {
        // The archived "old kiln" too: a search is how something put away is found again.
        assertEquals(listOf("old kiln", "ash", "kiln", "Voice 0214"), titles(arrange(notes, emptySet(), Showing.ALL, Order.CHANGED, "WOOD")))
        assertEquals(listOf("ash"), titles(arrange(notes, emptySet(), Showing.ALL, Order.CHANGED, "glaze wood")))
        assertEquals(listOf("Groceries"), titles(arrange(notes, emptySet(), Showing.ALL, Order.CHANGED, "groc")))
        assertEquals(emptyList<String>(), titles(arrange(notes, emptySet(), Showing.ALL, Order.CHANGED, "wood milk")))
    }

    @Test
    fun foldersListsNestedOnesToo() {
        assertEquals(listOf("ideas", "ideas/glaze", "reading-highlights"), folders(notes))
    }

    @Test
    fun voiceAndScans() {
        assertEquals(listOf("Voice 0214"), titles(arrange(notes, emptySet(), Showing.VOICE, Order.CHANGED, "")))
        assertEquals(listOf("Scan 0236"), titles(arrange(notes, emptySet(), Showing.SCANS, Order.CHANGED, "")))
    }

    @Test
    fun archivedNotesStayOutOfTheWayButSearchFindsThem() {
        val all = titles(arrange(notes, emptySet(), Showing.ALL, Order.CHANGED, ""))
        assertEquals(false, "old kiln" in all)
        assertEquals(listOf("old kiln"), titles(arrange(notes, emptySet(), Showing.ARCHIVE, Order.CHANGED, "")))
        assertEquals(true, "old kiln" in titles(arrange(notes, emptySet(), Showing.ALL, Order.CHANGED, "fired")))
        // A folder view does not reach into the archive, even under the same name.
        assertEquals(listOf("ash", "kiln"), titles(arrange(notes, emptySet(), Showing.folder("ideas"), Order.CHANGED, "")))
        assertEquals(false, folders(notes).any { it.startsWith("Archive") })
    }

    @Test
    fun oldestFirst() {
        assertEquals(
            listOf("Scan 0236", "Voice 0214", "Braiding Sweetgrass", "kiln", "ash", "Groceries"),
            titles(arrange(notes, emptySet(), Showing.ALL, Order.OLDEST, "")),
        )
    }

    @Test
    fun pinnedNotesComeFirstInTheirOwnOrder() {
        val pinned = setOf("Scan 0236.md", "ideas/kiln.md")
        assertEquals(
            listOf("kiln", "Scan 0236", "Groceries", "ash", "Braiding Sweetgrass", "Voice 0214"),
            titles(arrange(notes, emptySet(), Showing.ALL, Order.CHANGED, "", pinned)),
        )
        // By title, ash would lead; pinned, kiln does.
        assertEquals(
            listOf("kiln", "ash"),
            titles(arrange(notes, emptySet(), Showing.folder("ideas"), Order.TITLE, "", setOf("ideas/kiln.md"))),
        )
    }
}
