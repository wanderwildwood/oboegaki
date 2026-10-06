package com.wanderwildwood.oboegaki.sync

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * A WebDAV server in memory, answering through an OkHttp interceptor so that [WebDavRemote]
 * is tested whole, requests and all, without a network.
 *
 * It is made awkward on purpose, the way real servers are: by default it keeps no etags, so a
 * file's version is its date and length; it gives hrefs as full URLs, with folders' trailing
 * slashes left off and only spaces encoded; and it ignores If-Match and If-None-Match.
 */
private class FakeDav(
    val etags: Boolean = false,
    val absolute: Boolean = true,
    val honoursConditions: Boolean = false,
    val password: String = "secret",
) : Interceptor {
    val files = mutableMapOf<String, ByteArray>()
    val dates = mutableMapOf<String, Long>()
    val tags = mutableMapOf<String, String>()
    val folders = mutableSetOf("")
    private var clock = 1_790_000_000_000L
    private var counter = 0
    val methods = mutableListOf<String>()

    /** Written on the server's side, as an editor on a computer would. */
    fun write(path: String, text: String) = store(path, text.toByteArray())

    fun text(path: String): String? = files[path]?.toString(Charsets.UTF_8)

    private fun store(path: String, bytes: ByteArray) {
        files[path] = bytes
        // A second on: the date is all a server without etags has to go on.
        clock += 1000
        dates[path] = clock
        tags[path] = "\"x${++counter}\""
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        methods += request.method
        val auth = request.header("Authorization")
        if (auth != okhttp3.Credentials.basic("reader", password, Charsets.UTF_8)) return answer(request, 401, "")
        val raw = decodeHref(request.url.encodedPath)
        if (!raw.startsWith(PREFIX)) return answer(request, 404, "")
        val path = raw.removePrefix(PREFIX).trimEnd('/')
        return when (request.method) {
            "PROPFIND" -> propfind(request, path)
            "GET" -> files[path]?.let { bytes ->
                val headers = mutableMapOf("Last-Modified" to httpDate(dates[path]!!))
                if (etags) headers["ETag"] = tags[path]!!
                answer(request, 200, bytes, headers)
            } ?: answer(request, 404, "")
            "PUT" -> {
                if (honoursConditions) {
                    val match = request.header("If-Match")
                    if (request.header("If-None-Match") == "*" && path in files) return answer(request, 412, "")
                    if (match != null && tags[path] != match) return answer(request, 412, "")
                }
                if (path.substringBeforeLast('/', "") !in folders) return answer(request, 409, "")
                val buffer = Buffer()
                request.body!!.writeTo(buffer)
                store(path, buffer.readByteArray())
                answer(request, 201, "", if (etags) mapOf("ETag" to tags[path]!!) else emptyMap())
            }
            "DELETE" -> if (files.remove(path) != null) answer(request, 204, "") else answer(request, 404, "")
            "MKCOL" -> when {
                path in folders || path in files -> answer(request, 405, "")
                path.substringBeforeLast('/', "") !in folders -> answer(request, 409, "")
                else -> {
                    folders += path
                    answer(request, 201, "")
                }
            }
            else -> answer(request, 405, "")
        }
    }

    private fun propfind(request: Request, path: String): Response {
        val depth = request.header("Depth")
        if (depth != "0" && depth != "1") return answer(request, 403, "")
        val entries = mutableListOf<String>()
        when {
            path in files -> entries += fileEntry(path)
            path in folders -> {
                entries += folderEntry(path)
                if (depth == "1") {
                    val prefix = if (path.isEmpty()) "" else "$path/"
                    folders.filter { it.startsWith(prefix) && it != path && '/' !in it.removePrefix(prefix) }.forEach { entries += folderEntry(it) }
                    files.keys.filter { it.startsWith(prefix) && '/' !in it.removePrefix(prefix) }.forEach { entries += fileEntry(it) }
                }
            }
            else -> return answer(request, 404, "")
        }
        val body = """<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">${entries.joinToString("")}</D:multistatus>"""
        return answer(request, 207, body)
    }

    private fun href(path: String): String {
        val encoded = (PREFIX + path).replace(" ", "%20")
        return if (absolute) "http://dav.test$encoded" else encoded
    }

    private fun folderEntry(path: String) =
        "<D:response><D:href>${href(path)}</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>" +
            "<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"

    private fun fileEntry(path: String): String {
        val etag = if (etags) "<D:getetag>${tags[path]}</D:getetag>" else ""
        return "<D:response><D:href>${href(path)}</D:href><D:propstat><D:prop><D:resourcetype/>$etag" +
            "<D:getlastmodified>${httpDate(dates[path]!!)}</D:getlastmodified>" +
            "<D:getcontentlength>${files[path]!!.size}</D:getcontentlength></D:prop>" +
            "<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
    }

    private fun answer(request: Request, code: Int, body: String, headers: Map<String, String> = emptyMap()) =
        answer(request, code, body.toByteArray(), headers)

    private fun answer(request: Request, code: Int, body: ByteArray, headers: Map<String, String> = emptyMap()): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("fake")
            .body(body.toResponseBody(if (code == 207) "application/xml".toMediaType() else null))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }

    companion object {
        const val PREFIX = "/dav/my files/"

        fun httpDate(millis: Long): String =
            DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC))
    }
}

class WebDavTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun remote(server: Interceptor, password: String = "secret", folder: String = "Notes") = WebDavRemote(
        base = "http://dav.test/dav/my%20files/",
        user = "reader",
        password = password,
        folder = folder,
        client = OkHttpClient.Builder().addInterceptor(server).build(),
    )

    private inner class Phone(name: String) {
        val root = temp.newFolder(name)
        val notes = File(root, "notes")
        val sync = Sync(notes, File(root, "base"), File(root, "state"))
        fun write(path: String, text: String) = File(notes, path).apply { parentFile?.mkdirs() }.writeText(text)
        fun read(path: String): String? = File(notes, path).takeIf { it.isFile }?.readText()
    }

    @Test
    fun notesGoBothWaysWithNoEtagsAndFullUrlHrefs() {
        val server = FakeDav()
        val his = Phone("his")
        val hers = Phone("hers")
        his.write("groceries.md", "- [ ] milk\n")
        his.write("field notes/salt+pepper (1).md", "both")
        his.sync.run(remote(server))
        assertEquals("- [ ] milk\n", server.text("Notes/groceries.md"))
        assertEquals("both", server.text("Notes/field notes/salt+pepper (1).md"))

        hers.sync.run(remote(server))
        assertEquals("- [ ] milk\n", hers.read("groceries.md"))
        assertEquals("both", hers.read("field notes/salt+pepper (1).md"))

        // Nothing changed: a second sync on each sends nothing and fetches at most once.
        his.sync.run(remote(server))
        hers.sync.run(remote(server))
        server.methods.clear()
        val again = his.sync.run(remote(server))
        assertEquals(0, again.sent)
        assertEquals(0, again.received)
        assertFalse("PUT" in server.methods)
    }

    @Test
    fun anEditOnTheServerMergesWithOneHere() {
        val server = FakeDav()
        val phone = Phone("phone")
        phone.write("groceries.md", "- [ ] milk\n- [ ] bread\n")
        phone.sync.run(remote(server))
        phone.sync.run(remote(server))

        server.write("Notes/groceries.md", "- [ ] milk\n- [ ] bread\n- [ ] eggs\n")
        phone.write("groceries.md", "- [x] milk\n- [ ] bread\n")
        phone.sync.run(remote(server))

        val both = "- [x] milk\n- [ ] bread\n- [ ] eggs\n"
        assertEquals(both, phone.read("groceries.md"))
        assertEquals(both, server.text("Notes/groceries.md"))
    }

    @Test
    fun anEditOnTheServerAloneArrives() {
        val server = FakeDav()
        val phone = Phone("phone")
        phone.write("a.md", "one")
        phone.sync.run(remote(server))
        phone.sync.run(remote(server))
        server.write("Notes/a.md", "two")
        phone.sync.run(remote(server))
        assertEquals("two", phone.read("a.md"))
    }

    @Test
    fun deletionsAndAttachmentsAndPins() {
        val server = FakeDav()
        val his = Phone("his")
        val hers = Phone("hers")
        his.write("Voice 2026-10-06 0800.md", "![[Voice 2026-10-06 0800.m4a]]\n")
        File(his.notes, "Voice 2026-10-06 0800.m4a").writeBytes(byteArrayOf(1, 2, 3))
        his.write(PINS, "Voice 2026-10-06 0800.md\n")
        his.sync.run(remote(server))
        assertArrayEquals(byteArrayOf(1, 2, 3), server.files["Notes/Voice 2026-10-06 0800.m4a"])
        assertEquals("Voice 2026-10-06 0800.md\n", server.text("Notes/.pinned"))

        hers.sync.run(remote(server))
        assertArrayEquals(byteArrayOf(1, 2, 3), File(hers.notes, "Voice 2026-10-06 0800.m4a").readBytes())
        assertEquals("Voice 2026-10-06 0800.md\n", hers.read(PINS))

        his.sync.run(remote(server))
        File(his.notes, "Voice 2026-10-06 0800.md").delete()
        File(his.notes, "Voice 2026-10-06 0800.m4a").delete()
        his.sync.run(remote(server))
        assertNull(server.files["Notes/Voice 2026-10-06 0800.md"])
        assertNull(server.files["Notes/Voice 2026-10-06 0800.m4a"])

        hers.sync.run(remote(server))
        assertNull(hers.read("Voice 2026-10-06 0800.md"))
        assertFalse(File(hers.notes, "Voice 2026-10-06 0800.m4a").exists())
    }

    @Test
    fun aWriteIsRefusedWhenTheServerChangedEvenIfItIgnoresIfMatch() {
        val server = FakeDav(honoursConditions = false)
        val remote = remote(server)
        server.folders += "Notes"
        server.write("Notes/a.md", "first")
        val seen = remote.list().getValue("a.md")
        server.write("Notes/a.md", "someone else's")
        try {
            remote.put("a.md", "mine", Expect.Unchanged(seen))
            fail("wrote over a change it never saw")
        } catch (_: Moved) {
        }
        assertEquals("someone else's", server.text("Notes/a.md"))

        try {
            remote.put("a.md", "new", Expect.Absent)
            fail("wrote over a file it thought was not there")
        } catch (_: Moved) {
        }
        try {
            remote.delete("a.md", seen)
            fail("deleted a change it never saw")
        } catch (_: Moved) {
        }
        assertEquals("someone else's", server.text("Notes/a.md"))
    }

    @Test
    fun aVersionReadWithANoteMatchesTheListing() {
        val server = FakeDav()
        val remote = remote(server)
        server.folders += "Notes"
        server.write("Notes/a.md", "text")
        assertEquals(remote.list()["a.md"], remote.get("a.md").etag)
        assertTrue(remote.list()["a.md"]!!.startsWith(VERSION_PREFIX))
    }

    @Test
    fun aServerWithEtagsUsesThem() {
        val server = FakeDav(etags = true, absolute = false, honoursConditions = true)
        val phone = Phone("phone")
        phone.write("a.md", "one")
        phone.sync.run(remote(server))
        assertEquals(server.tags["Notes/a.md"], remote(server).list()["a.md"])
        phone.write("a.md", "two")
        phone.sync.run(remote(server))
        assertEquals("two", server.text("Notes/a.md"))
    }

    @Test
    fun theFolderIsMadeWithItsParents() {
        val server = FakeDav()
        remote(server, folder = "Writing/Notes").probe()
        assertTrue("Writing" in server.folders)
        assertTrue("Writing/Notes" in server.folders)
    }

    @Test
    fun aWrongPasswordIsRefused() {
        try {
            remote(FakeDav(), password = "wrong").probe()
            fail("let a wrong password through")
        } catch (_: Refused) {
        }
    }

    @Test
    fun aWebPageIsNotAWebDavFolder() {
        val page = Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("<html><body>Welcome</body></html>".toResponseBody("text/html".toMediaType())).build()
        }
        try {
            remote(page).probe()
            fail("took a web page for a WebDAV folder")
        } catch (_: NotWebDav) {
        }
    }

    @Test
    fun anAddressIsTidied() {
        assertEquals("https://app.example.net/dav/Files/", WebDavRemote.normalise("app.example.net/dav/Files"))
        assertEquals("http://10.0.2.2:8080/", WebDavRemote.normalise(" http://10.0.2.2:8080 "))
        assertEquals("https://dav.example/my%20files/", WebDavRemote.normalise("https://dav.example/my files/"))
        assertNull(WebDavRemote.normalise(""))
        assertNull(WebDavRemote.normalise("https://"))
    }
}
