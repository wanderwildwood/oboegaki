package com.wanderwildwood.oboegaki.glance

import android.content.Context
import com.wanderwildwood.oboegaki.R
import com.wanderwildwood.oboegaki.notes.Line as NoteLine
import com.wanderwildwood.oboegaki.notes.Notes
import com.wanderwildwood.oboegaki.notes.lines

/**
 * The pinned notes, for Glance's lock-screen panel: each by its title, and a list with how much
 * of it is left to do, so the shopping list can be read in the shop without unlocking the phone.
 * Nothing when nothing is pinned.
 */
class PinnedOnLockScreen : GlanceProvider() {

    override fun enabled(context: Context): Boolean {
        Notes.init(context)
        return Notes.preferences.lockScreen
    }

    override fun lines(context: Context): List<GlanceProvider.Line> {
        Notes.init(context)
        val shelf = Notes.shelf() ?: return emptyList()
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
