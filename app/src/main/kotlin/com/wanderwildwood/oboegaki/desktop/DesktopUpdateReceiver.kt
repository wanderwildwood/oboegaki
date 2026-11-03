package com.wanderwildwood.oboegaki.desktop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After the app is updated: Desktop Sync starts again if it was on, and the phone has not
 * restarted since it went on. Android lets this broadcast start a foreground service from the
 * background. There is deliberately no boot receiver for Desktop Sync: a reboot leaves it off.
 */
class DesktopUpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        DesktopSync.resumeIfWanted(context.applicationContext)
    }
}
