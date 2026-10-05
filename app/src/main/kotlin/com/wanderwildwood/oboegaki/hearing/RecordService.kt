package com.wanderwildwood.oboegaki.hearing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import com.wanderwildwood.oboegaki.MainActivity
import com.wanderwildwood.oboegaki.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the microphone while the screen is off or another app is in front. Android stops an
 * app hearing anything once it leaves the screen unless it says so with a notification, and a
 * thought spoken into a phone in a pocket is the point of a voice note.
 *
 * The notification says it is recording and has the one thing to do with it: stop.
 */
class RecordService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            Voice.end()
            finish()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (!Voice.begin(this)) {
            finish()
            return START_NOT_STICKY
        }
        lock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "oboegaki:recording")
            .apply { acquire(Recorder.LONGEST * 1000L + 60_000L) }
        // Ends with the recording, however it ends: the Stop here, the one on the screen, or
        // the hour running out.
        scope.launch {
            Voice.recording.first { it == null }
            finish()
        }
        return START_NOT_STICKY
    }

    private fun finish() {
        lock?.let { if (it.isHeld) it.release() }
        lock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        lock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.record_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecordService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.record_notification))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.record_stop), stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "recording"
        private const val NOTIFICATION = 1
        private const val STOP = "stop"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RecordService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecordService::class.java).setAction(STOP))
        }
    }
}
