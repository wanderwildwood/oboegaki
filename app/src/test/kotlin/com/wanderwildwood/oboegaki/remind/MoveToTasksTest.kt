package com.wanderwildwood.oboegaki.remind

import com.wanderwildwood.oboegaki.notes.removeLine
import com.wanderwildwood.oboegaki.notes.reminders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** An item moved to Tasks takes its reminder with it: the note's record goes, so it never rings twice. */
class MoveToTasksTest {

    @Test
    fun theLinesReminderIsLetGoWhenItBecomesATask() {
        val before = "# Groceries\n- [ ] milk ⏰ 2026-10-08 17:00\n- [ ] bread ⏰ 2026-10-08 18:00\n"
        val records = listOf(Record(1, "Groceries.md", "milk", "2026-10-08 17:00"), Record(2, "Groceries.md", "bread", "2026-10-08 18:00"))
        val after = removeLine(before, 1)
        assertEquals("# Groceries\n- [ ] bread ⏰ 2026-10-08 18:00\n", after)
        val left = afterEdit(records, "Groceries.md", "Groceries.md", before, after) { 99 }
        assertEquals(listOf(records[1]), left)
        assertTrue(reminders(after).none { it.key == "milk" })
    }

    @Test
    fun aTasksRecordIsNeverANotesRecord() {
        val task = Record(3, TASK + "uid-1", "milk", "2026-10-08 17:00")
        assertTrue(task.isTask)
        assertEquals("uid-1", task.uid)
        // Editing any note leaves a task's reminder alone.
        val left = afterEdit(listOf(task), "Groceries.md", "Groceries.md", "- [ ] milk ⏰ 2026-10-08 17:00\n", "") { 99 }
        assertEquals(listOf(task), left)
        assertEquals(listOf(task), decode(encode(listOf(task))))
    }

    @Test
    fun anItemBecomesATaskWithItsWordsTimeAndTick() {
        val text = "# Groceries\n- [ ] milk ⏰ 2026-10-08 17:00\n- [x] bread\n"
        val zone = java.time.ZoneId.of("America/New_York")
        val milk = com.wanderwildwood.oboegaki.notes.lines(text)[1] as com.wanderwildwood.oboegaki.notes.Line.Task
        val bread = com.wanderwildwood.oboegaki.notes.lines(text)[2] as com.wanderwildwood.oboegaki.notes.Line.Task
        assertEquals(
            com.wanderwildwood.oboegaki.tasks.Fields("milk", due = com.wanderwildwood.oboegaki.tasks.Due.At(java.time.LocalDateTime.of(2026, 10, 8, 17, 0), zone), done = false),
            itemAsTask(milk, zone),
        )
        assertEquals(com.wanderwildwood.oboegaki.tasks.Fields("bread", done = true), itemAsTask(bread, zone))
    }
}
