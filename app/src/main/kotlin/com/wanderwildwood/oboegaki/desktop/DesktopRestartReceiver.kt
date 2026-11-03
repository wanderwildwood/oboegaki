package com.wanderwildwood.oboegaki.desktop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After the phone starts up, and after the app is updated: Desktop Sync starts again if it was
 * left on. Android 12 lets both broadcasts start a foreground service from the background.
 */
class DesktopRestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        DesktopSync.resumeIfWanted(context.applicationContext)
    }
}
