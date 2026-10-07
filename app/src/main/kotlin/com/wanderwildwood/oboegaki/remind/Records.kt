package com.wanderwildwood.oboegaki.remind

import com.wanderwildwood.oboegaki.notes.Reminder
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
