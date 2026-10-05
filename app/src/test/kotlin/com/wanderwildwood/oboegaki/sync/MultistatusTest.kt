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
}
