package com.wanderwildwood.oboegaki.glance

import org.junit.Assert.assertEquals
import org.junit.Test

class PinnedLinesTest {

    private val words = LockWords(
        heading = "Notes",
        allDone = { "$it · all done" },
        left = { title, n -> "$title · $n to do" },
        more = { "and $it more" },
    )

    /** Each line as "lead|text|more", heading in front of the first. */
    private fun shown(lines: List<GlanceProvider.Line>) =
        lines.map { listOfNotNull(it.heading?.let { h -> "[$h]" }, it.lead, it.text, it.more.takeIf { m -> m > 0 }?.toString()).joinToString("|") }

    @Test
    fun aNoteListsItsFirstOpenItemsThenHowManyMore() {
        val text = "- [ ] oats\n- [x] milk\n- [ ] eggs ⏰ 2026-11-20 09:00\n- [ ] jam\nsome words\n  - [ ] bread\n- [ ] honey"
        val lines = pinnedLines(listOf(PinnedNote("Lists/Groceries.md", text)), emptySet(), words)
        assertEquals(
            listOf("[Notes]|Groceries", "•|oats", "•|eggs", "•|jam", "and 2 more|2"),
            shown(lines),
        )
    }

    @Test
    fun allOpenItemsFitWithNoMoreLineAndATickedListSaysAllDone() {
        val lines = pinnedLines(
            listOf(
                PinnedNote("Standing.md", "- [ ] water the plants\n- [ ] call Ada Whitlock"),
                PinnedNote("Packed.md", "- [x] tent\n- [x] stove"),
                PinnedNote("Plain.md", "just words"),
            ),
            emptySet(),
            words,
        )
        assertEquals(
            listOf("[Notes]|Standing", "•|water the plants", "•|call Ada Whitlock", "Packed · all done", "Plain"),
            shown(lines),
        )
    }

    @Test
    fun anItemUnderTodayIsNotShownAgain() {
        val text = "- [ ] oats ⏰ 2026-11-09 09:30\n- [ ] eggs\n- [ ] jam"
        val lines = pinnedLines(listOf(PinnedNote("Groceries.md", text)), setOf("Groceries.md" to 0), words)
        assertEquals(listOf("[Notes]|Groceries", "•|eggs", "•|jam"), shown(lines))
    }

    @Test
    fun pastTheOverallCapANoteIsOneLineAgain() {
        val four = "- [ ] a\n- [ ] b\n- [ ] c\n- [ ] d"
        val notes = (1..4).map { PinnedNote("N$it.md", four) }
        val lines = shown(pinnedLines(notes, emptySet(), words))
        assertEquals(PINNED_ITEMS_IN_ALL, lines.count { it.startsWith("$ITEM_LEAD|") })
        assertEquals("N4 · 4 to do", lines.last())
        assertEquals(listOf("[Notes]|N1", "•|a", "•|b", "•|c", "and 1 more|1"), lines.take(5))
    }
}
