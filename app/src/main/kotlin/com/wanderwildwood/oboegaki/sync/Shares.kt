package com.wanderwildwood.oboegaki.sync

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Someone on the same Nextcloud a note can be shared with. */
data class Person(val id: String, val name: String)

/** A note shared with one person, and whether they may change it. */
data class Share(val id: String, val with: String, val name: String, val canEdit: Boolean)

/** A note someone else shared with this account: where it sits in this account's files. */
data class Incoming(val id: String, val path: String, val from: String)

/**
 * Sharing a note with another person on the same Nextcloud, through Nextcloud's own sharing:
 * the note stays one file, and both people's phones sync that same file. Nothing here keeps a
 * second copy.
 *
 * Paths are the server's, from the root of this account's files ("/Notes/Groceries.md").
 */
class Sharing(private val account: Account) {

    private val base = account.server.trimEnd('/') + "/ocs/v2.php/apps/files_sharing/api/v1"

    /** The people this account can share with, as the server lists them. */
    fun people(): List<Person> {
        val data = get("$base/sharees?search=&itemType=file&perPage=50&format=json").getJSONObject("data")
        val out = mutableListOf<Person>()
        for (key in listOf("exact", "")) {
            val holder = if (key.isEmpty()) data else data.optJSONObject(key) ?: continue
            val users = holder.optJSONArray("users") ?: continue
            for (i in 0 until users.length()) {
                val user = users.getJSONObject(i)
                val id = user.getJSONObject("value").getString("shareWith")
                if (id != account.user && out.none { it.id == id }) out += Person(id, user.optString("label", id))
            }
        }
        return out
    }

    /** Who [path] is shared with, person by person. Links and the like are not listed. */
    fun sharesOf(path: String): List<Share> =
        array(get("$base/shares?path=${encodePath(path)}&reshares=true&format=json"))
            .filter { it.optInt("share_type") == USER }
            .map { share(it) }

    /** Share [path] with [person]: to read, or to read and change. */
    fun share(path: String, person: String, canEdit: Boolean) {
        val body = FormBody.Builder()
            .add("path", path)
            .add("shareType", USER.toString())
            .add("shareWith", person)
            .add("permissions", permissions(canEdit).toString())
            .build()
        call(request("$base/shares?format=json").post(body).build())
    }

    fun setCanEdit(id: String, canEdit: Boolean) {
        val body = FormBody.Builder().add("permissions", permissions(canEdit).toString()).build()
        call(request("$base/shares/$id?format=json").put(body).build())
    }

    fun stop(id: String) {
        call(request("$base/shares/$id?format=json").delete().build())
    }

    /** Every note this account has shared with a person, by path. */
    fun mine(): Set<String> =
        array(get("$base/shares?format=json"))
            .filter { it.optInt("share_type") == USER && it.optString("item_type") == "file" }
            .map { it.getString("path") }
            .toSet()

    /** Every file shared with this account, wherever it has landed in its files. */
    fun withMe(): List<Incoming> =
        array(get("$base/shares?shared_with_me=true&format=json"))
            .filter { it.optString("item_type") == "file" }
            .map { Incoming(it.getString("id"), it.getString("path"), it.optString("displayname_owner", it.optString("uid_owner"))) }

    /**
     * Move a file within this account's files. For a note shared with this account this moves
     * only where it appears here; the owner's copy, and the sharing, are untouched.
     */
    fun move(from: String, to: String) {
        val root = account.server.trimEnd('/') + "/remote.php/dav/files/" + encodePath(account.user)
        val request = request(root + encodePath(from))
            .method("MOVE", null)
            .header("Destination", root + encodePath(to))
            .header("Overwrite", "F")
            .build()
        call(request)
    }

    private fun share(json: JSONObject) = Share(
        id = json.getString("id"),
        with = json.getString("share_with"),
        name = json.optString("share_with_displayname", json.getString("share_with")),
        canEdit = json.optInt("permissions") and UPDATE != 0,
    )

    private fun permissions(canEdit: Boolean) = if (canEdit) READ or UPDATE else READ

    private fun request(url: String) = Request.Builder()
        .url(url)
        .header("OCS-APIRequest", "true")
        .header("Authorization", okhttp3.Credentials.basic(account.user, account.password))

    private fun get(url: String): JSONObject =
        JSONObject(call(request(url).get().build()) ?: "{}").getJSONObject("ocs")

    private fun array(ocs: JSONObject): List<JSONObject> {
        val data = ocs.opt("data") as? JSONArray ?: return emptyList()
        return (0 until data.length()).map { data.getJSONObject(it) }
    }

    /** The body of a successful answer; throws [Refused] or [Unreachable] otherwise. */
    private fun call(request: Request): String? {
        val response: Response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw Unreachable(e.message ?: "no answer")
        }
        return response.use {
            when {
                it.isSuccessful -> it.body?.string()
                it.code == 401 -> throw Refused("signed out")
                else -> throw IOException(sharingError(it.code, it.body?.string()))
            }
        }
    }

    private fun sharingError(code: Int, body: String?): String =
        runCatching { JSONObject(body ?: "").getJSONObject("ocs").getJSONObject("meta").getString("message") }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: "the server answered $code"

    private companion object {
        const val USER = 0
        const val READ = 1
        const val UPDATE = 2
        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
