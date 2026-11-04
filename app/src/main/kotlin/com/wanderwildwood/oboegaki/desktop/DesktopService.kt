package com.wanderwildwood.oboegaki.desktop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wanderwildwood.oboegaki.R
import com.wanderwildwood.oboegaki.notes.Notes
import com.wanderwildwood.oboegaki.tasks.Tasks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

private const val TAG = "oboegaki-desktop"

/**
 * The foreground service that hosts the server, for as long as the switch is on. Once on it stays
 * on until the reader switches it off: it is sticky, and after a reboot, an update or the system
 * taking it away it comes back by the first route Android allows ([DesktopRestartReceiver], the
 * sticky restart, the process starting, Memo being opened: [DesktopSync.resumeIfWanted]).
 */
class DesktopService : Service() {

    companion object {
        const val START = "DESKTOP_START"
        const val STOP = "DESKTOP_STOP"
        private const val CHANNEL = "desktop_sync"
        private const val NOTIFICATION = 20261029
    }

    private var server: DesktopServer? = null
    private var watching: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Why the service starts or stops, in the log and nowhere else: no note text, no address.
        Log.i(TAG, "service command: ${intent?.action?.removePrefix("$packageName.") ?: "none (restart or stray intent)"}")
        when (intent?.action) {
            "$packageName.$START" -> begin()
            // The reader's own Stop (the switch or the notification button) is the only thing that says "no".
            "$packageName.$STOP" -> { Notes.init(this); Notes.preferences.desktopWanted = false; end(removeNotification = true) }
            // The system restarting the sticky service (no intent), or a stray intent: back up if
            // the reader left it on. Neither turns the switch off.
            else -> { Notes.init(this); if (Notes.preferences.desktopWanted) begin() else end(removeNotification = true) }
        }
        // Sticky: if the system ends the process, it starts the service again by itself.
        return START_STICKY
    }

    @OptIn(FlowPreview::class)
    private fun begin() {
        // The notification first: a foreground service has seconds to put one up. A sticky restart
        // in the background can be refused it on Android 12: the switch stays on, and the next
        // allowed start (opening Memo, the next boot or update) brings it up.
        if (runCatching { startForeground(NOTIFICATION, notification(listening())) }.isFailure) {
            Log.i(TAG, "not allowed to run in the foreground now")
            stopSelf()
            return
        }
        if (server != null) return
        Notes.init(this)
        Tasks.init(this)
        val preferences = Notes.preferences

        val html = runCatching { assets.open("desktop/index.html").use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
        val next = DesktopServer(
            port = DesktopSync.PORT,
            context = applicationContext,
            browsers = DesktopSync.browsers(),
            pairing = DesktopSync.pairing,
            vpnOnly = { preferences.desktopVpnOnly },
            page = { html ?: "" },
        )
        val failed = if (html == null) getString(R.string.desktop_failed_page) else runCatching { next.start(0, false) }.exceptionOrNull()
            ?.let { it.message?.takeIf(String::isNotBlank) ?: it.javaClass.simpleName }
        if (failed != null) {
            Log.w(TAG, "the server could not start")
            // The one time it goes off without the reader: Settings says so, once, with why.
            preferences.desktopFailure = failed
            preferences.desktopWanted = false
            end(removeNotification = true)
            return
        }
        server = next
        preferences.desktopWanted = true
        DesktopSync.setRunning(true)
        // Started on its own (a reboot, an update, the system stopping the app), the service may
        // be all that runs: nothing has read the notes or asked the server for new ones yet.
        Notes.refresh()
        Notes.syncNow()
        Tasks.refresh()

        // Tell the page to look again when notes, pins or tasks change, wherever they changed.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        watching = scope
        scope.launch {
            combine(Notes.list, Notes.pins, Tasks.shown, Notes.trash) { a, b, c, d -> listOf(a, b, c, d) }
                .drop(1)
                .debounce(250)
                .collect { next.notifyChanged() }
        }
        startForeground(NOTIFICATION, notification(listening()))
    }

    private fun end(removeNotification: Boolean) {
        watching?.cancel()
        watching = null
        server?.stop()
        server = null
        DesktopSync.setRunning(false)
        DesktopSync.pairing.clear()
        if (removeNotification) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "the app was swiped away from Recents; the service goes on")
    }

    override fun onTrimMemory(level: Int) {
        Log.i(TAG, "the system asked the app to trim memory, level $level")
    }

    override fun onDestroy() {
        Log.i(TAG, "the service was destroyed; background restricted: ${Keepalive.backgroundRestricted(this)}")
        end(removeNotification = false)
        super.onDestroy()
    }

    private fun listening(): String {
        val first = Network.reachable(this).firstOrNull()
        return if (first != null) getString(R.string.desktop_listening, first.address, DesktopSync.PORT) else getString(R.string.desktop_waiting)
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.desktop_title), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, DesktopService::class.java).setAction("$packageName.$STOP"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.desktop_running))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.desktop_stop), stop)
            .build()
    }
}
