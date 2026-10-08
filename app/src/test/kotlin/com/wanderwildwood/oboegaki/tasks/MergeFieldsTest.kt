package com.wanderwildwood.oboegaki.tasks

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class MergeFieldsTest {

    private val base = Fields("Milk", "two litres", null, 0, false)

    @Test
    fun aFieldChangedOnOneSideTakesThatSide() {
        val local = base.copy(done = true)
        val remote = base.copy(summary = "Oat milk", priority = 1)
        assertEquals(Fields("Oat milk", "two litres", null, 1, true), mergeFields(base, local, remote))
    }

    @Test
    fun bothChangedTheSameWayIsNoConflict() {
        val both = base.copy(summary = "Milk!")
        assertEquals(both, mergeFields(base, both, both))
    }

    @Test
    fun aTrueConflictKeepsTheServersAndWritesThePhonesIntoTheNotes() {
        val local = base.copy(summary = "Whole milk", due = Due.Day(LocalDate.of(2026, 10, 9)))
        val remote = base.copy(summary = "Oat milk", due = Due.Day(LocalDate.of(2026, 10, 10)))
        val merged = mergeFields(base, local, remote, said = { f, v -> "phone ${f.name}: $v" }, dueWords = { it.toString() })
        assertEquals("Oat milk", merged.summary)
        assertEquals(remote.due, merged.due)
        assertEquals("two litres\n\nphone SUMMARY: Whole milk\n\nphone DUE: Day(date=2026-10-09)", merged.description)
    }

    @Test
    fun conflictingNotesKeepBoth() {
        val merged = mergeFields(base, base.copy(description = "from the phone"), base.copy(description = "from the web"), said = { _, v -> "phone: $v" })
        assertEquals("from the web\n\nphone: from the phone", merged.description)
    }
}
