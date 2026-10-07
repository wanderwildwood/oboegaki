package com.wanderwildwood.oboegaki.notes

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings

/**
 * The apps that carry a notes folder to and from a computer, when the notes are kept in one.
 *
 * Notes writes the files and those apps move them, so a note made on the computer reaches the
 * phone only while one of them is running. DuraSpeed, MediaTek's background manager on the
 * Kompakt, stops installed apps a while after the screen goes dark, unless they are switched on
 * in its list; that list cannot be read by another app, and Settings has no way into it. Its
 * App info page can be opened, and has an Open button, so that is where the button goes.
 */
object SyncApps {

    /** Syncthing-Fork 2 and later, then the 1.x name it had before. */
    private val SYNCTHING_FORK = listOf(
        "com.github.catfriend1.syncthingfork",
        "com.github.catfriend1.syncthingandroid",
    )

    /** Every app here that may be carrying the folder, most likely first. */
    private val CARRIERS = SYNCTHING_FORK + listOf(
        "com.nutomic.syncthingandroid",
        "dk.tacit.android.foldersync.full",
        "dk.tacit.android.foldersync.lite",
        "at.bitfire.davdroid",
        "md.obsidian",
    )

    private const val DURASPEED = "com.mediatek.duraspeed"

    fun isKompakt(): Boolean = Build.MANUFACTURER.equals("Mudita", ignoreCase = true)

    private fun installed(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(pkg, 0).enabled
    }.getOrDefault(false)

    /** Syncthing-Fork's package, if it is on the phone. */
    fun syncthingFork(context: Context): String? = SYNCTHING_FORK.firstOrNull { installed(context, it) }

    /** The names of the carrying apps on the phone, as the launcher shows them. */
    fun carriers(context: Context): List<String> {
        val pm = context.packageManager
        return CARRIERS.filter { installed(context, it) }
            .mapNotNull { runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }.getOrNull() }
            .distinct()
    }

    /**
     * Ask Syncthing-Fork to run, as its own start button would but keeping to its run
     * conditions: FOLLOW rather than START, which would leave it forced on for good.
     * It answers only with Settings › Behavior › Service Control by Broadcast switched on,
     * and quietly ignores the ask otherwise.
     */
    fun wakeSyncthing(context: Context) {
        val pkg = syncthingFork(context) ?: return
        runCatching { context.sendBroadcast(Intent("$pkg.action.FOLLOW").setPackage(pkg)) }
    }

    /** Whether the DuraSpeed row belongs in Settings: a Kompakt with DuraSpeed on it. */
    fun hasDuraSpeed(context: Context): Boolean = isKompakt() && runCatching {
        context.packageManager.getApplicationInfo(DURASPEED, PackageManager.MATCH_SYSTEM_ONLY)
        true
    }.getOrDefault(false)

    fun duraSpeedInfo(): Intent = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.parse("package:$DURASPEED"))
}
