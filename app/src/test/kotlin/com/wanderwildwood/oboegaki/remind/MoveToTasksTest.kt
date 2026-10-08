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
}
