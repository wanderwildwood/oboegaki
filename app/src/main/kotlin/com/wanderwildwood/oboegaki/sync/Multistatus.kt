package com.wanderwildwood.oboegaki.sync

import org.w3c.dom.Element
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import javax.xml.parsers.DocumentBuilderFactory

/** One entry in a WebDAV folder listing. [path] is decoded, and a folder's ends in "/". */
data class Entry(val path: String, val etag: String?, val isFolder: Boolean)

/**
 * The answer to a PROPFIND: one `<d:response>` per file or folder, each naming itself by an
 * href and carrying the properties asked for.
 *
 * Read with the platform's own XML parser rather than a pull parser, which is what makes this
 * testable on a computer as well as on the phone. A listing of a notes folder is small.
 */
fun parseMultistatus(body: InputStream): List<Entry> {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        // A server's answer is not trusted to name outside files for the parser to fetch.
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        isExpandEntityReferences = false
    }
    val document = factory.newDocumentBuilder().parse(body)
    val responses = document.getElementsByTagNameNS(DAV, "response")
    val out = mutableListOf<Entry>()
    for (i in 0 until responses.length) {
        val response = responses.item(i) as Element
        val href = response.first("href")?.textContent?.trim() ?: continue
        // Only the properties the server actually has: a 404 propstat lists the ones it lacks.
        var etag: String? = null
        var folder = false
        val propstats = response.getElementsByTagNameNS(DAV, "propstat")
        for (j in 0 until propstats.length) {
            val propstat = propstats.item(j) as Element
            val status = propstat.first("status")?.textContent ?: ""
            if (!status.contains(" 200 ")) continue
            propstat.first("getetag")?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { etag = it }
            if (propstat.first("resourcetype")?.let { it.getElementsByTagNameNS(DAV, "collection").length > 0 } == true) {
                folder = true
            }
        }
        out += Entry(path = decodeHref(href), etag = etag, isFolder = folder)
    }
    return out
}

/**
 * The path an href names, decoded. Some servers give a full URL here and some only the path;
 * both come out as the path. A literal "+" is a plus, not a space: WebDAV hrefs are
 * percent-encoded paths, not form fields.
 */
fun decodeHref(href: String): String {
    val raw = if (href.startsWith("http://") || href.startsWith("https://")) URI(href).rawPath else href
    return URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
}

/** Each segment of a path, percent-encoded for a URL, the slashes kept. */
fun encodePath(path: String): String =
    path.split('/').joinToString("/") { segment ->
        java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
    }

private const val DAV = "DAV:"

private fun Element.first(name: String): Element? =
    getElementsByTagNameNS(DAV, name).let { if (it.length > 0) it.item(0) as Element else null }
