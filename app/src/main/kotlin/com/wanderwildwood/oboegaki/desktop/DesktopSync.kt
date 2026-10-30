package com.wanderwildwood.oboegaki.desktop

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.wanderwildwood.oboegaki.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Desktop Sync as the rest of the app sees it: whether it is running, how to start and stop it,
 * and the pairing code. Whether it is on is whether the service is alive, nothing saved: a
 * restart, an update or the system stopping the app leaves it off, and it never starts itself.
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

    fun stop(context: Context) {
        // Nothing is running to stop if the service is not up; do not start one just to stop it.
        if (_running.value) runCatching { send(context, DesktopService.STOP, foreground = false) }
        pairing.clear()
    }

    /** A new token: old links and open tabs stop working at once. */
    fun resetLink(context: Context) {
        pairing.clear()
        if (_running.value) send(context, DesktopService.RESET, foreground = false)
        else com.wanderwildwood.oboegaki.notes.Notes.preferences.desktopToken = newToken()
    }

    /** The full address with the token in it, for pasting. */
    fun link(host: String, token: String) = "http://$host:$PORT/?token=$token"
}
