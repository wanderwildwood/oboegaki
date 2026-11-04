package com.wanderwildwood.oboegaki.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListEnterTest {

    /** Press Enter with the cursor at [cursor] in [text], as the editor reports it. */
    private fun enter(text: String, cursor: Int = text.length): Pair<String, Int>? =
        continueList(text, text.substring(0, cursor) + "\n" + text.substring(cursor), cursor + 1)

    /** Type [keys] into an empty note the way the editor would, Enter included, with the list rule on. */
    private fun type(keys: String): Pair<String, Int> {
        var text = ""
        var cursor = 0
        for (key in keys) {
            val before = text
            text = text.substring(0, cursor) + key + text.substring(cursor)
            cursor += 1
            if (key == '\n') continueList(before, text, cursor)?.let { (t, c) -> text = t; cursor = c }
        }
        return text to cursor
    }

    @Test
    fun enterAfterAnItemStartsTheNext() {
        assertEquals("- [ ] milk\n- [ ] " to 17, enter("- [ ] milk"))
    }

    @Test
    fun enterInTheMiddleOfAListOpensAnItemThere() {
        val text = "# Groceries\n- [ ] milk\n- [ ] bread\n"
        val (out, cursor) = enter(text, text.indexOf("milk") + 4)!!
        assertEquals("# Groceries\n- [ ] milk\n- [ ] \n- [ ] bread\n", out)
        assertEquals(out.indexOf("- [ ] \n") + 6, cursor)
    }

    @Test
    fun aTickedItemIsFollowedByAnUntickedOne() {
        assertEquals("- [x] milk\n- [ ] ", enter("- [x] milk")!!.first)
    }

    @Test
    fun theNewItemKeepsIndentAndBullet() {
        assertEquals("  * [ ] oat\n  * [ ] ", enter("  * [ ] oat")!!.first)
    }

    @Test
    fun enterInsideTheWordsSplitsThemIntoTwoItems() {
        val (out, cursor) = enter("- [ ] milk bread", 10)!!
        assertEquals("- [ ] milk\n- [ ] bread", out)
        assertEquals(17, cursor)
    }

    @Test
    fun enterOnAnEmptyItemEndsTheList() {
        assertEquals("- [ ] milk\n" to 11, enter("- [ ] milk\n- [ ] "))
        // Without the space the box is still an empty item.
        assertEquals("- [ ] milk\n" to 11, enter("- [ ] milk\n- [ ]"))
    }

    @Test
    fun endingTheListInTheMiddleLeavesAPlainLine() {
        val text = "- [ ] milk\n- [ ] \n- [ ] bread"
        assertEquals("- [ ] milk\n\n- [ ] bread" to 11, enter(text, 17))
    }

    @Test
    fun threeItemsThenOutOfTheList() {
        val (text, cursor) = type("- [ ] milk\nbread\neggs\n\nThat is all")
        assertEquals("- [ ] milk\n- [ ] bread\n- [ ] eggs\nThat is all", text)
        assertEquals(text.length, cursor)
    }

    @Test
    fun plainTextIsLeftAlone() {
        assertNull(enter("just words"))
        assertNull(enter("- a bullet"))
        assertNull(enter(""))
    }

    @Test
    fun enterBeforeTheBoxIsAnOrdinaryNewLine() {
        assertNull(enter("- [ ] milk", 0))
        assertNull(enter("- [ ] milk", 3))
    }

    @Test
    fun anythingButOneNewLineIsLeftAlone() {
        assertNull(continueList("- [ ] milk", "- [ ] milks", 11))
        assertNull(continueList("- [ ] milk", "- [ ] milk\n\n", 12))
        // The cursor is not just after the new line.
        assertNull(continueList("- [ ] milk", "- [ ] mil\nk", 9))
    }
}
