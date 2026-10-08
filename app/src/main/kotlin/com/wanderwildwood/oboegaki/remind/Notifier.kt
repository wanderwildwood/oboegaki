package com.wanderwildwood.oboegaki.remind

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.wanderwildwood.oboegaki.MainActivity
import com.wanderwildwood.oboegaki.R

/**
 * The notifications: one for each reminder ringing, with Done and Snooze on it, which work from
 * the lock screen without unlocking. Its number is the reminder's own, so ringing again replaces
 * it rather than adding a second. With the screen off, the reminder fills the screen as well.
 */
object Notifier {

    const val CHANNEL = "reminders"

    fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.remind_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.remind_channel_about)
                enableVibration(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            },
        )
    }

    /**
     * Rings for [rec]. [words]: the start of a note whose reminder it is. [late]: it could not
     * ring when it was due, [due]; [stopped] is when the system stopped the app, if that is why.
     */
    fun ring(context: Context, rec: Record, title: String, words: String, due: Long, late: Boolean, stopped: Long?) {
        channel(context)
        val text = listOf(said(context, rec, due, late, stopped), words).filter { it.isNotEmpty() }.joinToString("\n")
        val public = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_reminder)
            .setContentTitle(context.getString(R.string.remind_public_title))
            .setContentText(Times.whenShort(context, due))
            .build()
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setWhen(due)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(openNote(context, rec))
            .addAction(0, context.getString(R.string.remind_done), action(context, ActionReceiver.DONE, rec.id))
            .addAction(0, context.getString(R.string.remind_snooze, Reminders.SNOOZE_MINUTES), action(context, ActionReceiver.SNOOZE, rec.id))
            .setFullScreenIntent(screen(context, rec.id), true)
        context.getSystemService(NotificationManager::class.java).notify(rec.id, b.build())
    }

    /** "In Groceries · 17:00", and when it is late, that it is, and why if that is known. */
    fun said(context: Context, rec: Record, due: Long, late: Boolean, stopped: Long?): String {
        val note = Reminders.where(context, rec)
        val first = if (rec.key.isEmpty() || note.isEmpty()) Times.whenShort(context, due)
        else context.getString(R.string.remind_in_note, note, Times.whenShort(context, due))
        return when {
            !late -> first
            stopped != null -> first + "\n" + context.getString(R.string.remind_late_stopped, Times.whenShort(context, stopped))
            else -> first + "\n" + context.getString(R.string.remind_late)
        }
    }

    fun cancel(context: Context, id: Int) {
        context.getSystemService(NotificationManager::class.java).cancel(id)
    }

    /** The reminders whose notifications are showing now. */
    fun ringing(context: Context): Set<Int> =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .filter { it.notification.channelId == CHANNEL }
            .map { it.id }.toSet()

    private fun action(context: Context, what: String, id: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ActionReceiver::class.java)
            .setAction(what)
            // Each reminder and each button its own intent, so one never stands in for another.
            .setData(Uri.parse("oboegaki://reminder/$id/$what"))
            .putExtra(ActionReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openNote(context: Context, rec: Record): PendingIntent = PendingIntent.getActivity(
        context,
        rec.id,
        Intent(context, MainActivity::class.java)
            .setAction(MainActivity.OPEN_NOTE)
            .setData(Uri.parse("oboegaki://note/${rec.id}"))
            .putExtra(MainActivity.EXTRA_PATH, rec.path)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun screen(context: Context, id: Int): PendingIntent = PendingIntent.getActivity(
        context,
        id,
        Intent(context, ReminderActivity::class.java)
            .setData(Uri.parse("oboegaki://ringing/$id"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
