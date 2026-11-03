package com.wanderwildwood.oboegaki.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTest {

    @Test
    fun recordingsAndScansAreServedAsWhatTheyAre() {
        assertEquals("audio/mp4", mediaType("Walk 2026-11-03.m4a"))
        assertEquals("audio/mpeg", mediaType("a.MP3"))
        assertEquals("audio/ogg", mediaType("a.opus"))
        assertEquals("audio/wav", mediaType("a.wav"))
        assertEquals("application/pdf", mediaType("Scan 2026-11-03.pdf"))
    }

    @Test
    fun anythingElseIsNotMedia() {
        assertNull(mediaType("note.md"))
        assertNull(mediaType("photo.jpg"))
        assertNull(mediaType("page.html"))
        assertNull(mediaType("pdf"))
        assertFalse(isSafeMediaName("note.md"))
        assertFalse(isSafeMediaName("photo.jpg"))
    }

    @Test
    fun aNameCannotClimbOutOfItsFolder() {
        assertTrue(isSafeMediaName("Walk.m4a"))
        assertFalse(isSafeMediaName("../Walk.m4a"))
        assertFalse(isSafeMediaName("Garden/Walk.m4a"))
        assertFalse(isSafeMediaName("..\\Walk.m4a"))
        assertFalse(isSafeMediaName(".hidden.m4a"))
        assertFalse(isSafeMediaName("a\u0000.pdf"))
        assertFalse(isSafeMediaName(""))
        assertFalse(isSafeMediaName("a".repeat(300) + ".pdf"))
    }

    @Test
    fun noRangeIsTheWholeFile() {
        assertEquals(ByteRange.Whole, byteRange(null, 1000))
        assertEquals(ByteRange.Whole, byteRange("", 1000))
    }

    @Test
    fun aBrowserSeekingAsksFromAByteToTheEnd() {
        assertEquals(ByteRange.Part(0, 999), byteRange("bytes=0-", 1000))
        assertEquals(ByteRange.Part(500, 999), byteRange("bytes=500-", 1000))
    }

    @Test
    fun aClosedRangeIsKeptToTheFile() {
        assertEquals(ByteRange.Part(10, 19), byteRange("bytes=10-19", 1000))
        assertEquals(10L, (byteRange("bytes=10-19", 1000) as ByteRange.Part).length)
        assertEquals(ByteRange.Part(900, 999), byteRange("bytes=900-5000", 1000))
    }

    @Test
    fun theLastBytes() {
        assertEquals(ByteRange.Part(900, 999), byteRange("bytes=-100", 1000))
        assertEquals(ByteRange.Part(0, 999), byteRange("bytes=-5000", 1000))
    }

    @Test
    fun pastTheEndCannotBeHad() {
        assertEquals(ByteRange.Unsatisfiable, byteRange("bytes=1000-", 1000))
        assertEquals(ByteRange.Unsatisfiable, byteRange("bytes=2000-3000", 1000))
        assertEquals(ByteRange.Unsatisfiable, byteRange("bytes=-0", 1000))
        assertEquals(ByteRange.Unsatisfiable, byteRange("bytes=0-", 0))
    }

    @Test
    fun whatThisServerDoesNotDoGetsTheWholeFile() {
        assertEquals(ByteRange.Whole, byteRange("bytes=0-1,5-9", 1000))
        assertEquals(ByteRange.Whole, byteRange("items=0-1", 1000))
        assertEquals(ByteRange.Whole, byteRange("bytes=9-5", 1000))
        assertEquals(ByteRange.Whole, byteRange("bytes=a-b", 1000))
        assertEquals(ByteRange.Whole, byteRange("bytes=-", 1000))
        assertEquals(ByteRange.Whole, byteRange("bytes=-1-2", 1000))
    }

    @Test
    fun theMediaRouteNeedsTheTokenLikeEveryOther() {
        val route = Route("GET", "/api/media")
        assertTrue(route in API_ROUTES)
        assertEquals(Gate.NO_TOKEN, gate("GET", "/api/media", emptyMap()) { it == "x".repeat(64) })
        assertEquals(Gate.NO_TOKEN, gate("GET", "/api/media", mapOf("range" to "bytes=0-")) { it == "x".repeat(64) })
        assertEquals(Gate.API, gate("GET", "/api/media", mapOf("authorization" to "Bearer " + "x".repeat(64))) { it == "x".repeat(64) })
    }
}
