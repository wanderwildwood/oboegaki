package com.wanderwildwood.oboegaki.tasks

/**
 * An iCalendar file, read just far enough to change a task in it and leave everything else alone.
 *
 * Each property is kept as it stood in the file, folded lines and all, so what this app does not
 * understand goes back to the server byte for byte: a subtask's RELATED-TO, categories, alarms,
 * another client's X- properties, a VTIMEZONE, a second VTODO for one instance of a repeating
 * task. Only the properties this app changes are written anew, and only when they changed.
 */
class ICal private constructor(
    /** One entry per property or BEGIN/END line, as the file had it, without the line break. */
    private val items: MutableList<String>,
    private val eol: String,
) {

    override fun toString(): String = items.joinToString("") { it + eol }

    /** The range of [items] that is the task: the first VTODO with no RECURRENCE-ID, BEGIN to END. */
    private fun master(): IntRange? {
        var depth = 0
        var start = -1
        var nested = 0
        for ((i, raw) in items.withIndex()) {
            val line = unfold(raw)
            val upper = line.uppercase()
            when {
                upper.startsWith("BEGIN:") -> {
                    if (start < 0 && depth == 1 && upper == "BEGIN:VTODO") {
                        start = i
                        nested = 0
                    } else if (start >= 0) {
                        nested++
                    }
                    depth++
                }
                upper.startsWith("END:") -> {
                    depth--
                    if (start >= 0) {
                        if (nested == 0) {
                            val range = start..i
                            if (ownProps(range).none { it.second.name == "RECURRENCE-ID" }) return range
                            start = -1
                        } else {
                            nested--
                        }
                    }
                }
            }
        }
        return null
    }

    /** The task's own properties, not those of an alarm or anything else inside it, by index. */
    private fun ownProps(range: IntRange): List<Pair<Int, Prop>> {
        val out = mutableListOf<Pair<Int, Prop>>()
        var nested = 0
        for (i in range.first + 1 until range.last) {
            val line = unfold(items[i])
            val upper = line.uppercase()
            when {
                upper.startsWith("BEGIN:") -> nested++
                upper.startsWith("END:") -> nested--
                nested == 0 -> parseProp(line)?.let { out += i to it }
            }
        }
        return out
    }

    val hasTask: Boolean get() = master() != null

    /** Every one of the task's own properties, in order. */
    fun props(): List<Prop> = master()?.let { range -> ownProps(range).map { it.second } }.orEmpty()

    fun prop(name: String): Prop? = props().firstOrNull { it.name == name }

    /**
     * Sets the task's property [name] to the content line [line] ("SUMMARY:Milk"), in place of
     * the one there; or takes it off with null. A property not there yet goes in before the
     * task's END. Every other line stays as it was.
     */
    fun set(name: String, line: String?) {
        val range = master() ?: return
        val found = ownProps(range).filter { it.second.name == name }.map { it.first }
        if (found.isEmpty()) {
            if (line != null) items.add(range.last, fold(line, eol))
            return
        }
        // A second copy of a property that may only appear once is dropped with the first.
        for (i in found.drop(1).reversed()) items.removeAt(i)
        if (line == null) items.removeAt(found.first()) else items[found.first()] = fold(line, eol)
    }

    companion object {

        fun parse(text: String): ICal {
            val eol = if (text.contains("\r\n")) "\r\n" else "\n"
            val physical = text.split(Regex("\r?\n"))
            val items = mutableListOf<String>()
            for (line in physical) {
                // A line that starts with a space or a tab carries on the one before it.
                if ((line.startsWith(" ") || line.startsWith("\t")) && items.isNotEmpty()) {
                    items[items.size - 1] = items.last() + eol + line
                } else if (line.isNotEmpty()) {
                    items += line
                }
            }
            return ICal(items, eol)
        }

        /** A file holding one new task, with [lines] as its properties, UID first. */
        fun newTask(lines: List<String>): ICal {
            val items = mutableListOf(
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "PRODID:-//wander wildwood//Notes//EN",
                "BEGIN:VTODO",
            )
            items += lines.map { fold(it, "\r\n") }
            items += listOf("END:VTODO", "END:VCALENDAR")
            return ICal(items, "\r\n")
        }
    }
}

/** One property: its name, its parameters (names upper case, values unquoted), its value as written. */
data class Prop(val name: String, val params: Map<String, String>, val value: String) {
    fun param(name: String): String? = params[name]
}

/** A folded line put back on one line. */
fun unfold(raw: String): String = raw.replace(Regex("\r?\n[ \t]"), "")

/** Reads "DUE;TZID=Europe/Berlin:20261008T170000" into its name, parameters and value. */
fun parseProp(line: String): Prop? {
    var quoted = false
    var colon = -1
    for ((i, c) in line.withIndex()) {
        if (c == '"') quoted = !quoted
        if (c == ':' && !quoted) {
            colon = i
            break
        }
    }
    if (colon <= 0) return null
    val head = line.substring(0, colon)
    val value = line.substring(colon + 1)
    val parts = mutableListOf<String>()
    val sb = StringBuilder()
    quoted = false
    for (c in head) {
        if (c == '"') quoted = !quoted
        if (c == ';' && !quoted) {
            parts += sb.toString()
            sb.clear()
        } else {
            sb.append(c)
        }
    }
    parts += sb.toString()
    val params = parts.drop(1).mapNotNull { p ->
        val eq = p.indexOf('=')
        if (eq <= 0) null else p.substring(0, eq).uppercase() to p.substring(eq + 1).trim('"')
    }.toMap()
    return Prop(parts[0].uppercase(), params, value)
}

/** A content line folded at 75 octets, as RFC 5545 asks, never inside a character. */
fun fold(line: String, eol: String = "\r\n"): String {
    if (line.toByteArray(Charsets.UTF_8).size <= 75) return line
    val out = StringBuilder()
    var octets = 0
    var limit = 75
    var i = 0
    while (i < line.length) {
        val cp = line.codePointAt(i)
        val chars = Character.charCount(cp)
        val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
        if (octets + n > limit) {
            out.append(eol).append(' ')
            octets = 0
            // The space that starts a carried-on line counts against it.
            limit = 74
        }
        out.append(line, i, i + chars)
        octets += n
        i += chars
    }
    return out.toString()
}

/** A TEXT value as written in the file, back to the words. */
fun unescapeText(value: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '\\' && i + 1 < value.length) {
            when (val n = value[i + 1]) {
                'n', 'N' -> out.append('\n')
                else -> out.append(n)
            }
            i += 2
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}

/** Words as a TEXT value: backslashes, semicolons, commas and line breaks escaped. */
fun escapeText(text: String): String =
    text.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\\n").replace("\n", "\\n")
