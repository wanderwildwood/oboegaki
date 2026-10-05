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
        assertEquals(listOf("ash", "kiln", "Voice 0214"), titles(arrange(notes, emptySet(), Showing.ALL, Order.CHANGED, "WOOD")))
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
}
