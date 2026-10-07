package com.wanderwildwood.oboegaki.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderTest {

    private val list = "# Groceries\n- [ ] milk ⏰ 2026-10-08 17:00\n- [x] eggs ⏰ 2026-10-08 09:05\n- [ ] bread\n"

    @Test
    fun readsItemReminders() {
        val found = reminders(list)
        assertEquals(listOf(Reminder("milk", "2026-10-08 17:00", false, 1), Reminder("eggs", "2026-10-08 09:05", true, 2)), found)
    }

    @Test
    fun setsReplacesAndTakesOffAnItemsReminder() {
        val set = withItemReminder(list, 3, "2026-10-09 08:30")
        assertEquals("- [ ] bread ⏰ 2026-10-09 08:30", set.split('\n')[3])
        val moved = withItemReminder(set, 1, "2026-10-08 18:00")
        assertEquals("- [ ] milk ⏰ 2026-10-08 18:00", moved.split('\n')[1])
        val off = withItemReminder(moved, 1, null)
        assertEquals("- [ ] milk", off.split('\n')[1])
        // Nothing else in the note moves.
        assertEquals(list.split('\n').drop(2), withItemReminder(list, 1, null).split('\n').drop(2))
    }

    @Test
    fun tickingKeepsTheTimeButEndsTheReminder() {
        val ticked = toggle(list, 1)
        assertEquals("- [x] milk ⏰ 2026-10-08 17:00", ticked.split('\n')[1])
        assertTrue(reminders(ticked).first { it.key == "milk" }.done)
    }

    @Test
    fun aNoteReminderLivesInFrontMatter() {
        val text = "Call the vet\n"
        val set = withNoteReminder(text, "2026-10-08 17:00")
        assertEquals("---\nreminder: 2026-10-08 17:00\n---\nCall the vet\n", set)
        assertEquals("2026-10-08 17:00", noteReminder(set))
        assertEquals(text, withNoteReminder(set, null))
        // Front matter of Obsidian's own keeps everything else.
        val obsidian = "---\ntags: [home]\n---\nCall the vet\n"
        val both = withNoteReminder(obsidian, "2026-10-08 17:00")
        assertEquals("---\ntags: [home]\nreminder: 2026-10-08 17:00\n---\nCall the vet\n", both)
        assertEquals("---\ntags: [home]\nreminder: 2026-10-09 09:00\n---\nCall the vet\n", withNoteReminder(both, "2026-10-09 09:00"))
        assertEquals(obsidian, withNoteReminder(both, null))
    }

    @Test
    fun readsLooserTimesAsTheSameMinute() {
        assertEquals("2026-10-08 07:05", itemReminder("milk ⏰2026-10-08T7:05"))
        assertEquals("2026-10-08 17:00", noteReminder("---\nreminder: \"2026-10-08 17:00\"\n---\n"))
        assertNull(itemReminder("milk ⏰ 2026-13-40 17:00"))
        assertNull(noteReminder("reminder: 2026-10-08 17:00\n"))
    }

    @Test
    fun previewsAndCalendarTextLeaveTheTimeOut() {
        assertEquals("milk", plain("- [ ] milk ⏰ 2026-10-08 17:00"))
        assertEquals("Call the vet", reminderText("---\nreminder: 2026-10-08 17:00\n---\nCall the vet\n"))
        assertEquals("Call the vet", preview("---\nreminder: 2026-10-08 17:00\n---\nCall the vet\n"))
    }
}
