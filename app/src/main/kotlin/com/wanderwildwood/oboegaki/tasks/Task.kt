package com.wanderwildwood.oboegaki.tasks

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** When a task is due: on a day, or at a moment. */
sealed interface Due {
    data class Day(val date: LocalDate) : Due

    /** A moment in [zone]; with no zone it is "floating", whatever the clock where it is read says. */
    data class At(val time: LocalDateTime, val zone: ZoneId?) : Due

    /** What two dues are compared by: the same moment written in two zones is the same due. */
    val key: String
        get() = when (this) {
            is Day -> "D$date"
            is At -> if (zone == null) "F$time" else "I" + time.atZone(zone).toInstant()
        }
}

/** The moment it is due on the phone's clock, or null for a task due on a day. */
fun Due.local(zone: ZoneId = ZoneId.systemDefault()): LocalDateTime? = when (this) {
    is Due.Day -> null
    is Due.At -> if (this.zone == null) time else time.atZone(this.zone).withZoneSameInstant(zone).toLocalDateTime()
}

/** The day it is due on, on the phone's clock. */
fun Due.day(zone: ZoneId = ZoneId.systemDefault()): LocalDate = when (this) {
    is Due.Day -> date
    is Due.At -> local(zone)!!.toLocalDate()
}

/**
 * What this app reads and writes of a task. Everything else in it is carried along untouched.
 * [priority] is iCalendar's: 0 none, 1 highest, 9 lowest.
 */
data class Fields(
    val summary: String,
    val description: String = "",
    val due: Due? = null,
    val priority: Int = 0,
    val done: Boolean = false,
)

/** A task as read from its file. [repeats]: its RRULE, if it has one. */
data class Task(val uid: String, val fields: Fields, val repeats: String? = null)

private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
private val DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

/**
 * A zone named by a TZID. Most clients use the IANA names; some put a path in front of them
 * ("/mozilla.org/20050126_1/Europe/Berlin"). One that cannot be found is read as floating.
 */
fun zoneOf(tzid: String): ZoneId? {
    val clean = tzid.trim().trim('"')
    runCatching { return ZoneId.of(clean) }
    val parts = clean.split('/').filter { it.isNotEmpty() }
    for (n in 3 downTo 1) {
        if (parts.size >= n) runCatching { return ZoneId.of(parts.takeLast(n).joinToString("/")) }
    }
    return null
}

/** A DUE (or DTSTART) as written: a date, a UTC time, a time in a zone, or a floating time. */
fun parseDue(prop: Prop): Due? {
    val value = prop.value.trim()
    return runCatching {
        if (prop.param("VALUE")?.uppercase() == "DATE" || value.length == 8) {
            Due.Day(LocalDate.parse(value.take(8), DATE))
        } else if (value.endsWith("Z") || value.endsWith("z")) {
            Due.At(LocalDateTime.parse(value.dropLast(1), DATE_TIME), ZoneOffset.UTC)
        } else {
            Due.At(LocalDateTime.parse(value, DATE_TIME), prop.param("TZID")?.let(::zoneOf))
        }
    }.getOrNull()
}

/** The DUE line for [due]. A moment in a zone is written in UTC, which needs no VTIMEZONE. */
fun dueLine(due: Due): String = when (due) {
    is Due.Day -> "DUE;VALUE=DATE:" + DATE.format(due.date)
    is Due.At -> if (due.zone == null) {
        "DUE:" + DATE_TIME.format(due.time)
    } else {
        "DUE:" + DATE_TIME.format(due.time.atZone(due.zone).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()) + "Z"
    }
}

fun utcStamp(at: Instant): String = DATE_TIME.format(LocalDateTime.ofInstant(at, ZoneOffset.UTC)) + "Z"

/** The task in an iCalendar file, or null when it holds none. */
fun readTask(text: String): Task? {
    val ical = ICal.parse(text)
    if (!ical.hasTask) return null
    return readTask(ical)
}

fun readTask(ical: ICal): Task {
    val props = ical.props()
    fun first(name: String) = props.firstOrNull { it.name == name }
    val status = first("STATUS")?.value?.trim()?.uppercase()
    val done = when (status) {
        "COMPLETED", "CANCELLED" -> true
        "NEEDS-ACTION", "IN-PROCESS" -> false
        else -> first("COMPLETED") != null
    }
    return Task(
        uid = first("UID")?.value?.trim().orEmpty(),
        fields = Fields(
            summary = first("SUMMARY")?.value?.let(::unescapeText).orEmpty(),
            description = first("DESCRIPTION")?.value?.let(::unescapeText).orEmpty(),
            due = first("DUE")?.let(::parseDue),
            priority = first("PRIORITY")?.value?.trim()?.toIntOrNull()?.coerceIn(0, 9) ?: 0,
            done = done,
        ),
        repeats = first("RRULE")?.value?.trim(),
    )
}

/**
 * The file [text] with the task's fields changed from [from] to [to]. Only what differs is
 * written; every other line is left exactly as it was. Anything changed moves LAST-MODIFIED and
 * DTSTAMP on, which is how other clients tell a newer copy.
 */
fun applyFields(text: String, from: Fields, to: Fields, now: Instant): String {
    if (from == to) return text
    val ical = ICal.parse(text)
    if (!ical.hasTask) return text
    if (to.summary != from.summary) {
        ical.set("SUMMARY", "SUMMARY:" + escapeText(to.summary))
    }
    if (to.description != from.description) {
        ical.set("DESCRIPTION", if (to.description.isEmpty()) null else "DESCRIPTION:" + escapeText(to.description))
    }
    if (to.due?.key != from.due?.key) {
        ical.set("DUE", to.due?.let(::dueLine))
        // DUE and DURATION may not both be there.
        if (to.due != null) ical.set("DURATION", null)
    }
    if (to.priority != from.priority) {
        ical.set("PRIORITY", if (to.priority == 0) null else "PRIORITY:${to.priority}")
    }
    if (to.done != from.done) {
        if (to.done) {
            ical.set("STATUS", "STATUS:COMPLETED")
            ical.set("COMPLETED", "COMPLETED:" + utcStamp(now))
            ical.set("PERCENT-COMPLETE", "PERCENT-COMPLETE:100")
        } else {
            ical.set("STATUS", "STATUS:NEEDS-ACTION")
            ical.set("COMPLETED", null)
            ical.set("PERCENT-COMPLETE", null)
        }
    }
    ical.set("LAST-MODIFIED", "LAST-MODIFIED:" + utcStamp(now))
    ical.set("DTSTAMP", "DTSTAMP:" + utcStamp(now))
    return ical.toString()
}

/** A new file holding one new task. */
fun newTask(uid: String, fields: Fields, now: Instant): String {
    val stamp = utcStamp(now)
    val lines = mutableListOf("UID:$uid", "DTSTAMP:$stamp", "CREATED:$stamp", "LAST-MODIFIED:$stamp")
    lines += "SUMMARY:" + escapeText(fields.summary)
    if (fields.description.isNotEmpty()) lines += "DESCRIPTION:" + escapeText(fields.description)
    fields.due?.let { lines += dueLine(it) }
    if (fields.priority != 0) lines += "PRIORITY:${fields.priority}"
    if (fields.done) {
        lines += listOf("STATUS:COMPLETED", "COMPLETED:$stamp", "PERCENT-COMPLETE:100")
    } else {
        lines += "STATUS:NEEDS-ACTION"
    }
    return ICal.newTask(lines).toString()
}

/** Which field a true conflict was in, for the line that keeps the phone's side of it. */
enum class FieldName { SUMMARY, DESCRIPTION, DUE, PRIORITY }

/**
 * Two copies of a task changed since [base]: this phone's, [local], and the server's, [remote].
 * Field by field, a change on one side only is kept. Where both sides changed one field to two
 * different things, the server's stands, and the phone's is not dropped: it is written into the
 * notes under the server's, as [said] words it, so the reader sees both and chooses.
 */
fun mergeFields(
    base: Fields,
    local: Fields,
    remote: Fields,
    said: (FieldName, String) -> String = { f, v -> "This phone had ${f.name.lowercase()}: $v" },
    dueWords: (Due?) -> String = { it?.key ?: "none" },
): Fields {
    val kept = mutableListOf<String>()
    fun <T> pick(b: T, l: T, r: T, same: (T, T) -> Boolean, field: FieldName, words: (T) -> String): T = when {
        same(l, b) -> r
        same(r, b) -> l
        same(l, r) -> r
        else -> {
            kept += said(field, words(l))
            r
        }
    }
    val summary = pick(base.summary, local.summary, remote.summary, { a, b -> a == b }, FieldName.SUMMARY) { it }
    val due = pick(base.due, local.due, remote.due, { a, b -> a?.key == b?.key }, FieldName.DUE, dueWords)
    val priority = pick(base.priority, local.priority, remote.priority, { a, b -> a == b }, FieldName.PRIORITY) { it.toString() }
    var description = when {
        local.description == base.description -> remote.description
        remote.description == base.description -> local.description
        local.description == remote.description -> remote.description
        else -> {
            kept.add(0, said(FieldName.DESCRIPTION, local.description))
            remote.description
        }
    }
    if (kept.isNotEmpty()) {
        description = (listOf(description).filter { it.isNotEmpty() } + kept).joinToString("\n\n")
    }
    val done = if (local.done == base.done) remote.done else local.done
    return Fields(summary, description, due, priority, done)
}

// ---------------------------------------------------------------- repeating tasks

private val SIMPLE_PARTS = setOf("FREQ", "INTERVAL", "UNTIL", "WKST")

/**
 * A repeating task, ticked: the file with its DUE (and DTSTART, if it has one) moved on to the
 * next time, still open, as Tasks.org and OpenTasks do. Only for the plain rules, every n days,
 * weeks, months or years, perhaps until a day; anything else (a count, "the second Tuesday")
 * returns null, and the tick finishes the task for good, which the task's page says. Also null
 * when the next time is past the rule's end, since then it is finished.
 */
fun nextOccurrence(text: String): String? {
    val ical = ICal.parse(text)
    val props = ical.props()
    val rule = props.firstOrNull { it.name == "RRULE" }?.value ?: return null
    val parts = rule.split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].uppercase() to it[1] } }.toMap()
    if (!parts.keys.all { it in SIMPLE_PARTS }) return null
    val interval = parts["INTERVAL"]?.toLongOrNull()?.takeIf { it > 0 } ?: 1L
    val step: (LocalDateTime) -> LocalDateTime = when (parts["FREQ"]?.uppercase()) {
        "DAILY" -> { t -> t.plusDays(interval) }
        "WEEKLY" -> { t -> t.plusWeeks(interval) }
        "MONTHLY" -> { t -> t.plusMonths(interval) }
        "YEARLY" -> { t -> t.plusYears(interval) }
        else -> return null
    }
    val dueProp = props.firstOrNull { it.name == "DUE" } ?: return null
    parseDue(dueProp) ?: return null
    val nextDue = moved(dueProp, step) ?: return null
    parts["UNTIL"]?.let { until ->
        val end = parseDue(Prop("UNTIL", emptyMap(), until)) ?: return null
        val next = parseDue(nextDue) ?: return null
        if (next.day() > end.day()) return null
    }
    ical.set("DUE", line(nextDue))
    props.firstOrNull { it.name == "DTSTART" }?.let { start -> moved(start, step)?.let { ical.set("DTSTART", line(it)) } }
    return ical.toString()
}

/** [prop] with its date or time moved by [step], in the same form it was written in. */
private fun moved(prop: Prop, step: (LocalDateTime) -> LocalDateTime): Prop? {
    val value = prop.value.trim()
    val next = runCatching {
        when {
            prop.param("VALUE")?.uppercase() == "DATE" || value.length == 8 ->
                DATE.format(step(LocalDate.parse(value.take(8), DATE).atStartOfDay()).toLocalDate())
            value.endsWith("Z") || value.endsWith("z") ->
                DATE_TIME.format(step(LocalDateTime.parse(value.dropLast(1), DATE_TIME))) + "Z"
            else -> DATE_TIME.format(step(LocalDateTime.parse(value, DATE_TIME)))
        }
    }.getOrNull() ?: return null
    return prop.copy(value = next)
}

/** A property written back out, its parameters as they were. */
private fun line(prop: Prop): String {
    val params = prop.params.entries.joinToString("") { (k, v) ->
        ";" + k + "=" + if (v.any { it == ':' || it == ';' || it == ',' }) "\"$v\"" else v
    }
    return prop.name + params + ":" + prop.value
}
