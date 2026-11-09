package com.wanderwildwood.oboegaki.today

import com.wanderwildwood.oboegaki.notes.Note
import com.wanderwildwood.oboegaki.notes.reminders
import com.wanderwildwood.oboegaki.tasks.Due
import com.wanderwildwood.oboegaki.tasks.Entry as StoreEntry
import com.wanderwildwood.oboegaki.tasks.Fields
import com.wanderwildwood.oboegaki.tasks.Item
import com.wanderwildwood.oboegaki.tasks.Shown
import com.wanderwildwood.oboegaki.tasks.Task
import com.wanderwildwood.oboegaki.tasks.TaskList
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class TodayTest {

    private val today = LocalDate.of(2026, 11, 9)

    private fun item(title: String, due: Due?, done: Boolean = false) =
        Item("personal", "$title.ics", "", Task(title, Fields(title, due = due, done = done)), StoreEntry("$title.ics", null, null))

    private val list = TaskList("personal", null, "Personal")

    private fun note(path: String, text: String) = Note(path, path.substringBeforeLast('.'), "", 0L, text)

    @Test
    fun gathersTasksItemsAndNotesDueTodayInTimeOrder() {
        val tasks = listOf(
            Shown(
                list,
                listOf(
                    item("Call Tomas Reyes", Due.At(today.atTime(17, 0), null)),
                    item("Bins out", Due.Day(today)),
                    item("Pay rent", Due.Day(today.minusDays(2))),
                    item("Next week", Due.Day(today.plusDays(7))),
                    item("Already done", Due.Day(today), done = true),
                    item("No date", null),
                ),
            ),
        )
        val notes = listOf(
            note("Shopping.md", "- [ ] oats ⏰ 2026-11-09 09:30\n- [x] milk ⏰ 2026-11-09 08:00\n- [ ] eggs ⏰ 2026-11-07 10:00\n- [ ] jam ⏰ 2026-11-10 10:00"),
            note("Ada Whitlock.md", "---\nreminder: 2026-11-09 15:00\n---\nAsk about the hives"),
            note("Old.md", "---\nreminder: 2026-11-01 15:00\n---\nlong past"),
            note("Archive/Gone.md", "- [ ] filed ⏰ 2026-11-09 09:00"),
        )
        val titles = gather(notes, tasks, today).map { it.title }
        assertEquals(listOf("eggs", "Pay rent", "oats", "Ada Whitlock", "Call Tomas Reyes", "Bins out"), titles)
    }

    @Test
    fun movingAnItemKeepsItsTimeUnlessOneIsPicked() {
        val text = "- [ ] oats ⏰ 2026-11-09 09:30\n- [ ] eggs"
        val entry = gather(listOf(note("Shopping.md", text)), emptyList(), today).single() as Entry.OfNote
        val tomorrow = movedReminder(text, entry, today.plusDays(1))
        assertEquals("- [ ] oats ⏰ 2026-11-10 09:30\n- [ ] eggs", tomorrow)
        val picked = movedReminder(text, entry, today.plusDays(3), LocalTime.of(18, 0))
        assertEquals("2026-11-12 18:00", reminders(picked).single().at)
    }

    @Test
    fun movingANoteReminderRewritesItsFrontMatter() {
        val text = "---\nreminder: 2026-11-09 15:00\n---\nAsk about the hives"
        val entry = gather(listOf(note("Ada Whitlock.md", text)), emptyList(), today).single() as Entry.OfNote
        assertEquals("2026-11-16 15:00", reminders(movedReminder(text, entry, today.plusWeeks(1))).single().at)
    }

    @Test
    fun aMovedTaskKeepsItsTimeOfDay() {
        val moved = movedDue(Due.At(today.atTime(17, 0), null), today.plusDays(1))
        assertEquals(Due.At(today.plusDays(1).atTime(17, 0), null), moved)
        assertEquals(Due.Day(today.plusDays(1)), movedDue(Due.Day(today), today.plusDays(1)))
    }
}
