package com.wanderwildwood.oboegaki.today

import android.content.Context
import com.wanderwildwood.oboegaki.notes.Notes
import com.wanderwildwood.oboegaki.notes.findReminder
import com.wanderwildwood.oboegaki.notes.toggle
import com.wanderwildwood.oboegaki.notes.withItemReminder
import com.wanderwildwood.oboegaki.notes.withNoteReminder
import com.wanderwildwood.oboegaki.remind.Reminders
import com.wanderwildwood.oboegaki.tasks.Due
import com.wanderwildwood.oboegaki.tasks.Fields
import com.wanderwildwood.oboegaki.tasks.Tasks
import com.wanderwildwood.oboegaki.tasks.local
import java.time.LocalDate

/** What can be done to a Today row without opening it: tick it, move it to another day, add one. */
object TodayActions {

    /** A task or a checklist item ticked. A note's own reminder has nothing to tick. */
    fun tick(context: Context, entry: Entry) {
        when (entry) {
            is Entry.OfTask -> if (!entry.readOnly) Tasks.tick(entry.item)
            is Entry.OfNote -> if (entry.isItem) changeNote(context, entry.path) { text ->
                findReminder(text, entry.reminder.key, entry.reminder.at)?.let { toggle(text, it.line) } ?: text
            }
        }
    }

    /**
     * Moved to [day], at the same time of day. [exact], when one was picked, says it all: a task
     * takes it as it is (a day with no time stays one); a note's reminder takes its time.
     */
    fun move(context: Context, entry: Entry, day: LocalDate, exact: Due? = null) {
        when (entry) {
            is Entry.OfTask -> {
                if (entry.readOnly) return
                val from = entry.item.task.fields
                val due = from.due ?: return
                setDue(context, entry, exact ?: movedDue(due, day))
            }
            is Entry.OfNote -> {
                val time = (exact as? Due.At)?.local()?.toLocalTime()
                changeNote(context, entry.path) { movedReminder(it, entry, day, time) }
            }
        }
    }

    /** Its date taken off: a task then has none, a note's reminder is gone. */
    fun clear(context: Context, entry: Entry) {
        when (entry) {
            is Entry.OfTask -> if (!entry.readOnly) setDue(context, entry, null)
            is Entry.OfNote -> changeNote(context, entry.path) { text ->
                val found = findReminder(text, entry.reminder.key, entry.reminder.at) ?: return@changeNote text
                if (found.key.isEmpty()) withNoteReminder(text, null) else withItemReminder(text, found.line, null)
            }
        }
    }

    private fun setDue(context: Context, entry: Entry.OfTask, due: Due?) {
        val from = entry.item.task.fields
        val rings = due?.local() != null && Reminders.isTaskHere(context, entry.item.task.uid)
        Tasks.edit(entry.item, from, from.copy(due = due), rings)
    }

    /** A new task due today, in the list new tasks go in. Not ringing: it has no time. */
    fun add(title: String) {
        val words = title.trim()
        if (words.isEmpty()) return
        Tasks.add(Tasks.defaultList().id, Fields(words, due = Due.Day(LocalDate.now())), ring = false)
    }

    /** A note changed from outside its screen, and its reminders told, as the editor tells them. */
    private fun changeNote(context: Context, path: String, edit: (String) -> String) {
        var before = ""
        var after = ""
        Notes.change(path) { now ->
            before = now
            edit(now).also { after = it }
        }
        if (before != after) Reminders.edited(context, path, path, before, after)
    }
}
