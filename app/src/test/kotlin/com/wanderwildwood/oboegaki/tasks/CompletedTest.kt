package com.wanderwildwood.oboegaki.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class CompletedTest {

    private fun todo(vararg lines: String) = (
        listOf("BEGIN:VCALENDAR", "VERSION:2.0", "BEGIN:VTODO", "UID:t-1", "SUMMARY:Buy oats") +
            lines + listOf("END:VTODO", "END:VCALENDAR")
        ).joinToString("\r\n")

    @Test
    fun aDoneTaskKnowsWhenItWasFinished() {
        val task = readTask(todo("STATUS:COMPLETED", "COMPLETED:20261108T153000Z"))!!
        assertEquals(Instant.parse("2026-11-08T15:30:00Z"), task.completed)
    }

    @Test
    fun anOpenTaskHasNoFinishTime() {
        // A reopened task some clients leave COMPLETED on.
        assertNull(readTask(todo("STATUS:NEEDS-ACTION", "COMPLETED:20261108T153000Z"))!!.completed)
    }

    @Test
    fun aDoneTaskWithoutATimeSortsLast() {
        val task = readTask(todo("STATUS:COMPLETED"))!!
        assertNull(task.completed)
    }

    @Test
    fun tickingWritesATimeThatReadsBack() {
        val now = Instant.parse("2026-11-08T21:04:05Z")
        val text = newTask("t-2", Fields("Call Tomas Reyes"), now)
        val done = applyFields(text, Fields("Call Tomas Reyes"), Fields("Call Tomas Reyes", done = true), now)
        assertEquals(now, readTask(done)!!.completed)
    }
}
