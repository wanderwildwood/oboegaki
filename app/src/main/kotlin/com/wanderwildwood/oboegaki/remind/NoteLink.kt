package com.wanderwildwood.oboegaki.remind

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract

/**
 * The link from a calendar event back to the note it was made from, in Android's own place for
 * one: the event's `CUSTOM_APP_PACKAGE` and `CUSTOM_APP_URI`, put on *Remind me…*'s calendar
 * request. Datebook reads them, shows an "Open in Memo" row and opens the note with
 * `CalendarContract.ACTION_HANDLE_CUSTOM_EVENT`. A calendar that does not know them ignores them.
 */
object NoteLink {

    private const val PREFIX = "oboegaki://note/"

    fun uri(path: String): String = PREFIX + Uri.encode(path)

    /** Puts the link to the note at [path] on an "add to calendar" request. */
    fun put(context: Context, intent: Intent, path: String): Intent = intent
        .putExtra(CalendarContract.Events.CUSTOM_APP_PACKAGE, context.packageName)
        .putExtra(CalendarContract.Events.CUSTOM_APP_URI, uri(path))

    /**
     * The note a calendar event asks for, or null when [intent] is no such request. Only a path
     * inside the notes: nothing absolute, nothing climbing out, and never a task's record.
     */
    fun path(intent: Intent?): String? {
        if (intent?.action != CalendarContract.ACTION_HANDLE_CUSTOM_EVENT) return null
        val uri = intent.getStringExtra(CalendarContract.EXTRA_CUSTOM_APP_URI) ?: return null
        if (!uri.startsWith(PREFIX)) return null
        return safe(Uri.decode(uri.removePrefix(PREFIX)))
    }

    /**
     * [path] when it is a note's place inside the notes, else null: nothing absolute, nothing
     * climbing out, and a task's record only when [task] allows it (a reminder of a task).
     */
    fun safe(path: String?, task: Boolean = false): String? {
        if (path.isNullOrEmpty() || path.startsWith("/") || (!task && path.startsWith(TASK))) return null
        if (path.split('/').any { it == ".." || it.isEmpty() }) return null
        return path
    }
}
