package com.wanderwildwood.oboegaki

import android.app.Application
import android.util.Log
import com.wanderwildwood.oboegaki.desktop.DesktopSync

/**
 * The app's process. Its one job here: Desktop Sync left on comes back whenever the process
 * starts, whatever started it. From the background Android 12 may refuse that start; the switch
 * stays on, and the boot and update receivers, the sticky service or opening Memo bring it back.
 */
class MemoApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        runCatching { DesktopSync.resumeIfWanted(this) }
            .onFailure { Log.i("oboegaki-desktop", "not started at process start: ${it.javaClass.simpleName}") }
    }
}
