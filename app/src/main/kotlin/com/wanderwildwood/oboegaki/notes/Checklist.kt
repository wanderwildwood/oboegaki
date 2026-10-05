package com.wanderwildwood.oboegaki.notes

/**
 * A note read as lines, with the task lines picked out.
 *
 * A task is ordinary Markdown, `- [ ] milk` or `- [x] milk`, the form Obsidian, Nextcloud Notes
 * and GitHub all read, so a shopping list made here is a shopping list everywhere else too.
 * Nothing about a task is stored anywhere but in that line.
 */
sealed interface Line {
    val index: Int

    data class Task(override val index: Int, val indent: String, val done: Boolean, val text: String) : Line
    data class Text(override val index: Int, val text: String) : Line
}

private val TASK = Regex("""^(\s*)[-*+] \[( |x|X)\] ?(.*)$""")

fun lines(text: String): List<Line> =
    text.split('\n').mapIndexed { i, line ->
        val match = TASK.matchEntire(line)
        if (match != null) {
            val (indent, mark, rest) = match.destructured
            Line.Task(i, indent, mark != " ", rest)
        } else {
            Line.Text(i, line)
        }
    }

/** Whether the note has any tasks in it at all. */
fun hasTasks(text: String): Boolean = lines(text).any { it is Line.Task }

/**
 * Tick or untick the task on line [index], touching nothing else in the note. Only the one
 * character changes, so a tick merges cleanly with anything someone else did to other lines.
 */
fun toggle(text: String, index: Int): String {
    val all = text.split('\n').toMutableList()
    val line = all.getOrNull(index) ?: return text
    val match = TASK.matchEntire(line) ?: return text
    val open = line.indexOf('[', match.groups[1]!!.range.last + 1)
    val mark = if (line[open + 1] == ' ') 'x' else ' '
    all[index] = line.substring(0, open + 1) + mark + line.substring(open + 2)
    return all.joinToString("\n")
}

/**
 * A new task after the last one in the note, or at the end if there are none. The note keeps
 * its closing newline if it had one.
 */
fun addTask(text: String, task: String): String {
    val item = "- [ ] ${task.trim()}"
    if (text.isEmpty()) return "$item\n"
    val all = text.split('\n').toMutableList()
    val last = lines(text).lastOrNull { it is Line.Task }
    if (last != null) {
        all.add(last.index + 1, item)
        return all.joinToString("\n")
    }
    return if (text.endsWith("\n")) "$text$item\n" else "$text\n$item"
}

/**
 * Turn the line the cursor is on into a task, or back into plain text. Returns the new text and
 * how far the cursor moved, so it stays on the same word.
 */
fun toggleTaskLine(text: String, cursor: Int): Pair<String, Int> {
    val start = text.lastIndexOf('\n', cursor - 1) + 1
    val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
    val line = text.substring(start, end)
    val match = TASK.matchEntire(line)
    return if (match != null) {
        val (indent, _, rest) = match.destructured
        val plain = indent + rest
        text.substring(0, start) + plain + text.substring(end) to (plain.length - line.length)
    } else {
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        val bare = line.drop(indent.length).removePrefix("- ").removePrefix("* ")
        val task = "$indent- [ ] $bare"
        text.substring(0, start) + task + text.substring(end) to (task.length - line.length)
    }
}

/** The files a note links the Obsidian way, `![[name.m4a]]`, by name, in order. */
fun embeds(text: String): List<String> =
    Regex("""!\[\[([^\]|]+)(?:\|[^\]]*)?]]""").findAll(text).map { it.groupValues[1].trim() }.toList()

/** Whether a line is only a link to a recording. */
fun recordingOn(line: String): String? =
    embeds(line.trim()).singleOrNull()?.takeIf { line.trim().startsWith("![[") && line.trim().endsWith("]]") && isSound(it) }

fun isSound(name: String): Boolean =
    listOf("m4a", "mp3", "wav", "ogg", "opus", "aac").any { name.lowercase().endsWith(".$it") }

/** Whether a line is only a link to a scanned PDF. */
fun scanOn(line: String): String? =
    embeds(line.trim()).singleOrNull()?.takeIf {
        line.trim().startsWith("![[") && line.trim().endsWith("]]") && it.lowercase().endsWith(".pdf")
    }
