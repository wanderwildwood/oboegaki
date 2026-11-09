package com.wanderwildwood.oboegaki.today

import com.wanderwildwood.oboegaki.notes.ARCHIVE_FOLDER
import com.wanderwildwood.oboegaki.notes.Note
import com.wanderwildwood.oboegaki.notes.Reminder
import com.wanderwildwood.oboegaki.notes.findReminder
import com.wanderwildwood.oboegaki.notes.reminderAt
import com.wanderwildwood.oboegaki.notes.reminderTime
import com.wanderwildwood.oboegaki.notes.reminders
import com.wanderwildwood.oboegaki.notes.withItemReminder
import com.wanderwildwood.oboegaki.notes.withNoteReminder
import com.wanderwildwood.oboegaki.tasks.Due
import com.wanderwildwood.oboegaki.tasks.Item
import com.wanderwildwood.oboegaki.tasks.Shown
import com.wanderwildwood.oboegaki.tasks.day
import com.wanderwildwood.oboegaki.tasks.local
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * What is due today, from all of Memo: the open tasks of every list, the checklist items in notes
 * whose reminder is today, and the notes whose own reminder is. Overdue tasks and unticked items
 * stay until they are done or moved; a note's own reminder only shows on its day, since a note is
 * never ticked off and would otherwise stay for good.
 */
sealed interface Entry {
    /** The moment it is due, or null for a task due on a day with no time. */
    val at: LocalDateTime?
    val day: LocalDate
    val title: String

    data class OfTask(val item: Item, val listName: String, val readOnly: Boolean) : Entry {
        override val at: LocalDateTime? get() = item.task.fields.due?.local()
        override val day: LocalDate get() = item.task.fields.due!!.day()
        override val title: String get() = item.task.fields.summary
    }

    /** A note's reminder: an item's ([Reminder.key] its words), or the note's own (key ""). */
    data class OfNote(val path: String, val noteTitle: String, val reminder: Reminder) : Entry {
        override val at: LocalDateTime get() = reminderTime(reminder.at)!!
        override val day: LocalDate get() = at.toLocalDate()
        override val title: String get() = reminder.key.ifEmpty { noteTitle }
        val isItem: Boolean get() = reminder.key.isNotEmpty()
    }

    val overdue: Boolean get() = day.isBefore(LocalDate.now())
}

fun gather(notes: List<Note>, tasks: List<Shown>, today: LocalDate): List<Entry> {
    val out = mutableListOf<Entry>()
    for (s in tasks) {
        for (item in s.items) {
            val f = item.task.fields
            if (f.done || f.due == null || f.due.day().isAfter(today)) continue
            out += Entry.OfTask(item, s.list.name, s.list.readOnly)
        }
    }
    for (note in notes) {
        if (note.path == ARCHIVE_FOLDER || note.path.startsWith("$ARCHIVE_FOLDER/")) continue
        for (r in reminders(note.text)) {
            val at = reminderTime(r.at) ?: continue
            val day = at.toLocalDate()
            val keep = if (r.key.isEmpty()) day == today else !r.done && !day.isAfter(today)
            if (keep) out += Entry.OfNote(note.path, note.title, r)
        }
    }
    return out.sortedWith(order)
}

/** Soonest first; a task due on a day, with no time, at the end of its day; then by title. */
private val order = compareBy<Entry>({ it.at ?: it.day.atTime(23, 59) }, { it.title.lowercase() })

/** [due] moved to [day], at the same time of day if it has one. */
fun movedDue(due: Due, day: LocalDate): Due = when (due) {
    is Due.Day -> Due.Day(day)
    is Due.At -> {
        val local = due.local()!!
        Due.At(day.atTime(local.toLocalTime()), due.zone?.let { java.time.ZoneId.systemDefault() })
    }
}

/**
 * The note [text] with this entry's reminder moved to [day] (its time of day kept, or [time]
 * when one is given), found again by its words and time in case the note changed. The text as it
 * was when the reminder is no longer there.
 */
fun movedReminder(text: String, entry: Entry.OfNote, day: LocalDate, time: java.time.LocalTime? = null): String {
    val found = findReminder(text, entry.reminder.key, entry.reminder.at) ?: return text
    val at = reminderAt(day.atTime(time ?: entry.at.toLocalTime()))
    return if (found.key.isEmpty()) withNoteReminder(text, at) else withItemReminder(text, found.line, at)
}
