package com.wanderwildwood.oboegaki.desktop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32

/** A picture from the computer: only JPEG, PNG or WebP, never too big, and kept without Exif, GPS or anything else. */
class UploadTest {

    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private fun has(hay: ByteArray, needle: String): Boolean = String(hay, Charsets.ISO_8859_1).contains(needle)

    /** Small real pictures, 24 x 16, made for this test. */
    private val JPEG = "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAAQABgDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDzK20Tp8lbFtonT5K6+20Tp8tbFtonT5azr5p5nBlec7anIW2idPkor0e20Tp8tFeVPNNdz73DZz+7Wp//2Q=="
    private val PNG = "iVBORw0KGgoAAAANSUhEUgAAABgAAAAQCAIAAACDRijCAAAAIElEQVR4nGNkYIjiYmCgHLEw8DNQBYwaNGrQqEEj1SAAI7ICX8ntX70AAAAASUVORK5CYII="

    private fun image(format: String): ByteArray = Base64.getDecoder().decode(if (format == "jpg") JPEG else PNG)

    /** Width and height as the JDK's own decoder reads them (through reflection: the test classpath is Android's), or null. */
    private fun decodes(bytes: ByteArray): Pair<Int, Int>? {
        val io = Class.forName("javax.imageio.ImageIO")
        val img = io.getMethod("read", java.io.InputStream::class.java).invoke(null, ByteArrayInputStream(bytes)) ?: return null
        return (img.javaClass.getMethod("getWidth").invoke(img) as Int) to (img.javaClass.getMethod("getHeight").invoke(img) as Int)
    }

    /** An Exif block (little-endian) with Orientation 6 and a GPS pointer, and the words a camera would leave. */
    private fun exifSegment(): ByteArray {
        val tiff = ByteArrayOutputStream()
        tiff.write(ascii("II")); tiff.write(byteArrayOf(42, 0, 8, 0, 0, 0))
        tiff.write(byteArrayOf(2, 0)) // two entries
        tiff.write(byteArrayOf(0x12, 0x01, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0)) // Orientation = 6
        tiff.write(byteArrayOf(0x25, 0x88.toByte(), 4, 0, 1, 0, 0, 0, 38, 0, 0, 0)) // GPS IFD pointer
        tiff.write(byteArrayOf(0, 0, 0, 0))
        tiff.write(ascii("GPSLatitude 40.7128 N GPSLongitude 74.0060 W Ada Whitlock's camera"))
        val payload = ascii("Exif\u0000\u0000") + tiff.toByteArray()
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + payload
    }

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (len shr 8).toByte(), len.toByte()) + payload
    }

    /** A real JPEG with Exif (GPS), XMP, a comment, and a second "picture" after its end, as some phones write. */
    private fun dirtyJpeg(): ByteArray {
        val plain = image("jpg")
        val extra = exifSegment() +
            segment(0xE1, ascii("http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta>GPSAltitude</x:xmpmeta>")) +
            segment(0xED, ascii("Photoshop 3.0\u0000IPTC city")) +
            segment(0xFE, ascii("secret comment"))
        return plain.copyOfRange(0, 2) + extra + plain.copyOfRange(2, plain.size) + ascii("TRAILER motion photo")
    }

    @Test
    fun aJpegLosesItsExifAndGpsButStaysUpright() {
        val dirty = dirtyJpeg()
        assertNotNull(decodes(dirty))
        val clean = cleanUpload(dirty)
        assertEquals("jpg", clean.ext)
        for (word in listOf("GPS", "Ada", "xmpmeta", "IPTC", "secret", "TRAILER", "Photoshop")) {
            assertFalse(word, has(clean.bytes, word))
        }
        assertTrue(has(dirty, "GPSLatitude"))
        // Still a picture that reads, the same size, ending where a JPEG ends.
        assertEquals(24 to 16, decodes(clean.bytes))
        assertEquals(0xD9, clean.bytes.last().toInt() and 0xFF)
        // The orientation is kept, alone.
        val app1 = String(clean.bytes, Charsets.ISO_8859_1).indexOf("Exif\u0000\u0000")
        assertTrue(app1 > 0)
        assertEquals(6, exifOrientation(clean.bytes, app1 + 6))
        // Cleaning twice changes nothing more.
        assertArrayEquals(clean.bytes, cleanUpload(clean.bytes).bytes)
    }

    @Test
    fun anUprightJpegGetsNoExifAtAll() {
        val clean = cleanUpload(image("jpg"))
        assertFalse(has(clean.bytes, "Exif"))
        assertNotNull(decodes(clean.bytes))
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val len = data.size
        val crc = CRC32().apply { update(ascii(type)); update(data) }.value
        return byteArrayOf((len shr 24).toByte(), (len shr 16).toByte(), (len shr 8).toByte(), len.toByte()) +
            ascii(type) + data +
            byteArrayOf((crc shr 24).toByte(), (crc shr 16).toByte(), (crc shr 8).toByte(), crc.toByte())
    }

    @Test
    fun aPngLosesItsTextAndExifChunks() {
        val plain = image("png")
        val afterHeader = 8 + 25 // signature, IHDR
        val dirty = plain.copyOfRange(0, afterHeader) +
            chunk("tEXt", ascii("Comment\u0000GPS 40.7128 N")) +
            chunk("eXIf", exifSegment().copyOfRange(10, exifSegment().size)) +
            chunk("tIME", byteArrayOf(7, 0xEA.toByte(), 11, 3, 9, 0, 0)) +
            plain.copyOfRange(afterHeader, plain.size) + ascii("TRAILER")
        assertNotNull(decodes(dirty))
        val clean = cleanUpload(dirty)
        assertEquals("png", clean.ext)
        for (word in listOf("tEXt", "eXIf", "tIME", "GPS", "Ada", "TRAILER")) assertFalse(word, has(clean.bytes, word))
        assertEquals(24 to 16, decodes(clean.bytes))
        assertArrayEquals(plain, clean.bytes)
    }

    private fun riffChunk(type: String, data: ByteArray): ByteArray {
        val len = data.size
        val pad = if (len % 2 == 1) byteArrayOf(0) else byteArrayOf()
        return ascii(type) + byteArrayOf(len.toByte(), (len shr 8).toByte(), (len shr 16).toByte(), (len shr 24).toByte()) + data + pad
    }

    private fun riff(chunks: ByteArray): ByteArray {
        val size = chunks.size + 4
        return ascii("RIFF") + byteArrayOf(size.toByte(), (size shr 8).toByte(), (size shr 16).toByte(), (size shr 24).toByte()) + ascii("WEBP") + chunks
    }

    @Test
    fun aWebpLosesItsExifAndXmpChunksAndTheirFlags() {
        val vp8x = byteArrayOf(0x0C, 0, 0, 0, 23, 0, 0, 15, 0, 0) // Exif + XMP flags, 24 x 16
        val dirty = riff(riffChunk("VP8X", vp8x) + riffChunk("VP8 ", ByteArray(31) { 1 }) +
            riffChunk("EXIF", exifSegment().copyOfRange(10, exifSegment().size)) + riffChunk("XMP ", ascii("<x>GPSAltitude</x>")))
        val clean = cleanUpload(dirty)
        assertEquals("webp", clean.ext)
        for (word in listOf("EXIF", "XMP ", "GPS", "Ada")) assertFalse(word, has(clean.bytes, word))
        assertEquals(riff(riffChunk("VP8X", byteArrayOf(0, 0, 0, 0, 23, 0, 0, 15, 0, 0)) + riffChunk("VP8 ", ByteArray(31) { 1 })).toList(), clean.bytes.toList())
    }

    private fun refused(status: Int, bytes: ByteArray) {
        try {
            cleanUpload(bytes)
            fail("kept something it should have refused")
        } catch (r: Refusal) {
            assertEquals(status, r.status)
        }
    }

    @Test
    fun anythingButAJpegPngOrWebpIsRefused() {
        refused(415, ascii("GIF89a") + ByteArray(40))
        refused(415, ascii("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"))
        refused(415, ascii("<!doctype html><title>x</title>"))
        refused(415, ascii("%PDF-1.7\n"))
        refused(415, ascii("....ftypheic") + ByteArray(40))
        refused(400, ByteArray(0))
        // Named or labeled as a picture is not enough: a JPEG's first bytes and then nothing that reads.
        refused(415, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 2) + ascii("<script>"))
        // A JPEG cut off before its end.
        val jpeg = image("jpg")
        refused(415, jpeg.copyOfRange(0, jpeg.size / 2))
        // A PNG with a chunk that does not match its checksum.
        val png = image("png").copyOf()
        png[20] = (png[20] + 1).toByte()
        refused(415, png)
        // A WebP with no picture in it.
        refused(415, riff(riffChunk("EXIF", ascii("GPS"))))
    }

    @Test
    fun aPictureOverEightMegabytesIsRefused() {
        val big = ByteArray(Limits.PICTURE_BYTES + 1).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() }
        refused(413, big)
        // Before a byte is read, by the length the browser declares.
        for ((declared, status) in listOf((Limits.PICTURE_BYTES + 1).toString() to 413, "99999999999" to 413, null to 400, "0" to 400, "-5" to 400, "lots" to 400)) {
            try {
                uploadLength(declared)
                fail("took $declared")
            } catch (r: Refusal) {
                assertEquals(declared.toString(), status, r.status)
            }
        }
        assertEquals(Limits.PICTURE_BYTES, uploadLength(Limits.PICTURE_BYTES.toString()))
    }
}
