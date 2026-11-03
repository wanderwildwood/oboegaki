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
 * and the pairing code. The switch shows whether the service is alive. An update, or the system
 * stopping the app, brings it back if the reader left it on and the phone has not restarted since;
 * a reboot turns it off, and nothing starts it after one (no boot receiver, not sticky).
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
     * Starts the server again if the reader left it on and the phone has not restarted since it
     * went on; forgets that it was on if the phone has. Called when the app is opened and after an
     * update ([DesktopUpdateReceiver]), the two moments Android lets a foreground service start.
     */
    fun resumeIfWanted(context: Context) {
        Notes.init(context)
        val preferences = Notes.preferences
        val saved = BootMark.decode(preferences.desktopBootMark)
        when (DesktopBoot.decide(preferences.desktopWanted, _running.value, saved, BootMark.now(context))) {
            DesktopBoot.Decision.NOTHING -> Unit
            DesktopBoot.Decision.OFF -> preferences.desktopWanted = false
            DesktopBoot.Decision.START -> runCatching { start(context) }
                .onFailure { android.util.Log.i("oboegaki-desktop", "not started again: ${it.javaClass.simpleName}") }
        }
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
