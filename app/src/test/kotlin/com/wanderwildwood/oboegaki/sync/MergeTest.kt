package com.wanderwildwood.oboegaki.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MergeTest {

    private val list = "# Groceries\n- [ ] milk\n- [ ] bread\n- [ ] apples\n"

    @Test
    fun oneSideChangedTakesThatSide() {
        val ours = list.replace("- [ ] milk", "- [x] milk")
        assertEquals(ours, merge(list, ours, list))
        assertEquals(ours, merge(list, list, ours))
    }

    @Test
    fun tickingOneLineWhileAnotherIsAddedKeepsBoth() {
        val ours = list.replace("- [ ] milk", "- [x] milk")
        val theirs = list + "- [ ] eggs\n"
        assertEquals(
            "# Groceries\n- [x] milk\n- [ ] bread\n- [ ] apples\n- [ ] eggs\n",
            merge(list, ours, theirs),
        )
    }

    @Test
    fun twoAdditionsAtTheEndAreBothKeptOursFirst() {
        val ours = list + "- [ ] eggs\n"
        val theirs = list + "- [ ] butter\n"
        assertEquals(list + "- [ ] eggs\n- [ ] butter\n", merge(list, ours, theirs))
    }

    @Test
    fun theSameLineAddedOnBothSidesIsKeptOnce() {
        val both = list + "- [ ] eggs\n"
        assertEquals(both, merge(list, both, both))
        val ours = list + "- [ ] eggs\n"
        val theirs = list.replace("- [ ] bread", "- [x] bread") + "- [ ] eggs\n"
        assertEquals(
            "# Groceries\n- [ ] milk\n- [x] bread\n- [ ] apples\n- [ ] eggs\n",
            merge(list, ours, theirs),
        )
    }

    @Test
    fun changesToNeighbouringLinesAreBothKept() {
        val ours = list.replace("- [ ] milk", "- [x] milk")
        val theirs = list.replace("- [ ] bread", "- [x] bread")
        assertEquals(
            "# Groceries\n- [x] milk\n- [x] bread\n- [ ] apples\n",
            merge(list, ours, theirs),
        )
    }

    @Test
    fun theSameChangeOnBothSidesIsNotAConflict() {
        val ours = list.replace("- [ ] apples", "- [ ] pears")
        val theirs = list.replace("- [ ] apples", "- [ ] pears").replace("- [ ] milk", "- [x] milk")
        assertEquals(
            "# Groceries\n- [x] milk\n- [ ] bread\n- [ ] pears\n",
            merge(list, ours, theirs),
        )
    }

    @Test
    fun oneLineChangedTwoWaysCannotBeMerged() {
        val ours = list.replace("- [ ] apples", "- [ ] pears")
        val theirs = list.replace("- [ ] apples", "- [ ] plums")
        assertNull(merge(list, ours, theirs))
    }

    @Test
    fun editsToNearbyLinesAreAllKept() {
        val ours = list.replace("- [ ] bread\n", "- [ ] rye bread\n")
        val theirs = list.replace("- [ ] bread\n", "- [ ] bread\n- [ ] jam\n").replace("- [ ] milk", "- [ ] oat milk")
        // Theirs changed milk and added after bread; ours changed bread. Milk and bread are
        // separate lines, and jam goes after bread, so all three survive.
        assertEquals(
            "# Groceries\n- [ ] oat milk\n- [ ] rye bread\n- [ ] jam\n- [ ] apples\n",
            merge(list, ours, theirs),
        )
    }

    @Test
    fun aLineDeletedOnOneSideAndTickedOnTheOtherCannotBeMerged() {
        val ours = list.replace("- [ ] bread\n", "")
        val theirs = list.replace("- [ ] bread", "- [x] bread")
        assertNull(merge(list, ours, theirs))
    }

    @Test
    fun deletionsOnBothSidesOfDifferentLinesAreKept() {
        val ours = list.replace("- [ ] milk\n", "")
        val theirs = list.replace("- [ ] apples\n", "")
        assertEquals("# Groceries\n- [ ] bread\n", merge(list, ours, theirs))
    }

    @Test
    fun startingFromNothing() {
        assertEquals("a\nb", merge("", "a\nb", ""))
        assertNull(merge("", "a", "b"))
    }
}
