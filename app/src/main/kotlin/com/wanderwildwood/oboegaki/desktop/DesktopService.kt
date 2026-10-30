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
 * The foreground service that hosts the server, for exactly as long as the switch is on. It is
 * not sticky: if the system takes it away it stays away until the reader switches it on again.
 */
class DesktopService : Service() {

    companion object {
        const val START = "DESKTOP_START"
        const val STOP = "DESKTOP_STOP"
        const val RESET = "DESKTOP_RESET"
        private const val CHANNEL = "desktop_sync"
        private const val NOTIFICATION = 20261029
    }

    private var server: DesktopServer? = null
    private var watching: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "$packageName.$START" -> begin()
            "$packageName.$RESET" -> {
                Notes.init(this)
                Notes.preferences.desktopToken = newToken()
                end(removeNotification = false)
                begin()
            }
            else -> end(removeNotification = true)
        }
        return START_NOT_STICKY
    }

    @OptIn(FlowPreview::class)
    private fun begin() {
        // The notification first: a foreground service has seconds to put one up.
        startForeground(NOTIFICATION, notification(listening()))
        if (server != null) return
        Notes.init(this)
        Tasks.init(this)
        val preferences = Notes.preferences
        if (preferences.desktopToken.isEmpty()) preferences.desktopToken = newToken()

        val html = runCatching { assets.open("desktop/index.html").use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
        val next = DesktopServer(
            port = DesktopSync.PORT,
            context = applicationContext,
            token = preferences.desktopToken,
            pairing = DesktopSync.pairing,
            vpnOnly = { preferences.desktopVpnOnly },
            page = { html ?: "" },
        )
        if (html == null || runCatching { next.start(0, false) }.isFailure) {
            Log.w(TAG, "the server could not start")
            end(removeNotification = true)
            return
        }
        server = next
        DesktopSync.setRunning(true)
        Tasks.refresh()

        // Tell the page to look again when notes, pins or tasks change, wherever they changed.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        watching = scope
        scope.launch {
            combine(Notes.list, Notes.pins, Tasks.shown) { a, b, c -> Triple(a, b, c) }
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

    override fun onDestroy() {
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
