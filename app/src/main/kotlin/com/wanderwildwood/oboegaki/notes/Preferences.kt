package com.wanderwildwood.oboegaki.notes

import android.content.Context
import android.net.Uri
import com.wanderwildwood.oboegaki.sync.Account
import com.wanderwildwood.oboegaki.sync.DavAccount

/** The order of the list. */
enum class Order { CHANGED, OLDEST, TITLE }

/** Where the notes are kept. NOWHERE until the reader has chosen. */
enum class Keeping {
    NOWHERE, FOLDER, NEXTCLOUD, WEBDAV;

    /** Kept on a server, through a copy on the phone that is synced with it. */
    val isServer get() = this == NEXTCLOUD || this == WEBDAV
}

/**
 * What the reader chose, kept in the app's private preferences.
 *
 * The Nextcloud app password is kept here as well. The app's storage is private to it and the
 * manifest turns backups off, so it leaves the phone only to go to the server it was made for;
 * and it is an app password, which the server can revoke on its own from its security page.
 * A WebDAV server's password is kept the same way, beside its address; the form suggests an
 * app password for it too, where the service offers one.
 */
class Preferences(context: Context) {

    private val store = context.getSharedPreferences("oboegaki", Context.MODE_PRIVATE)

    var keeping: Keeping
        get() = runCatching { Keeping.valueOf(store.getString(KEEPING, null) ?: "") }.getOrDefault(Keeping.NOWHERE)
        set(value) = store.edit().putString(KEEPING, value.name).apply()

    /** The folder chosen through the system picker. */
    var folder: Uri?
        get() = store.getString(FOLDER, null)?.let(Uri::parse)
        set(value) = store.edit().putString(FOLDER, value?.toString()).apply()

    var account: Account?
        get() {
            val server = store.getString(SERVER, null) ?: return null
            val user = store.getString(USER, null) ?: return null
            val password = store.getString(PASSWORD, null) ?: return null
            return Account(server, user, password)
        }
        set(value) = store.edit()
            .putString(SERVER, value?.server)
            .putString(USER, value?.user)
            .putString(PASSWORD, value?.password)
            .apply()

    /** A WebDAV server, as it was typed in and checked. */
    var dav: DavAccount?
        get() {
            val address = store.getString(DAV_ADDRESS, null) ?: return null
            val user = store.getString(DAV_USER, null) ?: return null
            val password = store.getString(DAV_PASSWORD, null) ?: return null
            return DavAccount(address, user, password)
        }
        set(value) = store.edit()
            .putString(DAV_ADDRESS, value?.address)
            .putString(DAV_USER, value?.user)
            .putString(DAV_PASSWORD, value?.password)
            .apply()

    /** The folder on the WebDAV server the notes live in, under its address. */
    var davFolder: String
        get() = store.getString(DAV_FOLDER, null) ?: "Notes"
        set(value) = store.edit().putString(DAV_FOLDER, value.trim('/', ' ')).apply()

    /** The folder on the Nextcloud the notes live in, under the user's files. */
    var remoteFolder: String
        get() = store.getString(REMOTE_FOLDER, null) ?: "Notes"
        set(value) = store.edit().putString(REMOTE_FOLDER, value.trim('/', ' ')).apply()

    /**
     * The folder new notes are made in, under the notes folder: new notes, voice notes, scans,
     * pictures and anything shared in. "" is the top, as it always was.
     */
    var newFolder: String
        get() = store.getString(NEW_FOLDER, null) ?: ""
        set(value) = store.edit().putString(NEW_FOLDER, value).apply()

    var order: Order
        get() = runCatching { Order.valueOf(store.getString(ORDER, null) ?: "") }.getOrDefault(Order.CHANGED)
        set(value) = store.edit().putString(ORDER, value.name).apply()

    /** What the list shows: "" for everything, "shared", "lists", or "folder:<path>". */
    var showing: String
        get() = store.getString(SHOWING, null) ?: ""
        set(value) = store.edit().putString(SHOWING, value).apply()

    /** The camera app scans are taken with, by package, once one has been chosen. */
    var camera: String?
        get() = store.getString(CAMERA, null)
        set(value) = store.edit().putString(CAMERA, value).apply()

    /** Whether the pinned notes are handed to Glance for the lock screen. */
    var lockScreen: Boolean
        get() = store.getBoolean(LOCK_SCREEN, true)
        set(value) = store.edit().putBoolean(LOCK_SCREEN, value).apply()

    /** The language voice notes are heard in; anything but "en" uses the downloaded model. */
    var speechLanguage: String
        get() = store.getString(SPEECH_LANGUAGE, null) ?: "en"
        set(value) = store.edit().putString(SPEECH_LANGUAGE, value).apply()

    /** Whether opening the app asks Syncthing-Fork to run, when the notes are in a folder. */
    var wakeSyncthing: Boolean
        get() = store.getBoolean(WAKE_SYNCTHING, true)
        set(value) = store.edit().putBoolean(WAKE_SYNCTHING, value).apply()

    /** The reader's word that the sync app is switched on in DuraSpeed's list. */
    var duraSpeedDone: Boolean
        get() = store.getBoolean(DURASPEED_DONE, false)
        set(value) = store.edit().putBoolean(DURASPEED_DONE, value).apply()

    var lastSync: Long
        get() = store.getLong(LAST_SYNC, 0L)
        set(value) = store.edit().putLong(LAST_SYNC, value).apply()

    private companion object {
        const val KEEPING = "keeping"
        const val FOLDER = "folder"
        const val SERVER = "server"
        const val USER = "user"
        const val PASSWORD = "password"
        const val REMOTE_FOLDER = "remote_folder"
        const val DAV_ADDRESS = "dav_address"
        const val DAV_USER = "dav_user"
        const val DAV_PASSWORD = "dav_password"
        const val DAV_FOLDER = "dav_folder"
        const val LAST_SYNC = "last_sync"
        const val NEW_FOLDER = "new_folder"
        const val ORDER = "order"
        const val SHOWING = "showing"
        const val CAMERA = "camera"
        const val LOCK_SCREEN = "lock_screen"
        const val SPEECH_LANGUAGE = "speech_language"
        const val WAKE_SYNCTHING = "wake_syncthing"
        const val DURASPEED_DONE = "duraspeed_done"
    }
}
