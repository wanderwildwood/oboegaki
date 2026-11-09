package com.wanderwildwood.oboegaki.glance

import android.content.Context
import com.wanderwildwood.oboegaki.R
import com.wanderwildwood.oboegaki.notes.Line as NoteLine
import com.wanderwildwood.oboegaki.notes.Notes
import com.wanderwildwood.oboegaki.notes.lines
import com.wanderwildwood.oboegaki.remind.Times
import com.wanderwildwood.oboegaki.tasks.Tasks
import com.wanderwildwood.oboegaki.today.gather
import java.time.LocalDate

/**
 * For Glance's lock-screen panel: first Today, what is due today or overdue from the tasks and
 * the notes (the first few, and how many more), then the pinned notes, each by its title, and a
 * list with how much of it is left to do, so the shopping list can be read in the shop without
 * unlocking the phone. Nothing when nothing is due or pinned.
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
        return today(context, shelf) + pinned(context, shelf)
    }

    private fun today(context: Context, shelf: com.wanderwildwood.oboegaki.notes.Shelf): List<GlanceProvider.Line> {
        Tasks.init(context)
        Tasks.reload()
        val due = gather(shelf.list(), Tasks.shown.value, LocalDate.now())
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
        return if (more > 0) lines + GlanceProvider.Line(text = context.resources.getQuantityString(R.plurals.lock_today_more, more, more)) else lines
    }

    private fun pinned(context: Context, shelf: com.wanderwildwood.oboegaki.notes.Shelf): List<GlanceProvider.Line> {
        val pinned = Notes.pinnedNow()
        if (pinned.isEmpty()) return emptyList()
        val heading = context.getString(R.string.lock_heading)
        return pinned.mapIndexedNotNull { i, path ->
            val text = shelf.read(path) ?: return@mapIndexedNotNull null
            val title = path.substringAfterLast('/').substringBeforeLast('.')
            val tasks = lines(text).filterIsInstance<NoteLine.Task>()
            val left = tasks.count { !it.done }
            val shown = when {
                tasks.isEmpty() -> title
                left == 0 -> context.getString(R.string.lock_all_done, title)
                else -> context.resources.getQuantityString(R.plurals.lock_left, left, title, left)
            }
            GlanceProvider.Line(text = shown, heading = if (i == 0) heading else null)
        }
    }
}
