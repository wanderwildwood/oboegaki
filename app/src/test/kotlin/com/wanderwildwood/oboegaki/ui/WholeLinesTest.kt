package com.wanderwildwood.oboegaki.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WholeLines.kt's cut (22 Nov 2026): long text in rows of whole lines, none taller than the page. */
class WholeLinesTest {

    private fun bottoms(lines: Int, height: Float = 26f) = (1..lines).map { it * height }

    @Test
    fun eachRunFillsAPageWithWholeLines() {
        // 30 lines of 26 px on a 300 px page, 12 px of row padding: 288 px a run, 11 lines.
        val groups = wholeLineGroups(bottoms(30), gap = 12f, room = 300f)
        assertEquals(listOf(0..10, 11..21, 22..29), groups)
        assertEquals((0..29).toList(), groups.flatMap { it.toList() })
    }

    @Test
    fun noRunIsTallerThanThePage() {
        val b = bottoms(47, 34.5f)
        val groups = wholeLineGroups(b, gap = 12f, room = 590f)
        groups.forEach { g ->
            val top = if (g.first == 0) 0f else b[g.first - 1]
            assertTrue(b[g.last] - top + 12f <= 590f)
        }
    }

    @Test
    fun aLineTallerThanThePageStillGetsARun() {
        assertEquals(listOf(0..0, 1..1), wholeLineGroups(listOf(400f, 800f), gap = 12f, room = 300f))
    }

    @Test
    fun textThatFitsIsOneRun() {
        assertEquals(listOf(0..4), wholeLineGroups(bottoms(5), gap = 12f, room = 300f))
    }
}
