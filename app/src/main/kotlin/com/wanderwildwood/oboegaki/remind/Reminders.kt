package com.wanderwildwood.oboegaki.remind

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.ApplicationExitInfo
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.wanderwildwood.oboegaki.MainActivity
import com.wanderwildwood.oboegaki.notes.Notes
import com.wanderwildwood.oboegaki.notes.Reminder
import com.wanderwildwood.oboegaki.notes.findReminder
import com.wanderwildwood.oboegaki.notes.preview
import com.wanderwildwood.oboegaki.notes.reminderTime
import com.wanderwildwood.oboegaki.notes.reminders
import com.wanderwildwood.oboegaki.notes.toggle
import com.wanderwildwood.oboegaki.notes.withNoteReminder
import com.wanderwildwood.oboegaki.notes.reminderAt
import com.wanderwildwood.oboegaki.tasks.Tasks
import com.wanderwildwood.oboegaki.tasks.local
import java.time.LocalDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.ZoneId
import kotlin.concurrent.thread

/**
 * Everything that makes a reminder ring: the engine Medicine (fukuyaku) uses, fitted to notes.
 *
 * The note's text says when; the records here say which of those reminders were set on this
 * phone, and whether each has rung. There is only ever one alarm, an alarm clock set for the
 * next one due. When it goes off, or the app is opened, or the phone starts, or the clock or the
 * time zone changes, [sync] reads each of this phone's reminders from its note again, rings what
 * has come due, and sets the alarm for the next. A reminder that could not ring on time,
 * because the app was stopped or the phone was off, rings as soon as it can and says it is late.
 */
object Reminders {

    private const val TAG = "oboegaki"
    private val lock = Any()

    const val SNOOZE_MINUTES = 10

    /** Later than this, a reminder says it is late, and why if that is known. */
    private const val LATE_MS = 2 * 60_000L

    /** A reminder that rang this long ago, and was not snoozed, is let go of here. */
    private const val KEEP_MS = 30L * 24 * 3_600_000L

    private val _version = MutableStateFlow(0)
    /** Moves on whenever the records change, so the screens can look again. */
    val version: StateFlow<Int> = _version

    private fun file(context: Context) = File(context.filesDir, "reminders")

    fun load(context: Context): List<Record> =
        runCatching { decode(file(context).readText()) }.getOrDefault(emptyList())

    private fun store(context: Context, records: List<Record>) {
        val f = file(context)
        val tmp = File(f.path + ".new")
        tmp.writeText(encode(records))
        tmp.renameTo(f)
        _version.value++
    }

    /** Whether [r], in the note at [path], was set on this phone. */
    fun isHere(context: Context, path: String, r: Reminder): Boolean =
        load(context).any { it.matches(path, r) }

    // ---------------------------------------------------------------- edits on this phone

    /**
     * The note at [from] was edited on this phone, from [before] to [after], and saved as [to].
     * Reminders that appeared were set here; ones that went were taken off. A tick or an untick
     * changes nothing here but what rings, so the alarm is set again.
     */
    fun edited(context: Context, from: String, to: String, before: String, after: String) {
        val changed = synchronized(lock) {
            val old = load(context)
            var n = (old.maxOfOrNull { it.id } ?: 0) + 1
            val new = afterEdit(old, from, to, before, after) { n++ }
            if (new != old) store(context, new)
            new != old || reminders(before).map { Triple(it.key, it.at, it.done) } != reminders(after).map { Triple(it.key, it.at, it.done) }
        }
        if (changed) syncSoon(context)
    }

    /**
     * Set from the reminder dialog: this phone's from now, even when the note already had it,
     * as when one set on another phone is made to ring here too.
     */
    fun claim(context: Context, path: String, key: String, at: String) {
        synchronized(lock) {
            val old = load(context)
            if (old.any { it.path == path && it.key == key && it.at == at }) return@synchronized
            store(context, old + Record((old.maxOfOrNull { it.id } ?: 0) + 1, path, key, at))
        }
        syncSoon(context)
    }

    /**
     * A task's reminder set on this phone: it rings here at the task's due time, wherever the
     * task was made, and follows the due time if it is moved, here or on the server.
     */
    fun claimTask(context: Context, uid: String, title: String, at: LocalDateTime) {
        val when_ = reminderAt(at)
        synchronized(lock) {
            val old = load(context)
            val mine = old.firstOrNull { it.path == TASK + uid }
            val new = when {
                mine == null -> old + Record((old.maxOfOrNull { it.id } ?: 0) + 1, TASK + uid, title, when_)
                mine.at == when_ && mine.key == title -> return@synchronized
                mine.at == when_ -> old.map { if (it.id == mine.id) it.copy(key = title) else it }
                else -> old.map { if (it.id == mine.id) it.copy(key = title, at = when_, rang = 0, snooze = 0) else it }
            }
            store(context, new)
        }
        syncSoon(context)
    }

    /** A task's reminder taken off this phone. */
    fun unclaimTask(context: Context, uid: String) {
        val gone = synchronized(lock) {
            val old = load(context)
            val gone = old.filter { it.path == TASK + uid }
            if (gone.isNotEmpty()) store(context, old - gone.toSet())
            gone
        }
        for (r in gone) Notifier.cancel(context, r.id)
        if (gone.isNotEmpty()) syncSoon(context)
    }

    /** Whether the task with [uid] rings on this phone. */
    fun isTaskHere(context: Context, uid: String): Boolean = load(context).any { it.path == TASK + uid }

    /** Where a reminder is from: its note's name, or for a task, its list's. */
    fun where(context: Context, rec: Record): String {
        if (rec.isTask) {
            Tasks.init(context)
            return runCatching { Tasks.store.find(rec.uid)?.let { Tasks.listName(it.list) } }.getOrNull().orEmpty()
        }
        return rec.path.substringAfterLast('/').substringBeforeLast('.')
    }

    /** A note moved, by archiving or bringing it back: its reminders go with it. */
    fun moved(context: Context, from: String, to: String) = synchronized(lock) {
        val old = load(context)
        if (old.none { it.path == from }) return@synchronized
        store(context, old.map { if (it.path == from) it.copy(path = to) else it })
    }

    fun syncSoon(context: Context) {
        val app = context.applicationContext
        thread(name = "reminders") { runCatching { sync(app) }.onFailure { Log.w(TAG, "reminders: $it") } }
    }

    // ---------------------------------------------------------------- ringing

    /** Reads each reminder from its note, rings what is due, and sets the next alarm. */
    fun sync(context: Context, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        Notes.init(context)
        Tasks.init(context)
        val stop = newStop(context)
        val records = load(context)
        val shelf = Notes.shelf()
        if (records.isEmpty()) {
            schedule(context, null, now)
            return@synchronized
        }
        val zone = ZoneId.systemDefault()
        val texts = HashMap<String, String?>()
        fun text(path: String) = texts.getOrPut(path) { runCatching { shelf?.read(path) }.getOrNull() }
        val everyNote by lazy { runCatching { shelf?.list() }.getOrNull().orEmpty() }

        val out = mutableListOf<Record>()
        var next: Long? = null
        for (r in records) {
            var rec = r
            val done: Boolean
            if (r.isTask) {
                // A task's reminder rings at the task's due time as it is now, wherever it was
                // moved to; a task deleted, or no longer due at a time, rings no more.
                val item = runCatching { Tasks.store.find(r.uid) }.getOrNull()
                val atNow = item?.task?.fields?.due?.local()?.let(::reminderAt)
                if (item == null || atNow == null) {
                    Notifier.cancel(context, r.id)
                    continue
                }
                if (atNow != rec.at) {
                    Notifier.cancel(context, r.id)
                    rec = rec.copy(at = atNow, rang = 0, snooze = 0)
                }
                if (item.task.fields.summary != rec.key) rec = rec.copy(key = item.task.fields.summary)
                done = item.task.fields.done
            } else if (shelf == null) {
                // The notes cannot be read just now: their reminders are kept as they are.
                out += r
                continue
            } else {
                var found = text(r.path)?.let { findReminder(it, r.key, r.at) }
                if (found == null) {
                    // Renamed or moved somewhere else, perhaps on another device: the one note that
                    // has this reminder, if only one does.
                    val elsewhere = everyNote.filter { n -> findReminder(n.text, r.key, r.at) != null }
                    if (elsewhere.size == 1 && records.none { it.path == elsewhere[0].path && it.key == r.key && it.at == r.at }) {
                        rec = rec.copy(path = elsewhere[0].path)
                        found = findReminder(elsewhere[0].text, r.key, r.at)
                    }
                }
                if (found == null) {
                    // Taken off, here or on another device, or the note is gone.
                    Notifier.cancel(context, r.id)
                    continue
                }
                done = found.done
            }
            val at = reminderTime(rec.at)?.atZone(zone)?.toInstant()?.toEpochMilli()
            if (at == null) {
                Notifier.cancel(context, r.id)
                continue
            }
            if (done) {
                // Ticked, here or by someone sharing the list: it does not ring.
                Notifier.cancel(context, r.id)
                if (rec.snooze != 0L) rec = rec.copy(snooze = 0)
                out += rec
                continue
            }
            val due = when {
                rec.snooze > 0 -> rec.snooze
                rec.rang == 0L -> at
                else -> null
            }
            if (due != null && due <= now) {
                val late = now - due > LATE_MS
                Log.i(TAG, "reminder ${rec.id} due $due rings at $now")
                val words = if (rec.key.isEmpty() && !rec.isTask) text(rec.path)?.let(::preview).orEmpty() else ""
                Notifier.ring(context, rec, title(rec), words, due, late, stoppedBetween(context, stop, due, now))
                rec = rec.copy(rang = now, snooze = 0)
            } else if (due != null) {
                next = minOf(next ?: due, due)
            }
            // A task's stays while the task does: its due time may be moved on.
            if (!rec.isTask && rec.rang > 0 && rec.snooze == 0L && now - at > KEEP_MS) continue
            out += rec
        }
        if (out != records) store(context, out)
        schedule(context, next, now)
    }

    /** What a reminder is called: the item's words, or the note's name. */
    fun title(rec: Record): String = if (rec.isTask) rec.key else rec.key.ifEmpty { rec.path.substringAfterLast('/').substringBeforeLast('.') }

    private fun stoppedBetween(context: Context, stop: Long?, due: Long, now: Long): Long? {
        val at = stop ?: Notes.preferences.stoppedAt.takeIf { it > 0 } ?: return null
        return at.takeIf { it in (due - 12 * 3_600_000L)..now }
    }

    /** Sets the one alarm for the next thing due, or clears it when nothing is. */
    private fun schedule(context: Context, wake: Long?, now: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pending = alarm(context)
        if (wake == null) {
            am.cancel(pending)
            return
        }
        val at = maxOf(wake, now + 1_000L)
        if (am.canScheduleExactAlarms()) {
            // An alarm clock is exact, is let through Doze, and wakes the phone to deliver it.
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, open(context)), pending)
            Log.i(TAG, "reminder alarm set for $at")
        } else {
            // Without the permission no exact alarm can be set; this one may come late, and the
            // reminder dialog says so and offers the permission.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            Log.w(TAG, "no exact alarms allowed; inexact alarm set for $at")
        }
    }

    // ---------------------------------------------------------------- answering

    /** Done: an item is ticked; a note's reminder is taken off it. */
    fun done(context: Context, id: Int) {
        val rec = synchronized(lock) { load(context).firstOrNull { it.id == id } }
        Notifier.cancel(context, id)
        _version.value++
        if (rec == null) return
        if (rec.isTask) {
            // The task ticked, here and, with the next sync, on the server.
            Tasks.init(context)
            Tasks.doneFromReminder(rec.uid)
            synchronized(lock) {
                val old = load(context)
                val new = old.map { if (it.id == id) it.copy(snooze = 0) else it }
                if (new != old) store(context, new)
            }
            syncSoon(context)
            return
        }
        Notes.init(context)
        Notes.change(rec.path) { text ->
            val found = findReminder(text, rec.key, rec.at)
            when {
                found == null -> text
                rec.key.isEmpty() -> withNoteReminder(text, null)
                !found.done -> toggle(text, found.line)
                else -> text
            }
        }
        synchronized(lock) {
            val old = load(context)
            // A note's reminder is gone from its text; an item's stays, ticked, and rings no more.
            val new = if (rec.key.isEmpty()) old.filterNot { it.id == id } else old.map { if (it.id == id) it.copy(snooze = 0) else it }
            if (new != old) store(context, new)
        }
        syncSoon(context)
    }

    fun snooze(context: Context, id: Int) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val old = load(context)
            val new = old.map { if (it.id == id) it.copy(snooze = now + SNOOZE_MINUTES * 60_000L) else it }
            if (new != old) store(context, new)
        }
        Notifier.cancel(context, id)
        _version.value++
        syncSoon(context)
    }

    /** Done or Snooze pressed on a screen: answered off the main thread, the note's file and all. */
    fun answer(context: Context, id: Int, done: Boolean) {
        val app = context.applicationContext
        thread(name = "reminders") { runCatching { if (done) done(app, id) else snooze(app, id) } }
    }

    // ---------------------------------------------------------------- stops

    /**
     * Notes the newest stop by the system not yet seen, and returns its time. A stop cancels
     * every alarm the app had set, which is what DuraSpeed does on a Kompakt to apps not on its
     * list, so the DuraSpeed row in Settings comes back.
     */
    private fun newStop(context: Context): Long? {
        val prefs = Notes.preferences
        val am = context.getSystemService(ActivityManager::class.java)
        val stop = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 0) }
            .getOrDefault(emptyList())
            .filter { it.reason == ApplicationExitInfo.REASON_USER_REQUESTED && it.description?.contains("due to from pid") == true }
            .maxOfOrNull { it.timestamp } ?: return null
        if (stop <= prefs.stopSeen) return null
        prefs.stopSeen = stop
        prefs.stoppedAt = stop
        Log.w(TAG, "stopped by the system at $stop; reminders set before then were cancelled")
        if (load(context).isNotEmpty()) prefs.duraSpeedDone = false
        return stop
    }

    // ---------------------------------------------------------------- intents

    private fun alarm(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** What the system's alarm icon opens when it is pressed: the app. */
    private fun open(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
