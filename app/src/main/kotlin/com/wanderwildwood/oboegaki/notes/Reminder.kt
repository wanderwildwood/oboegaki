package com.wanderwildwood.oboegaki.notes

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Reminders, written into the note itself, so they travel with it through Nextcloud, WebDAV or
 * Syncthing and read plainly in any editor.
 *
 * On a checklist item, at the end of its line, the way the Obsidian Reminder plugin writes one
 * in its Tasks form: `- [ ] milk ⏰ 2026-10-08 17:00`. On a whole note, a line in its front
 * matter, where Obsidian shows it as a property: `reminder: 2026-10-08 17:00`. Times are the
 * phone's clock times, as an alarm clock's are.
 */
data class Reminder(
    /** The item's words without the time, or "" for the note itself. */
    val key: String,
    /** "2026-10-08 17:00". */
    val at: String,
    /** The item is ticked: its reminder no longer rings. Never true for a note. */
    val done: Boolean = false,
    /** The task's line, or -1 for the note. */
    val line: Int = -1,
)

val REMINDER_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private val ITEM_TIME = Regex("""\s*⏰\s*(\d{4}-\d{2}-\d{2})[ T](\d{1,2}):(\d{2})""")
private val NOTE_TIME = Regex("""^reminder:\s*["']?(\d{4}-\d{2}-\d{2})[ T](\d{1,2}):(\d{2})["']?\s*$""")
private val TASK_LINE = Regex("""^(\s*[-*+] \[( |x|X)\] ?)(.*)$""")

fun reminderTime(at: String): LocalDateTime? = runCatching { LocalDateTime.parse(at, REMINDER_FORMAT) }.getOrNull()

fun reminderAt(at: LocalDateTime): String = REMINDER_FORMAT.format(at)

private fun normal(date: String, hour: String, minute: String): String? =
    runCatching { reminderAt(LocalDateTime.parse("$date ${hour.padStart(2, '0')}:$minute", REMINDER_FORMAT)) }.getOrNull()

/** An item's words with its reminder taken out: what it is called, and what the list shows. */
fun withoutReminder(words: String): String = words.replace(ITEM_TIME, "").trim()

/** The time on an item's words, if there is one. */
fun itemReminder(words: String): String? =
    ITEM_TIME.find(words)?.destructured?.let { (d, h, m) -> normal(d, h, m) }

/** The front matter's lines, first and last `---` included, or null when the note has none. */
fun frontMatter(text: String): IntRange? {
    val all = text.split('\n')
    if (all.firstOrNull()?.trimEnd() != "---") return null
    val close = all.drop(1).indexOfFirst { it.trimEnd() == "---" }
    return if (close < 0) null else 0..close + 1
}

/** The reminder on the whole note, from its front matter. */
fun noteReminder(text: String): String? {
    val range = frontMatter(text) ?: return null
    val all = text.split('\n')
    for (i in range) {
        NOTE_TIME.matchEntire(all[i].trimEnd())?.destructured?.let { (d, h, m) -> return normal(d, h, m) }
    }
    return null
}

/** Every reminder in a note: the note's own first, then the items' in order. */
fun reminders(text: String): List<Reminder> {
    val out = mutableListOf<Reminder>()
    noteReminder(text)?.let { out += Reminder("", it) }
    for (line in lines(text)) {
        if (line !is Line.Task) continue
        val at = itemReminder(line.text) ?: continue
        out += Reminder(withoutReminder(line.text), at, line.done, line.index)
    }
    return out
}

/** The note with its own reminder set to [at], or taken off with null. */
fun withNoteReminder(text: String, at: String?): String {
    val all = text.split('\n').toMutableList()
    val range = frontMatter(text)
    if (range != null) {
        val i = range.firstOrNull { it > range.first && it < range.last && NOTE_TIME.matchEntire(all[it].trimEnd()) != null }
        when {
            at != null && i != null -> all[i] = "reminder: $at"
            at != null -> all.add(range.last, "reminder: $at")
            i != null -> {
                all.removeAt(i)
                // Front matter that held only the reminder goes with it.
                if (range.last - range.first == 2) {
                    all.removeAt(range.first)
                    all.removeAt(range.first)
                }
            }
        }
        return all.joinToString("\n")
    }
    if (at == null) return text
    return "---\nreminder: $at\n---\n$text"
}

/** The task on line [index] with its reminder set to [at], or taken off with null. */
fun withItemReminder(text: String, index: Int, at: String?): String {
    val all = text.split('\n').toMutableList()
    val line = all.getOrNull(index) ?: return text
    val match = TASK_LINE.matchEntire(line) ?: return text
    val (head, _, words) = match.destructured
    val bare = words.replace(ITEM_TIME, "").trimEnd()
    all[index] = head + if (at == null) bare else "$bare ⏰ $at"
    return all.joinToString("\n")
}

/** Where a reminder is in [text] now: by its words and time, or null when it has gone. */
fun findReminder(text: String, key: String, at: String): Reminder? =
    reminders(text).firstOrNull { it.key == key && it.at == at }

/**
 * The front matter as it stands at the top of [text], closing line break and all, or "" when
 * there is none: what the editor leaves out, so it shows the words rather than the YAML.
 */
fun frontMatterHead(text: String): String {
    val range = frontMatter(text) ?: return ""
    var end = 0
    repeat(range.last + 1) { end = text.indexOf('\n', end).let { if (it < 0) text.length else it + 1 } }
    return text.substring(0, end)
}
