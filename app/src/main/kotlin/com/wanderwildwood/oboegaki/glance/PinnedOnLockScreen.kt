package com.wanderwildwood.oboegaki.glance

import android.content.Context
import com.wanderwildwood.oboegaki.R
import com.wanderwildwood.oboegaki.notes.Line as NoteLine
import com.wanderwildwood.oboegaki.notes.Notes
import com.wanderwildwood.oboegaki.notes.Shelf
import com.wanderwildwood.oboegaki.notes.lines
import com.wanderwildwood.oboegaki.notes.withoutReminder
import com.wanderwildwood.oboegaki.remind.Times
import com.wanderwildwood.oboegaki.tasks.Tasks
import com.wanderwildwood.oboegaki.today.Entry
import com.wanderwildwood.oboegaki.today.gather
import java.time.LocalDate

/**
 * For Glance's lock-screen panel: first Today, what is due today or overdue from the tasks and
 * the notes (the first few, and how many more), then the pinned notes, each by its title with its
 * open items under it (the first few, and how many more), so the shopping list can be read in the
 * shop without unlocking the phone and a pinned note can hold standing reminders that have no
 * date. Nothing when nothing is due or pinned.
 */
class PinnedOnLockScreen : GlanceProvider() {

    private companion object {
        /** Today's rows on the lock screen before "and n more". */
        const val TODAY_ROWS = 4
    }

    override fun enabled(context: Context): Boolean {
        Notes.init(context)
        return Notes.preferences.lockScreen
    }

    override fun lines(context: Context): List<GlanceProvider.Line> {
        Notes.init(context)
        val shelf = Notes.shelf() ?: return emptyList()
        Tasks.init(context)
        Tasks.reload()
        val due = gather(shelf.list(), Tasks.shown.value, LocalDate.now())
        return today(context, due) + pinned(context, shelf, due)
    }

    private fun today(context: Context, due: List<Entry>): List<GlanceProvider.Line> {
        if (due.isEmpty()) return emptyList()
        val heading = context.getString(R.string.lock_today)
        val lines = due.take(TODAY_ROWS).mapIndexed { i, e ->
            GlanceProvider.Line(
                text = e.title,
                lead = e.at?.takeIf { !e.overdue }?.let { Times.time(context, it) },
                bold = e.overdue,
                heading = if (i == 0) heading else null,
            )
        }
        val more = due.size - lines.size
        // Marked with how many it stands for, so Glance's own "+N more" counts it as that many.
        return if (more > 0) lines + GlanceProvider.Line(text = context.resources.getQuantityString(R.plurals.lock_today_more, more, more), more = more) else lines
    }

    private fun pinned(context: Context, shelf: Shelf, due: List<Entry>): List<GlanceProvider.Line> {
        val notes = Notes.pinnedNow().mapNotNull { path -> shelf.read(path)?.let { PinnedNote(path, it) } }
        // An item already under Today is left there, not shown twice.
        val underToday = due.filterIsInstance<Entry.OfNote>().filter { it.isItem }.map { it.path to it.reminder.line }.toSet()
        val res = context.resources
        val words = LockWords(
            heading = context.getString(R.string.lock_heading),
            allDone = { title -> context.getString(R.string.lock_all_done, title) },
            left = { title, n -> res.getQuantityString(R.plurals.lock_left, n, title, n) },
            more = { n -> res.getQuantityString(R.plurals.lock_items_more, n, n) },
        )
        return pinnedLines(notes, underToday, words)
    }
}

/** A pinned note as the lock screen reads it: where it is, and its words. */
internal data class PinnedNote(val path: String, val text: String)

/** The words the pinned part is made of, from strings.xml; plain ones in tests. */
internal class LockWords(
    val heading: String,
    /** "Groceries · all done" */
    val allDone: (title: String) -> String,
    /** "Groceries · 5 to do", for a note whose items there is no more room to list. */
    val left: (title: String, n: Int) -> String,
    /** "and 2 more", under a note's first items. */
    val more: (n: Int) -> String,
)

/** A pinned note's open items listed under its title before "and n more". */
internal const val PINNED_ITEM_ROWS = 3

/**
 * Open items listed under all the pinned notes together. Past it, a note is one line again, its
 * title and how many are left, so many pinned notes never push the rest off the panel. Glance
 * still cuts whatever does not fit its own height, with its own "+N more".
 */
internal const val PINNED_ITEMS_IN_ALL = 9

/** The lead that sets an item in under its note's title: Glance gives a lead a column of its own. */
internal const val ITEM_LEAD = "•"

/**
 * The pinned part of the lock screen. Each note is its title (with "· all done" when every item
 * is ticked), then its first [PINNED_ITEM_ROWS] open items, each set in by [ITEM_LEAD], then
 * "and n more" when it has more. An item in [underToday] (path, line) is due today or overdue
 * and already shown under Today, so it is left out here and not counted. Once
 * [PINNED_ITEMS_IN_ALL] items are listed, the notes after are their title and how many are left.
 */
internal fun pinnedLines(notes: List<PinnedNote>, underToday: Set<Pair<String, Int>>, words: LockWords): List<GlanceProvider.Line> {
    val out = mutableListOf<GlanceProvider.Line>()
    var room = PINNED_ITEMS_IN_ALL
    for (note in notes) {
        val title = note.path.substringAfterLast('/').substringBeforeLast('.')
        val tasks = lines(note.text).filterIsInstance<NoteLine.Task>()
        val open = tasks.filter { !it.done && (note.path to it.index) !in underToday }
            .map { withoutReminder(it.text) }
            .filter { it.isNotBlank() }
        val shown = minOf(open.size, PINNED_ITEM_ROWS, room)
        room -= shown
        val head = when {
            tasks.isEmpty() -> title
            tasks.none { !it.done } -> words.allDone(title)
            shown == 0 && open.isNotEmpty() -> words.left(title, open.size)
            else -> title
        }
        out += GlanceProvider.Line(text = head, heading = if (out.isEmpty()) words.heading else null)
        open.take(shown).forEach { out += GlanceProvider.Line(text = it, lead = ITEM_LEAD) }
        val more = if (shown > 0) open.size - shown else 0
        // Marked with how many it stands for, so Glance's own "+N more" counts it as that many.
        if (more > 0) out += GlanceProvider.Line(text = words.more(more), more = more)
    }
    return out
}
