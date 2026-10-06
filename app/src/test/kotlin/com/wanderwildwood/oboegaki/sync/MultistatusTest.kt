package com.wanderwildwood.oboegaki.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class MultistatusTest {

    /** Shaped like what Nextcloud sends for a Depth: 1 PROPFIND on a notes folder. */
    private val listing = """
        <?xml version="1.0"?>
        <d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:oc="http://owncloud.org/ns">
         <d:response>
          <d:href>/remote.php/dav/files/wander/Notes/</d:href>
          <d:propstat><d:prop><d:getetag>&quot;6511aa&quot;</d:getetag><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
         </d:response>
         <d:response>
          <d:href>/remote.php/dav/files/wander/Notes/field%20notes/</d:href>
          <d:propstat><d:prop><d:getetag>&quot;6511ab&quot;</d:getetag><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
         </d:response>
         <d:response>
          <d:href>/remote.php/dav/files/wander/Notes/salt%2Bpepper.md</d:href>
          <d:propstat><d:prop><d:getetag>&quot;abc123&quot;</d:getetag><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
          <d:propstat><d:prop><d:getcontentlength/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
         </d:response>
        </d:multistatus>
    """.trimIndent()

    @Test
    fun readsFoldersFilesAndEtags() {
        val entries = parseMultistatus(listing.byteInputStream())
        assertEquals(
            listOf(
                Entry("/remote.php/dav/files/wander/Notes/", "\"6511aa\"", true),
                Entry("/remote.php/dav/files/wander/Notes/field notes/", "\"6511ab\"", true),
                Entry("/remote.php/dav/files/wander/Notes/salt+pepper.md", "\"abc123\"", false),
            ),
            entries,
        )
    }

    @Test
    fun fullUrlHrefsComeOutAsPaths() {
        assertEquals("/dav/Notes/a b.md", decodeHref("https://cloud.example/dav/Notes/a%20b.md"))
    }

    @Test
    fun pathsAreEncodedSegmentBySegment() {
        assertEquals("field%20notes/salt%2Bpepper%20%28this%20phone%29.md", encodePath("field notes/salt+pepper (this phone).md"))
    }

    /** Shaped like a server that keeps no etags and names everything by its full URL. */
    private val plain = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:multistatus xmlns:D="DAV:">
         <D:response>
          <D:href>https://dav.example/files/Notes</D:href>
          <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
         </D:response>
         <D:response>
          <D:href>https://dav.example/files/Notes/field%20notes</D:href>
          <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
         </D:response>
         <D:response>
          <D:href>https://dav.example/files/Notes/bread%20(rye).md</D:href>
          <D:propstat><D:prop><D:resourcetype/><D:getlastmodified>Tue, 06 Oct 2026 11:56:40 GMT</D:getlastmodified><D:getcontentlength>42</D:getcontentlength></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
          <D:propstat><D:prop><D:getetag/></D:prop><D:status>HTTP/1.1 404 Not Found</D:status></D:propstat>
         </D:response>
        </D:multistatus>
    """.trimIndent()

    @Test
    fun aServerWithoutEtagsGivesADateAndLengthVersion() {
        val entries = parseMultistatus(plain.byteInputStream())
        assertEquals("/files/Notes/", entries[0].path)
        assertEquals(true, entries[0].isFolder)
        // A folder's trailing slash is put back where the server left it off.
        assertEquals("/files/Notes/field notes/", entries[1].path)
        val note = entries[2]
        assertEquals("/files/Notes/bread (rye).md", note.path)
        assertEquals(null, note.etag)
        assertEquals(1791287800000L, note.modified)
        assertEquals(42L, note.length)
        assertEquals("lm:1791287800:42", note.version)
    }

    @Test
    fun etagsAreComparedQuoted() {
        assertEquals("\"abc\"", Entry("/a.md", "abc", false).version)
        assertEquals("\"abc\"", Entry("/a.md", "\"abc\"", false).version)
        assertEquals("W/\"abc\"", Entry("/a.md", "W/\"abc\"", false).version)
        assertEquals(true, isEtag("\"abc\""))
        assertEquals(false, isEtag("lm:1:2"))
    }

    @Test
    fun doubledSlashesInAnHrefAreOne() {
        assertEquals("/dav/Notes/a.md", decodeHref("/dav//Notes/a.md"))
    }
}
