package com.wanderwildwood.oboegaki.remind

import com.wanderwildwood.oboegaki.notes.Note
import com.wanderwildwood.oboegaki.notes.Reminder
import com.wanderwildwood.oboegaki.notes.findReminder
import com.wanderwildwood.oboegaki.notes.reminders

/**
 * One reminder set on this phone. The note says when; this says that it was set here, so a
 * shared list does not ring on every phone it is on, only on the one whose owner asked.
 *
 * [rang]: when it rang, 0 before. [snooze]: when it rings again, 0 when it is not snoozed.
 */
data class Record(
    val id: Int,
    val path: String,
    val key: String,
    val at: String,
    val rang: Long = 0,
    val snooze: Long = 0,
) {
    fun matches(path: String, r: Reminder) = this.path == path && key == r.key && at == r.at
}

/** How a task's reminder names it, in place of a note's path: "task:" and the task's UID. */
const val TASK = "task:"

/** A task's reminder, not a note's: [Record.key] is then the task's title. */
val Record.isTask: Boolean get() = path.startsWith(TASK)

val Record.uid: String get() = path.removePrefix(TASK)

/** A line each, tab-separated; a tab or line break in an item's words is a space here. */
fun encode(records: List<Record>): String = records.joinToString("") { r ->
    listOf(r.id.toString(), clean(r.path), clean(r.key), r.at, r.rang.toString(), r.snooze.toString()).joinToString("\t") + "\n"
}

fun decode(text: String): List<Record> = text.lines().mapNotNull { line ->
    val f = line.split('\t')
    if (f.size < 6) return@mapNotNull null
    runCatching { Record(f[0].toInt(), f[1], f[2], f[3], f[4].toLong(), f[5].toLong()) }.getOrNull()
}

private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')

/**
 * The records after the note at [from] was edited on this phone from [before] to [after], and
 * saved as [to]. A reminder that appears in the edit was set here; one that goes was taken off
 * here. One whose words changed but whose time did not is the same reminder, and stays this
 * phone's only if it was before, so editing an item someone else set a reminder on does not
 * make it ring here. [next] gives new records their numbers.
 */
fun afterEdit(records: List<Record>, from: String, to: String, before: String, after: String, next: () -> Int): List<Record> {
    val was = reminders(before).map { it.key to it.at }.toSet()
    val now = reminders(after).map { it.key to it.at }.toSet()
    val added = now - was
    val removed = was - now
    var out = records.filterNot { r -> r.path == from && (r.key to r.at) in removed }
    for ((key, at) in added) {
        val renamedFrom = removed.firstOrNull { it.second == at }
        val ours = if (renamedFrom == null) true else records.any { it.path == from && it.key == renamedFrom.first && it.at == at }
        if (ours && out.none { it.path == from && it.key == key && it.at == at }) {
            out = out + Record(next(), from, key, at)
        }
    }
    return if (from == to) out else out.map { if (it.path == from) it.copy(path = to) else it }
}

/** What [Reminders.sync] makes of a note's reminder after looking for it in the notes. */
sealed interface Seen {
    /** There, in its note or in the one note it moved to: [record] says which. */
    data class Found(val record: Record, val reminder: Reminder) : Seen

    /** The notes were read and it is not in them: taken off, here or elsewhere, or its note is gone. */
    data object Gone : Seen

    /**
     * The notes could not be read just now, so there is no telling: a folder offline or not yet
     * mounted, its leave taken back, or the copy of a server's notes being fetched afresh. The
     * reminder is kept as it is and looked for again next time.
     */
    data object Unknown : Seen
}

/**
 * Looks for [r] in [text], its note as read just now (null when not there or not readable), and
 * failing that in every note, for one renamed or moved elsewhere. [readFailed] is true when
 * reading its note went wrong, rather than finding no such note. [everyNote] lists the notes,
 * or gives null when they could not be listed. A listing with nothing in it while there are
 * reminders to look for is taken as a folder that could not be read, not one emptied, so a
 * reminder is let go of only when the notes were really read and it is not in them. A note that
 * is listed while [text] is null was there but could not be opened, and gives no telling either.
 */
fun look(r: Record, records: List<Record>, text: String?, readFailed: Boolean, everyNote: () -> List<Note>?): Seen {
    text?.let { findReminder(it, r.key, r.at) }?.let { return Seen.Found(r, it) }
    if (readFailed) return Seen.Unknown
    val notes = everyNote()?.takeIf { it.isNotEmpty() } ?: return Seen.Unknown
    // Listed but not read: the note is there and its words could not be opened just now.
    if (text == null && notes.any { it.path == r.path }) return Seen.Unknown
    val elsewhere = notes.filter { findReminder(it.text, r.key, r.at) != null }
    if (elsewhere.size == 1 && records.none { it.path == elsewhere[0].path && it.key == r.key && it.at == r.at }) {
        val moved = findReminder(elsewhere[0].text, r.key, r.at)
        if (moved != null) return Seen.Found(r.copy(path = elsewhere[0].path), moved)
    }
    return Seen.Gone
}
