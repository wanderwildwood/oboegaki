package com.wanderwildwood.oboegaki.desktop

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
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

    /**
     * Whether Android leaves Memo out of battery optimisation. An app left out is also passed over
     * by the battery manager that restricts apps by itself (on the Kompakt it puts them back on
     * its restricted list after a while, and Desktop Sync then stops); Messaging, which is left
     * out, keeps its Desktop Sync running.
     */
    fun allowedToRun(context: Context): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(true)

    /** Android's own "let this app run in the background?" dialog, or its list where that is refused. */
    fun askToRun(context: Context) {
        runCatching {
            context.startActivity(
                Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            runCatching {
                context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

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
