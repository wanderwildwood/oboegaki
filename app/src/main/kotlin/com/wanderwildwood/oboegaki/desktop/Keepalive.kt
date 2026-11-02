package com.wanderwildwood.oboegaki.desktop

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings

/** What Settings should say about Desktop Sync staying up. */
enum class DesktopWarning { NONE, RESTRICTED, STOPPED }

/**
 * Android ends a foreground service about a minute after the app leaves the screen when the
 * app's battery use is set to "Restricted" ("Restrict background activity"); a phone's own
 * background manager (DuraSpeed on the Kompakt) can end the whole app. Either way the switch
 * falls back to off with nothing said, so Settings says it: while on and restricted, and after
 * a stop the reader did not ask for ([wanted] is true from switching on until switching off).
 */
fun desktopWarning(running: Boolean, wanted: Boolean, restricted: Boolean): DesktopWarning = when {
    running && restricted -> DesktopWarning.RESTRICTED
    !running && wanted -> DesktopWarning.STOPPED
    else -> DesktopWarning.NONE
}

object Keepalive {

    private const val DURASPEED = "com.mediatek.duraspeed"

    fun backgroundRestricted(context: Context): Boolean = runCatching {
        context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
    }.getOrDefault(false)

    fun duraSpeedPresent(context: Context): Boolean = runCatching {
        context.packageManager.getApplicationInfo(DURASPEED, 0)
        true
    }.getOrDefault(false)

    /** This app's own App info page, where Battery is changed to Unrestricted. */
    fun appInfo(context: Context) = detailsIntent(context.packageName)

    /** DuraSpeed's App info page: its Open button leads to the list where Memo is switched on. */
    fun duraSpeedInfo() = detailsIntent(DURASPEED)

    private fun detailsIntent(pkg: String) =
        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
