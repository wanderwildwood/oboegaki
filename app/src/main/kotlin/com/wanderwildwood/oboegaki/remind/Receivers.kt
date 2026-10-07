package com.wanderwildwood.oboegaki.remind

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread

/** Runs [work] off the main thread, keeping the broadcast alive until it is done. */
private fun BroadcastReceiver.async(work: () -> Unit) {
    val pending = goAsync()
    thread(name = "reminders") {
        try {
            runCatching(work).onFailure { Log.w("oboegaki", "reminders: $it") }
        } finally {
            pending.finish()
        }
    }
}

/** The one alarm going off: read the reminders again, ring what is due, set the next. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        async { Reminders.sync(context) }
    }

    companion object {
        const val ACTION = "com.wanderwildwood.oboegaki.REMINDER_ALARM"
    }
}

/** Done and Snooze, pressed on a notification, from the lock screen or anywhere. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, -1)
        if (id < 0) return
        async {
            when (intent.action) {
                DONE -> Reminders.done(context, id)
                SNOOZE -> Reminders.snooze(context, id)
            }
        }
    }

    companion object {
        const val DONE = "com.wanderwildwood.oboegaki.REMINDER_DONE"
        const val SNOOZE = "com.wanderwildwood.oboegaki.REMINDER_SNOOZE"
        const val EXTRA_ID = "id"
    }
}

/**
 * Everything that clears or moves the alarm: the phone starting up, this app being updated,
 * the clock or the time zone being changed, the permission for exact alarms being given or
 * taken away. Each one sets it again from the notes.
 */
class SystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> async { Reminders.sync(context) }
        }
    }
}
