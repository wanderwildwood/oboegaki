package com.wanderwildwood.oboegaki

import android.app.Application
import android.util.Log
import com.wanderwildwood.oboegaki.desktop.DesktopSync
import com.wanderwildwood.oboegaki.desktop.Network
import com.wanderwildwood.oboegaki.sync.LoginFlow

/**
 * The app's process. It shows the plain-http rule how to tell whether the phone is on Tailscale,
 * and brings Desktop Sync left on back whenever the process starts, whatever started it. From the
 * background Android 12 may refuse that start; the switch stays on, and the boot and update
 * receivers, the sticky service or opening Memo bring it back.
 */
class MemoApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // A 100.64/10 server on plain http is home only while the phone is on Tailscale.
        val app = this
        LoginFlow.tailscaleUp = { Network.onTailscale(app) }
        runCatching { DesktopSync.resumeIfWanted(this) }
            .onFailure { Log.i("oboegaki-desktop", "not started at process start: ${it.javaClass.simpleName}") }
    }
}
