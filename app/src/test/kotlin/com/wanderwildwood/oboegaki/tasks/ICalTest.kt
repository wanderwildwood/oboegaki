package com.wanderwildwood.oboegaki.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class ICalTest {

    /** A task as another client writes it: a subtask link, categories, an alarm, X- props, a folded line. */
    private val thunderbird = listOf(
        "BEGIN:VCALENDAR",
        "VERSION:2.0",
        "PRODID:-//Mozilla.org/NONSGML Mozilla Calendar V1.1//EN",
        "BEGIN:VTIMEZONE",
        "TZID:Europe/Berlin",
        "BEGIN:STANDARD",
        "DTSTART:19701025T030000",
        "TZOFFSETFROM:+0200",
        "TZOFFSETTO:+0100",
        "END:STANDARD",
        "END:VTIMEZONE",
        "BEGIN:VTODO",
        "UID:3f1c-ada",
        "CREATED:20261001T080000Z",
        "LAST-MODIFIED:20261001T080000Z",
        "DTSTAMP:20261001T080000Z",
        "SUMMARY:Buy seed potatoes\\, early",
        "DESCRIPTION:Ask Tomas which ones\\nand how many",
        "DUE;TZID=Europe/Berlin:20261009T170000",
        "PRIORITY:5",
        "STATUS:NEEDS-ACTION",
        "RELATED-TO;RELTYPE=PARENT:parent-uid-1",
        "CATEGORIES:Garden,Spring",
        "X-APPLE-SORT-ORDER:12",
        "X-MOZ-GENERATION:3",
        "X-LONG:this line is long enough that the client that wrote it folded it acros",
        " s two lines",
        "BEGIN:VALARM",
        "ACTION:DISPLAY",
        "DESCRIPTION:Alarm words",
        "TRIGGER;VALUE=DURATION:-PT15M",
        "END:VALARM",
        "END:VTODO",
        "END:VCALENDAR",
        "",
    ).joinToString("\r\n")

    @Test
    fun parsingAndWritingBackChangesNothing() {
        assertEquals(thunderbird, ICal.parse(thunderbird).toString())
    }

    @Test
    fun readsTheFieldsAndOnlyTheTasksOwn() {
        val t = readTask(thunderbird)!!
        assertEquals("3f1c-ada", t.uid)
        assertEquals("Buy seed potatoes, early", t.fields.summary)
        // The alarm's DESCRIPTION is the alarm's, not the task's.
        assertEquals("Ask Tomas which ones\nand how many", t.fields.description)
        assertEquals(Due.At(LocalDateTime.of(2026, 10, 9, 17, 0), ZoneId.of("Europe/Berlin")), t.fields.due)
        assertEquals(5, t.fields.priority)
        assertFalse(t.fields.done)
    }

    @Test
    fun anEditKeepsEveryUnknownLineByteForByte() {
        val from = readTask(thunderbird)!!.fields
        val to = from.copy(done = true, summary = "Buy seed potatoes")
        val out = applyFields(thunderbird, from, to, Instant.parse("2026-10-07T12:00:00Z"))
        val changed = setOf("SUMMARY", "STATUS", "COMPLETED", "PERCENT-COMPLETE", "LAST-MODIFIED", "DTSTAMP")
        fun kept(text: String) = text.split("\r\n").filter { line -> changed.none { line.startsWith("$it:") || line.startsWith("$it;") } }
        assertEquals(kept(thunderbird), kept(out))
        val back = readTask(out)!!
        assertTrue(back.fields.done)
        assertEquals("Buy seed potatoes", back.fields.summary)
        assertTrue(out.contains("COMPLETED:20261007T120000Z\r\n"))
        assertTrue(out.contains("LAST-MODIFIED:20261007T120000Z\r\n"))
        // The alarm is still inside the task, after the new lines.
        assertTrue(out.indexOf("END:VALARM") < out.indexOf("END:VTODO"))
    }

    @Test
    fun nothingChangedIsTheSameFile() {
        val f = readTask(thunderbird)!!.fields
        assertEquals(thunderbird, applyFields(thunderbird, f, f, Instant.now()))
    }

    @Test
    fun untickingTakesCompletedOff() {
        val from = readTask(thunderbird)!!.fields
        val done = applyFields(thunderbird, from, from.copy(done = true), Instant.EPOCH)
        val undone = applyFields(done, from.copy(done = true), from, Instant.EPOCH)
        assertFalse(undone.contains("COMPLETED:"))
        assertTrue(undone.contains("STATUS:NEEDS-ACTION"))
        assertFalse(readTask(undone)!!.fields.done)
    }

    @Test
    fun aNewTaskReadsBackAsWritten() {
        val f = Fields("Call Ada; then, Tomas", "line one\nline two", Due.Day(LocalDate.of(2026, 10, 9)), 1)
        val text = newTask("u-1", f, Instant.EPOCH)
        assertEquals(Task("u-1", f), readTask(text))
        assertTrue(text.contains("SUMMARY:Call Ada\\; then\\, Tomas\r\n"))
        assertTrue(text.contains("DUE;VALUE=DATE:20261009\r\n"))
    }

    @Test
    fun longLinesAreFoldedAndReadBack() {
        val long = "ü".repeat(60)
        val text = newTask("u-2", Fields(long), Instant.EPOCH)
        for (line in text.split("\r\n")) assertTrue(line.toByteArray().size <= 75)
        assertEquals(long, readTask(text)!!.fields.summary)
    }

    @Test
    fun anInstanceOverrideIsNotTheTask() {
        val text = "BEGIN:VCALENDAR\r\nBEGIN:VTODO\r\nUID:r\r\nRECURRENCE-ID:20261008T090000Z\r\nSUMMARY:one time\r\nEND:VTODO\r\n" +
            "BEGIN:VTODO\r\nUID:r\r\nSUMMARY:every time\r\nRRULE:FREQ=DAILY\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"
        val t = readTask(text)!!
        assertEquals("every time", t.fields.summary)
        assertEquals("FREQ=DAILY", t.repeats)
    }

    @Test
    fun noTaskInAnEvent() {
        assertNull(readTask("BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:e\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"))
    }

    // ---------------------------------------------------------------- due dates and zones

    private fun due(line: String) = parseDue(parseProp(line)!!)

    @Test
    fun dueInEveryForm() {
        assertEquals(Due.Day(LocalDate.of(2026, 10, 9)), due("DUE;VALUE=DATE:20261009"))
        assertEquals(Due.At(LocalDateTime.of(2026, 10, 9, 15, 0), ZoneOffset.UTC), due("DUE:20261009T150000Z"))
        assertEquals(Due.At(LocalDateTime.of(2026, 10, 9, 17, 0), null), due("DUE:20261009T170000"))
        assertEquals(Due.At(LocalDateTime.of(2026, 10, 9, 17, 0), ZoneId.of("America/New_York")), due("DUE;TZID=America/New_York:20261009T170000"))
        // Thunderbird's old prefixed names, and a name nobody knows, which reads as floating.
        assertEquals(ZoneId.of("Europe/Berlin"), (due("DUE;TZID=/mozilla.org/20050126_1/Europe/Berlin:20261009T170000") as Due.At).zone)
        assertNull((due("DUE;TZID=W. Europe Standard Time:20261009T170000") as Due.At).zone)
    }

    @Test
    fun dueOnThePhonesClock() {
        val ny = ZoneId.of("America/New_York")
        // 15:00 UTC is 11:00 in New York in October (EDT).
        assertEquals(LocalDateTime.of(2026, 10, 9, 11, 0), due("DUE:20261009T150000Z")!!.local(ny))
        assertEquals(LocalDateTime.of(2026, 10, 9, 11, 0), due("DUE;TZID=Europe/Berlin:20261009T170000")!!.local(ny))
        // Floating is whatever the clock says where it is read.
        assertEquals(LocalDateTime.of(2026, 10, 9, 17, 0), due("DUE:20261009T170000")!!.local(ny))
        // A day has no moment to ring at.
        assertNull(due("DUE;VALUE=DATE:20261009")!!.local(ny))
        assertEquals(LocalDate.of(2026, 10, 9), due("DUE;VALUE=DATE:20261009")!!.day(ny))
    }

    @Test
    fun aMomentIsWrittenInUtcAndTheSameMomentIsTheSameDue() {
        val berlin = Due.At(LocalDateTime.of(2026, 10, 9, 17, 0), ZoneId.of("Europe/Berlin"))
        assertEquals("DUE:20261009T150000Z", dueLine(berlin))
        assertEquals(berlin.key, due(dueLine(berlin))!!.key)
        assertEquals("DUE:20261009T170000", dueLine(Due.At(LocalDateTime.of(2026, 10, 9, 17, 0), null)))
    }

    // ---------------------------------------------------------------- repeating

    private fun repeating(rule: String, due: String = "DUE;TZID=America/New_York:20261009T090000", start: String? = "DTSTART;TZID=America/New_York:20261009T080000") =
        listOfNotNull("BEGIN:VCALENDAR", "BEGIN:VTODO", "UID:rep", "SUMMARY:Water the llamas", start, due, "RRULE:$rule", "X-KEEP:me", "END:VTODO", "END:VCALENDAR", "")
            .joinToString("\r\n")

    @Test
    fun aPlainRuleMovesOnOneStep() {
        val next = nextOccurrence(repeating("FREQ=WEEKLY;INTERVAL=2"))!!
        assertTrue(next.contains("DUE;TZID=America/New_York:20261023T090000\r\n"))
        assertTrue(next.contains("DTSTART;TZID=America/New_York:20261023T080000\r\n"))
        assertTrue(next.contains("X-KEEP:me\r\n"))
        assertFalse(readTask(next)!!.fields.done)
        assertTrue(nextOccurrence(repeating("FREQ=DAILY", due = "DUE;VALUE=DATE:20261031", start = null))!!.contains("DUE;VALUE=DATE:20261101"))
        assertTrue(nextOccurrence(repeating("FREQ=MONTHLY", due = "DUE:20261009T150000Z", start = null))!!.contains("DUE:20261109T150000Z"))
    }

    @Test
    fun anythingElseIsFinishedInstead() {
        assertNull(nextOccurrence(repeating("FREQ=WEEKLY;COUNT=4")))
        assertNull(nextOccurrence(repeating("FREQ=MONTHLY;BYDAY=2TU")))
        // Past the rule's end.
        assertNull(nextOccurrence(repeating("FREQ=WEEKLY;UNTIL=20261012T000000Z")))
        // No due date to move.
        assertNull(nextOccurrence(repeating("FREQ=DAILY", due = "X-NONE:1", start = null)))
    }
}
