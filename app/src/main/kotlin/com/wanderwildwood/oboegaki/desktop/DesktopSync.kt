package com.wanderwildwood.oboegaki.desktop

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.wanderwildwood.oboegaki.BuildConfig
import com.wanderwildwood.oboegaki.notes.Notes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Desktop Sync as the rest of the app sees it: whether it is running, how to start and stop it,
 * and the pairing code. The switch shows whether the service is alive. Once the reader switches
 * it on it stays on until they switch it off: a reboot, an update or the system stopping the app
 * brings it back ([resumeIfWanted]). Only a start that fails turns it off, and Settings says so.
 */
object DesktopSync {

    /** A debug build listens on its own port, so it can be tried beside a release build. */
    val PORT = if (BuildConfig.DEBUG) 8431 else 8430

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    internal fun setRunning(on: Boolean) {
        _running.value = on
    }

    /** Six-digit codes, counted on the phone's uptime clock, which only goes forward. */
    val pairing = Pairing(clock = { android.os.SystemClock.elapsedRealtime() })

    private fun send(context: Context, action: String, foreground: Boolean) {
        val intent = Intent(context, DesktopService::class.java).setAction("${context.packageName}.$action")
        if (foreground) ContextCompat.startForegroundService(context, intent) else context.startService(intent)
    }

    fun start(context: Context) = send(context, DesktopService.START, foreground = true)

    /**
     * Starts the server if the reader left it on and it is not running. Called when the process
     * starts, when Memo is opened, and after a reboot or an update ([DesktopRestartReceiver]).
     * Android 12 may refuse a start from the background (not for an app left out of battery
     * optimization); the switch stays on, and the next of those starts it.
     */
    fun resumeIfWanted(context: Context) {
        Notes.init(context)
        if (!Notes.preferences.desktopWanted || _running.value) return
        runCatching { start(context) }
            .onFailure { android.util.Log.i("oboegaki-desktop", "not started again: ${it.javaClass.simpleName}") }
    }

    fun stop(context: Context) {
        // Nothing is running to stop if the service is not up; do not start one just to stop it.
        if (_running.value) runCatching { send(context, DesktopService.STOP, foreground = false) }
        pairing.clear()
    }

    /**
     * The browsers that have paired. Made on first use; the list lives in the app's private
     * preferences, as hashes. The one shared token an earlier version kept is erased here.
     */
    private val pairedBrowsers by lazy {
        val preferences = Notes.preferences
        preferences.forgetSharedDesktopToken()
        PairedBrowsers(
            now = { System.currentTimeMillis() },
            initial = preferences.desktopBrowsers,
            save = { preferences.desktopBrowsers = it },
        )
    }

    fun browsers(): PairedBrowsers = pairedBrowsers

    /** Every paired browser is removed: its token stops working and open pages are closed. */
    fun removeAllBrowsers() {
        pairing.clear()
        browsers().removeAll()
    }

    /** The address to open on the computer; the code does the rest. */
    fun address(host: String) = "http://$host:$PORT"
}
