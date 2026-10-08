package com.wanderwildwood.oboegaki.tasks

import com.wanderwildwood.oboegaki.sync.Account
import com.wanderwildwood.oboegaki.sync.Moved
import com.wanderwildwood.oboegaki.sync.Refused
import com.wanderwildwood.oboegaki.sync.Unreachable
import com.wanderwildwood.oboegaki.sync.Untrusted
import com.wanderwildwood.oboegaki.sync.canonicalEtag
import com.wanderwildwood.oboegaki.sync.encodePath
import com.wanderwildwood.oboegaki.sync.http
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Element
import java.io.IOException
import java.io.InputStream
import java.security.cert.CertificateException
import java.util.UUID
import javax.net.ssl.SSLException
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The reader's task lists on their Nextcloud, over CalDAV, with the app password Notes already
 * holds. Nextcloud keeps every calendar of a user, those shared with them included, under one
 * home, `remote.php/dav/calendars/<user>/`; a calendar that can hold VTODOs is a task list.
 */
class CalDavServer(
    account: Account,
    private val client: OkHttpClient = http,
) : TaskServer {

    private val user = account.user
    private val password = account.password
    private val origin = account.server.trimEnd('/').toHttpUrl().let { "${it.scheme}://${it.host}:${it.port}" }
    val home = account.server.trimEnd('/') + "/remote.php/dav/calendars/" + encodePath(user) + "/"

    private fun url(href: String) = if (href.startsWith("http://") || href.startsWith("https://")) href else origin + href

    private fun request(url: String) = Request.Builder().url(url)
        .header("Authorization", Credentials.basic(user, password))
        .header("User-Agent", "Notes (oboegaki)")

    override fun lists(): List<RemoteList> {
        val request = request(home).method("PROPFIND", LISTS_BODY.toRequestBody(XML)).header("Depth", "1").build()
        return call(request) { response ->
            if (response.code != 207) fail(response, home)
            response.body!!.byteStream().use(::parseCalendars)
        }.filter { norm(it.href) != norm(java.net.URI(home).rawPath) }
    }

    override fun items(listHref: String): List<RemoteItem> {
        val request = request(url(listHref)).method("REPORT", QUERY_BODY.toRequestBody(XML)).header("Depth", "1").build()
        return call(request) { response ->
            if (response.code != 207) fail(response, listHref)
            response.body!!.byteStream().use(::parseReport)
        }
    }

    override fun get(href: String): RemoteItem {
        val request = request(url(href)).get().build()
        return call(request) { response ->
            if (response.code == 404 || response.code == 410) throw Moved("$href is gone")
            if (!response.isSuccessful) fail(response, href)
            RemoteItem(href, response.header("ETag")?.let(::canonicalEtag), response.body!!.string())
        }
    }

    override fun put(href: String, text: String, ifMatch: String?): String? {
        val builder = request(url(href)).put(text.toRequestBody(CALENDAR))
        if (ifMatch == null) builder.header("If-None-Match", "*") else builder.header("If-Match", ifMatch)
        return call(builder.build()) { response ->
            when {
                response.code == 412 -> throw Moved("$href changed on the server")
                response.code == 403 -> throw ReadOnly("$href may not be written")
                !response.isSuccessful -> fail(response, href)
            }
            response.header("ETag")?.let(::canonicalEtag)
        }
    }

    override fun delete(href: String, etag: String?) {
        val builder = request(url(href)).delete()
        if (etag != null) builder.header("If-Match", etag)
        call(builder.build()) { response ->
            when {
                response.code == 412 -> throw Moved("$href changed on the server")
                response.code == 404 || response.code == 410 -> Unit
                response.code == 403 -> throw ReadOnly("$href may not be deleted")
                !response.isSuccessful -> fail(response, href)
            }
        }
    }

    override fun makeList(name: String): RemoteList {
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "tasks" }.take(40) +
            "-" + UUID.randomUUID().toString().take(6)
        val href = home + encodePath(slug) + "/"
        val body = MKCALENDAR_BODY.replace("%NAME%", xmlEscape(name))
        val request = request(href).method("MKCALENDAR", body.toRequestBody(XML)).build()
        call(request) { response -> if (response.code != 201) fail(response, href) }
        return RemoteList(java.net.URI(href).rawPath, name, false, null)
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

    private fun fail(response: Response, what: String): Nothing {
        if (response.code == 401) throw Refused("signed out")
        throw IOException("${response.code} for $what")
    }
}

private val XML = "application/xml; charset=utf-8".toMediaType()
private val CALENDAR = "text/calendar; charset=utf-8".toMediaType()

private const val LISTS_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/" xmlns:oc="http://owncloud.org/ns">
<d:prop><d:displayname/><d:resourcetype/><c:supported-calendar-component-set/><cs:getctag/><d:current-user-privilege-set/><oc:read-only/></d:prop>
</d:propfind>"""

private const val QUERY_BODY = """<?xml version="1.0" encoding="utf-8"?>
<c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
<d:prop><d:getetag/><c:calendar-data/></d:prop>
<c:filter><c:comp-filter name="VCALENDAR"><c:comp-filter name="VTODO"/></c:comp-filter></c:filter>
</c:calendar-query>"""

private const val MKCALENDAR_BODY = """<?xml version="1.0" encoding="utf-8"?>
<c:mkcalendar xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
<d:set><d:prop><d:displayname>%NAME%</d:displayname>
<c:supported-calendar-component-set><c:comp name="VTODO"/></c:supported-calendar-component-set>
</d:prop></d:set></c:mkcalendar>"""

private fun xmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private const val DAV = "DAV:"
private const val CALDAV = "urn:ietf:params:xml:ns:caldav"
private const val CS = "http://calendarserver.org/ns/"
private const val OC = "http://owncloud.org/ns"

private fun document(body: InputStream) = DocumentBuilderFactory.newInstance().apply {
    isNamespaceAware = true
    runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    isExpandEntityReferences = false
}.newDocumentBuilder().parse(body)

private fun Element.first(ns: String, name: String): Element? =
    getElementsByTagNameNS(ns, name).let { if (it.length > 0) it.item(0) as Element else null }

private fun Element.okProps(): List<Element> {
    val out = mutableListOf<Element>()
    val propstats = getElementsByTagNameNS(DAV, "propstat")
    for (j in 0 until propstats.length) {
        val ps = propstats.item(j) as Element
        if (ps.first(DAV, "status")?.textContent?.contains(" 200 ") != true) continue
        ps.first(DAV, "prop")?.let { out += it }
    }
    return out
}

/**
 * The lists in a calendar home's PROPFIND: calendars only (not the inbox, outbox or trash), and
 * of those only the ones whose components include VTODO, or that do not say, which by RFC 4791
 * means they take any. A list the reader may not write to (shared read-only) says so.
 */
fun parseCalendars(body: InputStream): List<RemoteList> {
    val responses = document(body).getElementsByTagNameNS(DAV, "response")
    val out = mutableListOf<RemoteList>()
    for (i in 0 until responses.length) {
        val response = responses.item(i) as Element
        val href = response.first(DAV, "href")?.textContent?.trim() ?: continue
        val props = response.okProps()
        fun prop(ns: String, name: String) = props.firstNotNullOfOrNull { it.first(ns, name) }
        val type = prop(DAV, "resourcetype") ?: continue
        if (type.getElementsByTagNameNS(CALDAV, "calendar").length == 0) continue
        val comps = prop(CALDAV, "supported-calendar-component-set")
        if (comps != null) {
            val names = comps.getElementsByTagNameNS(CALDAV, "comp").let { list -> (0 until list.length).map { (list.item(it) as Element).getAttribute("name").uppercase() } }
            if ("VTODO" !in names) continue
        }
        val privileges = prop(DAV, "current-user-privilege-set")
        val readOnly = if (privileges != null) {
            listOf("write", "write-content", "all").none { privileges.getElementsByTagNameNS(DAV, it).length > 0 }
        } else {
            prop(OC, "read-only")?.textContent?.trim()?.lowercase().let { it == "true" || it == "1" }
        }
        val name = prop(DAV, "displayname")?.textContent?.trim().orEmpty()
            .ifEmpty { norm(href).substringAfterLast('/') }
        out += RemoteList(href, name, readOnly, prop(CS, "getctag")?.textContent?.trim()?.ifEmpty { null })
    }
    return out
}

/** The tasks in a calendar-query REPORT: each one's address, etag and file. */
fun parseReport(body: InputStream): List<RemoteItem> {
    val responses = document(body).getElementsByTagNameNS(DAV, "response")
    val out = mutableListOf<RemoteItem>()
    for (i in 0 until responses.length) {
        val response = responses.item(i) as Element
        val href = response.first(DAV, "href")?.textContent?.trim() ?: continue
        val props = response.okProps()
        val data = props.firstNotNullOfOrNull { it.first(CALDAV, "calendar-data") }?.textContent ?: continue
        val etag = props.firstNotNullOfOrNull { it.first(DAV, "getetag") }?.textContent?.trim()?.ifEmpty { null }
        // XML turns every CRLF into LF; iCalendar's lines end in CRLF, as a GET returns them.
        out += RemoteItem(href, etag?.let(::canonicalEtag), data.replace("\r\n", "\n").replace("\n", "\r\n"))
    }
    return out
}
