package com.wanderwildwood.oboegaki.ui

import com.wanderwildwood.oboegaki.notes.lines
import org.junit.Assert.assertEquals
import org.junit.Test

class FoldHeadingsTest {

    private val note = """
        # Stops
        - [ ] 1 Flute
        sub line
        ## Pedal
        - [ ] 2 Bourdon
        # Other
        - [ ] 3 Viola
    """.trimIndent()

    private fun shown(vararg folded: Int) = withoutFolded(lines(note), folded.toSet()).map { it.index }

    @Test
    fun nothingFoldedShowsEverything() = assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), shown())

    @Test
    fun foldingAHeadingHidesUntilTheNextOneAsBig() = assertEquals(listOf(0, 5, 6), shown(0))

    @Test
    fun aSmallerHeadingFoldsOnlyItsOwnPart() = assertEquals(listOf(0, 1, 2, 3, 5, 6), shown(3))

    @Test
    fun aFoldedHeadingInsideAFoldedOneStaysHidden() = assertEquals(listOf(0, 5, 6), shown(0, 3))

    @Test
    fun headingLevels() {
        assertEquals(1, headingLevel("# a"))
        assertEquals(3, headingLevel("  ### a"))
        assertEquals(0, headingLevel("#tag"))
        assertEquals(0, headingLevel("plain"))
        assertEquals(0, headingLevel("####### seven"))
    }
}
