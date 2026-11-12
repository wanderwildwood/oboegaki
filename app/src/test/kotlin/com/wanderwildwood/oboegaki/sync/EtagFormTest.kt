package com.wanderwildwood.oboegaki.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An etag that came with a file is the listing's etag for that file, however the server or a
 * proxy in front of it marked it on the way out.
 */
class EtagFormTest {

    @Test fun anEtagFromAHeaderTakesTheListingsForm() {
        val listed = "\"64f1c0a2b3\""
        for (form in listOf("\"64f1c0a2b3\"", "64f1c0a2b3", "W/\"64f1c0a2b3\"", "\"64f1c0a2b3-gzip\"", "W/\"64f1c0a2b3-gzip\"", " \"64f1c0a2b3\" ", "\"64f1c0a2b3-br\"")) {
            assertEquals(form, listed, listedForm(form))
        }
        assertEquals("\"gzip\"", listedForm("\"gzip\""))
    }

    @Test fun oneFileInTwoFormsIsTheSameVersion() {
        // As a sync before this fix kept it, against what the listing says now.
        assertTrue(sameVersion("W/\"abc\"", "\"abc\""))
        assertTrue(sameVersion("\"abc-gzip\"", "\"abc\""))
        assertTrue(sameVersion("\"abc\"", "\"abc\""))
        assertTrue(sameVersion(null, null))
        assertFalse(sameVersion("\"abc\"", "\"abd\""))
        assertFalse(sameVersion("\"abc\"", null))
        assertFalse(sameVersion(null, "\"abc\""))
    }

    @Test fun aVersionMadeUpHereIsOnlyItself() {
        assertTrue(sameVersion("lm:1700000000:120", "lm:1700000000:120"))
        assertFalse(sameVersion("lm:1700000000:120", "lm:1700000000:121"))
        assertFalse(sameVersion("lm:1700000000:120", "\"lm:1700000000:120\""))
    }

    @Test fun aListingsOwnEtagIsKeptAsListed() {
        // A server that lists weak etags is answered in its own form (see MultistatusTest too).
        assertEquals("W/\"abc\"", Entry("/a.md", "W/\"abc\"", false).version)
    }
}
