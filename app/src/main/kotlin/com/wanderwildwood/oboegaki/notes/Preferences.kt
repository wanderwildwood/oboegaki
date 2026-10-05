package com.wanderwildwood.oboegaki.notes

import android.content.Context
import android.net.Uri
import com.wanderwildwood.oboegaki.sync.Account

/** The order of the list. */
enum class Order { CHANGED, TITLE }

/** Where the notes are kept. NOWHERE until the reader has chosen. */
enum class Keeping { NOWHERE, FOLDER, NEXTCLOUD }

/**
 * What the reader chose, kept in the app's private preferences.
 *
 * The Nextcloud app password is kept here as well. The app's storage is private to it and the
 * manifest turns backups off, so it leaves the phone only to go to the server it was made for;
 * and it is an app password, which the server can revoke on its own from its security page.
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

    /** The folder on the Nextcloud the notes live in, under the user's files. */
    var remoteFolder: String
        get() = store.getString(REMOTE_FOLDER, null) ?: "Notes"
        set(value) = store.edit().putString(REMOTE_FOLDER, value.trim('/', ' ')).apply()

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
        const val LAST_SYNC = "last_sync"
        const val ORDER = "order"
        const val SHOWING = "showing"
        const val CAMERA = "camera"
    }
}
