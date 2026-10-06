package com.wanderwildwood.oboegaki.sync

import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** The server's certificate could not be trusted, so nothing was sent. */
class Untrusted(message: String) : IOException(message)

/** The address answered, but not as a WebDAV folder. */
class NotWebDav(message: String) : IOException(message)

/** Nothing at that path. Inside this file only: to [Sync] a missing note is [Moved]. */
private class Missing : IOException("not there")

internal val http = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

/**
 * A WebDAV server as the reader typed it in: the full address of the folder or account root,
 * as the service gives it ("https://app.koofr.net/dav/Koofr"), a username and a password.
 */
data class DavAccount(val address: String, val user: String, val password: String)

/**
 * A folder of notes on a WebDAV server, the far end of a [Sync].
 *
 * [base] is the address everything is under, and [folder] the notes folder below it ("Notes");
 * every path this hands [Sync] is relative to that folder. Subfolders are walked one listing at
 * a time, because many servers refuse a PROPFIND of unlimited depth.
 *
 * A Nextcloud is one of these ([nextcloudRemote]) and is [trusted]: it keeps an etag for every
 * file, says the new one after a write, and honours If-Match. Any other server is taken as it
 * comes. Where it keeps no etags, a file's version is when it last changed and how long it is
 * ([Entry.version]); and since it may ignore If-Match and If-None-Match, every write and
 * deletion first looks at what is there now, and goes ahead only if that is still what [Sync]
 * expects. The conditions are still sent where they mean something, for a server that does
 * honour them.
 *
 * Nothing here needs MOVE or COPY: a renamed note reaches the server as a new file and a
 * deletion, so a server without them loses nothing.
 */
class WebDavRemote(
    base: String,
    private val user: String,
    private val password: String,
    folder: String,
    private val trusted: Boolean = false,
    private val client: OkHttpClient = http,
) : Remote {

    private val base = base.trimEnd('/') + "/"
    private val folder = folder.trim('/', ' ')
    private val root = this.base + if (this.folder.isEmpty()) "" else encodePath(this.folder) + "/"

    /** The folders this has made or seen, so each is asked about once. */
    private val made = mutableSetOf<String>()

    private fun url(path: String) = root + encodePath(path)

    private fun request(url: String): Request.Builder {
        val credentials = if (trusted) Credentials.basic(user, password) else Credentials.basic(user, password, Charsets.UTF_8)
        return Request.Builder().url(url).header("Authorization", credentials).header("User-Agent", "Notes (oboegaki)")
    }

    override fun list(): Map<String, String> {
        val top = try {
            propfind(root, 1)
        } catch (_: Missing) {
            // The first sync to a folder not made yet.
            makeFolder()
            propfind(root, 1)
        }
        // The folder as the server names it. Usually the path asked for; behind a proxy that
        // rewrites paths it can be something else, and then it is the listing's own first,
        // shortest folder, which is the folder itself.
        val asked = decodeHref(root)
        val rootPath = if (top.any { it.path.startsWith(asked) }) {
            asked
        } else {
            top.filter { it.isFolder }.minByOrNull { it.path.length }?.path ?: asked
        }

        val out = mutableMapOf<String, String>()
        val pending = ArrayDeque(listOf("" to top))
        while (pending.isNotEmpty()) {
            val (dir, entries) = pending.removeFirst()
            val subfolders = mutableListOf<String>()
            for (entry in entries) {
                if (!entry.path.startsWith(rootPath)) continue
                val relative = entry.path.removePrefix(rootPath)
                if (relative.trimEnd('/') == dir.trimEnd('/')) continue
                val name = relative.trimEnd('/').substringAfterLast('/')
                if (name.isEmpty() || name.startsWith(".") && relative != PINS) continue
                if (entry.isFolder) {
                    subfolders += relative
                } else if (syncsAsText(relative) || isAttachment(name)) {
                    // A trusted server always has an etag; one listed without is half-written.
                    val version = entry.version ?: if (trusted) null else ""
                    if (version != null) out[relative] = version
                }
            }
            for (sub in subfolders) {
                made += sub.trimEnd('/')
                pending += sub to propfind(url(sub), 1)
            }
        }
        return out
    }

    override fun get(path: String): Fetched {
        val version = if (trusted) null else stat(path) ?: throw Moved("$path is gone")
        return call(request(url(path)).get().build()) { response ->
            if (response.code == 404) throw Moved("$path is gone")
            check(response, path)
            Fetched(response.body!!.string(), version ?: response.header("ETag"), response.headers.getDate("Last-Modified")?.time)
        }
    }

    override fun getBytes(path: String): FetchedBytes {
        val version = if (trusted) null else stat(path) ?: throw Moved("$path is gone")
        return call(request(url(path)).get().build()) { response ->
            if (response.code == 404) throw Moved("$path is gone")
            check(response, path)
            FetchedBytes(response.body!!.bytes(), version ?: response.header("ETag"), response.headers.getDate("Last-Modified")?.time)
        }
    }

    override fun put(path: String, text: String, expect: Expect): String? =
        putBody(path, text.toRequestBody(MARKDOWN), expect)

    override fun putBytes(path: String, bytes: ByteArray, expect: Expect): String? =
        putBody(path, bytes.toRequestBody(OCTETS), expect)

    private fun putBody(path: String, body: RequestBody, expect: Expect): String? {
        makeParents(path)
        if (!trusted) {
            // Read before write: the server may not honour the conditions below.
            val now = stat(path)
            when (expect) {
                Expect.Absent -> if (now != null) throw Moved("$path is already on the server")
                is Expect.Unchanged -> if (now != expect.etag) throw Moved("$path changed on the server")
            }
        }
        val builder = request(url(path)).put(body)
        when (expect) {
            Expect.Absent -> builder.header("If-None-Match", "*")
            is Expect.Unchanged -> if (isEtag(expect.etag)) builder.header("If-Match", expect.etag)
        }
        return call(builder.build()) { response ->
            if (response.code == 412) throw Moved("$path changed on the server")
            check(response, path)
            (response.header("ETag") ?: response.header("OC-ETag"))?.let(::canonicalEtag)
        }
    }

    override fun delete(path: String, etag: String) {
        if (!trusted) {
            val now = stat(path) ?: return
            if (now != etag) throw Moved("$path changed on the server")
        }
        val builder = request(url(path)).delete()
        if (isEtag(etag)) builder.header("If-Match", etag)
        call(builder.build()) { response ->
            if (response.code == 412) throw Moved("$path changed on the server")
            if (response.code == 404) return@call
            check(response, path)
        }
    }

    /**
     * Ask the server once, before anything is kept: that the address is a WebDAV folder these
     * credentials open, and that the notes folder is there, made if it was not. Throws
     * [Unreachable], [Untrusted], [Refused] or [NotWebDav], or any other [IOException] with the
     * server's answer, when it is not so.
     */
    fun probe() {
        try {
            propfind(base, 0)
        } catch (_: Missing) {
            throw NotWebDav("nothing at $base")
        }
        if (folder.isEmpty()) return
        try {
            propfind(root, 0)
        } catch (_: Missing) {
            makeFolder()
            try {
                propfind(root, 0)
            } catch (_: Missing) {
                throw IOException("the folder could not be made")
            }
        }
    }

    /** The version of the file at [path] as the server lists it now, or null if it is not there. */
    private fun stat(path: String): String? =
        try {
            propfind(url(path), 0).firstOrNull()?.version ?: ""
        } catch (_: Missing) {
            null
        }

    /** The notes folder itself, and every folder above it under [base]. */
    private fun makeFolder() {
        if (folder.isNotEmpty()) mkcolAll(base, folder, "")
    }

    /** Every folder above [path], made where missing, because a PUT into nothing fails. */
    private fun makeParents(path: String) {
        val dir = path.substringBeforeLast('/', "")
        if (dir.isNotEmpty() && dir !in made) mkcolAll(root, dir, dir)
    }

    private fun mkcolAll(at: String, dir: String, remember: String) {
        var sofar = ""
        for (part in dir.split('/').filter { it.isNotEmpty() }) {
            sofar += encodePath(part) + "/"
            val target = at + sofar
            val request = request(target).method("MKCOL", null).build()
            val ok = call(request) { response ->
                // 405 is "already there", which is the usual answer. Some servers say so
                // another way, so anything else is checked by looking.
                response.code == 201 || response.code == 405
            }
            if (!ok) {
                try {
                    propfind(target, 0)
                } catch (_: Missing) {
                    throw IOException("the folder $dir could not be made")
                }
            }
        }
        if (remember.isNotEmpty()) made += remember
    }

    private fun propfind(url: String, depth: Int): List<Entry> {
        val body = PROPFIND_BODY.toRequestBody(XML)
        val request = request(url).method("PROPFIND", body).header("Depth", depth.toString()).build()
        return call(request) { response ->
            if (response.code == 404) throw Missing()
            if (response.code == 401 || response.code == 403) throw Refused("${response.code}")
            if (response.code == 405 || response.code == 501 || response.code == 200) {
                throw NotWebDav("${response.code} for PROPFIND")
            }
            check(response, url)
            if (response.code != 207) throw NotWebDav("${response.code} for PROPFIND")
            try {
                response.body!!.byteStream().use(::parseMultistatus)
            } catch (e: Exception) {
                // HTML, or nothing: a web page at that address rather than a WebDAV folder.
                throw NotWebDav(e.message ?: "not a multistatus")
            }
        }
    }

    private fun <T> call(request: Request, read: (Response) -> T): T {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            if (e is SSLException || e.cause is CertificateException) throw Untrusted(e.message ?: "certificate")
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

    companion object {
        /**
         * What was typed, as an address to use: "https://" put in front if no scheme was
         * given, spaces and the like encoded, and one slash at the end. Null if it is not an
         * address at all.
         */
        fun normalise(typed: String): String? {
            val trimmed = typed.trim()
            if (trimmed.isEmpty()) return null
            val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
            val parsed = withScheme.toHttpUrlOrNull() ?: return null
            return parsed.toString().trimEnd('/') + "/"
        }
    }
}

private val MARKDOWN = "text/markdown; charset=utf-8".toMediaType()
private val XML = "application/xml; charset=utf-8".toMediaType()
private val OCTETS = "application/octet-stream".toMediaType()

private const val PROPFIND_BODY = """<?xml version="1.0"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:getetag/><d:resourcetype/><d:getlastmodified/><d:getcontentlength/></d:prop></d:propfind>"""
