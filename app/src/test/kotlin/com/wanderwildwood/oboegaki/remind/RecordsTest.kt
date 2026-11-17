package com.wanderwildwood.oboegaki.remind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordsTest {

    private var n = 1
    private val next = { n++ }

    @Test
    fun aReminderSetHereIsRecorded() {
        val out = afterEdit(emptyList(), "Groceries.md", "Groceries.md", "- [ ] milk\n", "- [ ] milk ⏰ 2026-10-08 17:00\n", next)
        assertEquals(listOf(Record(1, "Groceries.md", "milk", "2026-10-08 17:00")), out)
    }

    @Test
    fun aReminderTakenOffHereIsDropped() {
        val had = listOf(Record(4, "Groceries.md", "milk", "2026-10-08 17:00"))
        val out = afterEdit(had, "Groceries.md", "Groceries.md", "- [ ] milk ⏰ 2026-10-08 17:00\n", "- [ ] milk\n", next)
        assertTrue(out.isEmpty())
    }

    @Test
    fun editingAnItemSomeoneElseSetAReminderOnDoesNotMakeItRingHere() {
        val out = afterEdit(emptyList(), "Groceries.md", "Groceries.md", "- [ ] milk ⏰ 2026-10-08 17:00\n", "- [ ] oat milk ⏰ 2026-10-08 17:00\n", next)
        assertTrue(out.isEmpty())
        // While an item of this phone's, edited, stays this phone's.
        val ours = listOf(Record(2, "Groceries.md", "milk", "2026-10-08 17:00"))
        val kept = afterEdit(ours, "Groceries.md", "Groceries.md", "- [ ] milk ⏰ 2026-10-08 17:00\n", "- [ ] oat milk ⏰ 2026-10-08 17:00\n", next)
        assertEquals(listOf("oat milk"), kept.map { it.key })
    }

    @Test
    fun aTickChangesNoRecordAndARenameMovesThem() {
        val had = listOf(Record(3, "Groceries.md", "milk", "2026-10-08 17:00"), Record(5, "Other.md", "", "2026-10-09 09:00"))
        val ticked = afterEdit(had, "Groceries.md", "Groceries.md", "- [ ] milk ⏰ 2026-10-08 17:00\n", "- [x] milk ⏰ 2026-10-08 17:00\n", next)
        assertEquals(had, ticked)
        val renamed = afterEdit(had, "Groceries.md", "Shopping.md", "- [ ] milk ⏰ 2026-10-08 17:00\n", "- [ ] milk ⏰ 2026-10-08 17:00\n", next)
        assertEquals(listOf("Shopping.md", "Other.md"), renamed.map { it.path })
    }

    private val milk = Record(4, "Groceries.md", "milk", "2026-10-08 17:00")
    private fun note(path: String, text: String) = com.wanderwildwood.oboegaki.notes.note(path, text, 0L)

    @Test
    fun aReminderInItsNoteIsFound() {
        val seen = look(milk, listOf(milk), "- [ ] milk ⏰ 2026-10-08 17:00\n", false) { emptyList() }
        assertEquals(milk, (seen as Seen.Found).record)
        assertEquals("milk", seen.reminder.key)
    }

    @Test
    fun aReminderIsKeptWhileItsNoteCannotBeRead() {
        // Reading the note went wrong: a folder offline, its leave taken back.
        assertEquals(Seen.Unknown, look(milk, listOf(milk), null, true) { listOf(note("Other.md", "words")) })
        // The notes could not be listed at all.
        assertEquals(Seen.Unknown, look(milk, listOf(milk), null, false) { null })
        // A folder not yet mounted, or a server's copy being fetched afresh, lists as empty.
        assertEquals(Seen.Unknown, look(milk, listOf(milk), null, false) { emptyList() })
    }

    @Test
    fun aReminderIsLetGoOnlyWhenTheNotesWereReadAndItIsNotThere() {
        // Its note is gone, and the others were read.
        assertEquals(Seen.Gone, look(milk, listOf(milk), null, false) { listOf(note("Other.md", "words")) })
        // Taken off its note.
        val off = "- [ ] milk\n"
        assertEquals(Seen.Gone, look(milk, listOf(milk), off, false) { listOf(note("Groceries.md", off)) })
    }

    @Test
    fun aReminderFollowsItsNoteRenamedElsewhere() {
        val seen = look(milk, listOf(milk), null, false) { listOf(note("Lists/Shopping.md", "- [ ] milk ⏰ 2026-10-08 17:00\n")) }
        assertEquals("Lists/Shopping.md", (seen as Seen.Found).record.path)
    }

    @Test
    fun recordsRoundTrip() {
        val had = listOf(Record(3, "Lists/Groceries.md", "milk\tand honey", "2026-10-08 17:00", 123L, 456L))
        assertEquals(listOf(had[0].copy(key = "milk and honey")), decode(encode(had)))
    }
}
