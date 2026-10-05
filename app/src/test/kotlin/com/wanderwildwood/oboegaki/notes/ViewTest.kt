package com.wanderwildwood.oboegaki.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewTest {

    private val notes = listOf(
        Note("Groceries.md", "Groceries", "", modified = 300, text = "- [ ] milk\n- [ ] bread"),
        Note("ideas/kiln.md", "kiln", "", modified = 100, text = "wood fired, two chambers"),
        Note("ideas/glaze/ash.md", "ash", "", modified = 200, text = "wood ash glaze"),
        Note("reading-highlights/Braiding Sweetgrass.md", "Braiding Sweetgrass", "", modified = 50, text = "gift"),
    )

    private fun titles(list: List<Note>) = list.map { it.title }

    @Test
    fun newestFirstAcrossEveryFolder() {
        assertEquals(
            listOf("Groceries", "ash", "kiln", "Braiding Sweetgrass"),
            titles(arrange(notes, emptySet(), Showing.ALL, Order.CHANGED, "")),
        )
    }

    @Test
    fun byTitleIgnoresCase() {
        assertEquals(
            listOf("ash", "Braiding Sweetgrass", "Groceries", "kiln"),
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
        assertEquals(listOf("ash", "kiln"), titles(arrange(notes, emptySet(), Showing.ALL, Order.CHANGED, "WOOD")))
        assertEquals(listOf("ash"), titles(arrange(notes, emptySet(), Showing.ALL, Order.CHANGED, "glaze wood")))
        assertEquals(listOf("Groceries"), titles(arrange(notes, emptySet(), Showing.ALL, Order.CHANGED, "groc")))
        assertEquals(emptyList<String>(), titles(arrange(notes, emptySet(), Showing.ALL, Order.CHANGED, "wood milk")))
    }

    @Test
    fun foldersListsNestedOnesToo() {
        assertEquals(listOf("ideas", "ideas/glaze", "reading-highlights"), folders(notes))
    }
}
