package com.wanderwildwood.oboegaki.sync

import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * A Nextcloud account as the login page handed it over: the server, the user's login name and
 * an app password made for this phone alone, which can be revoked from the server's security
 * settings without touching the real one.
 */
data class Account(val server: String, val user: String, val password: String)

/** Something the reader can act on: the server said no, or could not be reached. */
class Unreachable(message: String) : IOException(message)
class Refused(message: String) : IOException(message)

/** The notes folder on a Nextcloud: WebDAV, under the user's own files. */
fun nextcloudRemote(account: Account, folder: String) = WebDavRemote(
    base = account.server.trimEnd('/') + "/remote.php/dav/files/" + encodePath(account.user) + "/",
    user = account.user,
    password = account.password,
    folder = folder,
    // Nextcloud keeps etags, says them after every write, and honours If-Match.
    trusted = true,
)

/**
 * Nextcloud's own sign-in, the one its apps use: the phone asks the server for a login page,
 * the reader signs in there in the browser, and the server hands this phone an app password.
 * Nobody types a password into this app, and two-factor works because it is the server's page.
 */
object LoginFlow {

    data class Started(val loginUrl: String, val pollUrl: String, val token: String)

    fun start(server: String): Started {
        val base = normalise(server)
        val request = Request.Builder()
            .url("$base/index.php/login/v2")
            .post(FormBody.Builder().build())
            .header("User-Agent", "Notes (oboegaki)")
            .build()
        val json = try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Refused("${response.code}")
                JSONObject(response.body!!.string())
            }
        } catch (e: IOException) {
            if (e is Refused) throw e
            throw Unreachable(e.message ?: "no answer")
        }
        val poll = json.getJSONObject("poll")
        return Started(json.getString("login"), poll.getString("endpoint"), poll.getString("token"))
    }

    /** The account, once the reader has finished in the browser, or null while they have not. */
    fun poll(started: Started): Account? {
        val request = Request.Builder()
            .url(started.pollUrl)
            .post(FormBody.Builder().add("token", started.token).build())
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (response.code == 404) return null
                if (!response.isSuccessful) throw Refused("${response.code}")
                val json = JSONObject(response.body!!.string())
                Account(
                    server = json.getString("server").trimEnd('/'),
                    user = json.getString("loginName"),
                    password = json.getString("appPassword"),
                )
            }
        } catch (e: IOException) {
            if (e is Refused) throw e
            null
        }
    }

    /** What was typed, as an address: "cloud.example" becomes "https://cloud.example". */
    fun normalise(server: String): String {
        val trimmed = server.trim().trimEnd('/')
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }
}
