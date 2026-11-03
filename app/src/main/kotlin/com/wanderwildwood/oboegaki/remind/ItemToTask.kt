package com.wanderwildwood.oboegaki.remind

import android.content.Context
import com.wanderwildwood.oboegaki.notes.Line
import com.wanderwildwood.oboegaki.notes.Reminder
import com.wanderwildwood.oboegaki.notes.itemReminder
import com.wanderwildwood.oboegaki.notes.reminderTime
import com.wanderwildwood.oboegaki.notes.withoutReminder
import com.wanderwildwood.oboegaki.tasks.Due
import com.wanderwildwood.oboegaki.tasks.Fields
import java.time.ZoneId

/*
 * A checklist item moved to Tasks ("Move to Tasks" on the phone, and from Desktop Sync): it
 * leaves the note and becomes a task, moved, not copied, so it is never on both lists. Its words
 * are the task's title, its reminder the task's due time, its tick the task's done.
 */

/** The task a checklist item becomes. */
fun itemAsTask(item: Line.Task, zone: ZoneId = ZoneId.systemDefault()): Fields {
    val due = itemReminder(item.text)?.let(::reminderTime)?.let { Due.At(it, zone) }
    return Fields(withoutReminder(item.text), due = due, done = item.done)
}

/**
 * Whether the task rings on this phone: where the item's reminder rang. Asked before the item
 * leaves the note, since the note's record of it goes when it does.
 */
fun itemRingsHere(context: Context, notePath: String, item: Line.Task): Boolean {
    val time = itemReminder(item.text) ?: return false
    return Reminders.isHere(context, notePath, Reminder(withoutReminder(item.text), time))
}
