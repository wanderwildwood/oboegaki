package com.wanderwildwood.oboegaki.sync

import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A Nextcloud account as the login page handed it over: the server, the user's login name and
 * an app password made for this phone alone, which can be revoked from the server's security
 * settings without touching the real one.
 */
data class Account(val server: String, val user: String, val password: String)

/** Something the reader can act on: the server said no, or could not be reached. */
class Unreachable(message: String) : IOException(message)
class Refused(message: String) : IOException(message)

private val http = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

/**
 * The notes folder on a Nextcloud, over WebDAV.
 *
 * [folder] is the path under the user's files ("Notes"), and every path this hands [Sync] is
 * relative to it. Subfolders are walked one listing at a time: Nextcloud refuses a PROPFIND of
 * unlimited depth by default, and a notes folder is never deep enough for it to matter.
 */
class NextcloudRemote(private val account: Account, folder: String) : Remote {

    private val userRoot = account.server.trimEnd('/') + "/remote.php/dav/files/" + encodePath(account.user) + "/"
    private val folder = folder.trim('/')
    private val root = userRoot + if (this.folder.isEmpty()) "" else encodePath(this.folder) + "/"

    private val rootPath = decodeHref(root)

    private fun url(path: String) = root + encodePath(path)

    private fun request(url: String) =
        Request.Builder().url(url).header("Authorization", Credentials.basic(account.user, account.password))

    override fun list(): Map<String, String> {
        ensureFolder()
        val out = mutableMapOf<String, String>()
        val pending = ArrayDeque(listOf(""))
        while (pending.isNotEmpty()) {
            val dir = pending.removeFirst()
            for (entry in propfind(url(dir))) {
                if (!entry.path.startsWith(rootPath)) continue
                val relative = entry.path.removePrefix(rootPath)
                if (relative.trimEnd('/') == dir.trimEnd('/')) continue
                val name = relative.trimEnd('/').substringAfterLast('/')
                if (name.startsWith(".")) continue
                if (entry.isFolder) {
                    pending += relative
                } else if (isNote(name) && entry.etag != null) {
                    out[relative] = entry.etag
                }
            }
        }
        return out
    }

    override fun get(path: String): Fetched =
        call(request(url(path)).get().build()) { response ->
            if (response.code == 404) throw Moved("$path is gone")
            check(response, path)
            Fetched(response.body!!.string(), response.header("ETag"))
        }

    override fun put(path: String, text: String, expect: Expect): String? {
        makeParents(path)
        val builder = request(url(path)).put(text.toRequestBody(MARKDOWN))
        when (expect) {
            Expect.Absent -> builder.header("If-None-Match", "*")
            is Expect.Unchanged -> builder.header("If-Match", expect.etag)
        }
        return call(builder.build()) { response ->
            if (response.code == 412) throw Moved("$path changed on the server")
            check(response, path)
            response.header("ETag") ?: response.header("OC-ETag")
        }
    }

    override fun delete(path: String, etag: String) {
        call(request(url(path)).delete().header("If-Match", etag).build()) { response ->
            if (response.code == 412) throw Moved("$path changed on the server")
            if (response.code == 404) return@call
            check(response, path)
        }
    }

    /** The notes folder itself, made if this is the first sync to it. */
    private fun ensureFolder() {
        if (folder.isNotEmpty()) mkcolAll(userRoot, folder)
    }

    /** Every folder above [path], made where missing, because a PUT into nothing fails. */
    private fun makeParents(path: String) {
        val dir = path.substringBeforeLast('/', "")
        if (dir.isNotEmpty()) mkcolAll(root, dir)
    }

    private fun mkcolAll(base: String, dir: String) {
        var at = ""
        for (part in dir.split('/')) {
            at += encodePath(part) + "/"
            val request = request(base + at).method("MKCOL", null).build()
            call(request) { response ->
                // 405 is "already there", which is the usual answer.
                if (response.code != 201 && response.code != 405) check(response, dir)
            }
        }
    }

    private fun propfind(url: String): List<Entry> {
        val body = PROPFIND_BODY.toRequestBody(XML)
        val request = request(url).method("PROPFIND", body).header("Depth", "1").build()
        return call(request) { response ->
            check(response, url)
            response.body!!.byteStream().use(::parseMultistatus)
        }
    }

    private fun <T> call(request: Request, read: (Response) -> T): T {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw Unreachable(e.message ?: "no answer")
        }
        return response.use(read)
    }

    private fun check(response: Response, what: String) {
        when {
            response.isSuccessful -> Unit
            response.code == 401 || response.code == 403 -> throw Refused("signed out")
            else -> throw IOException("${response.code} for $what")
        }
    }
}

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

private val MARKDOWN = "text/markdown; charset=utf-8".toMediaType()
private val XML = "application/xml; charset=utf-8".toMediaType()

private const val PROPFIND_BODY = """<?xml version="1.0"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:getetag/><d:resourcetype/></d:prop></d:propfind>"""
