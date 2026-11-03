package com.wanderwildwood.oboegaki.desktop

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/*
 * A picture sent from the computer, made fit to keep beside a note. Pure, so every rule is
 * tested without a phone.
 *
 * What it is comes from its first bytes, never from its name or the type the browser claims:
 * JPEG, PNG or WebP, the three a note shows, and nothing else. It is then rewritten with only
 * what draws the picture: no Exif (where a camera puts GPS, the time and the phone's make), no
 * XMP, IPTC, comments, text chunks or embedded thumbnails, and nothing after the picture's end
 * (where some phones hide a second picture or a video). A JPEG keeps which way up it is, as a
 * one-entry Exif, so it is not shown on its side. Anything that does not read through cleanly
 * is refused rather than kept as it came.
 */

/** A picture ready to keep: its bytes and the extension its kind gets. */
class CleanPicture(val bytes: ByteArray, val ext: String)

/**
 * The picture in [bytes], cleaned, or a refusal: 400 for nothing at all, 413 past
 * [Limits.PICTURE_BYTES], 415 for anything that is not a JPEG, PNG or WebP that reads through.
 */
fun cleanUpload(bytes: ByteArray): CleanPicture {
    if (bytes.isEmpty()) throw Refusal(400, "there is no picture")
    if (bytes.size > Limits.PICTURE_BYTES) throw Refusal(413, "that picture is over 8 MB")
    val clean = when {
        isJpeg(bytes) -> cleanJpeg(bytes)?.let { CleanPicture(it, "jpg") }
        isPng(bytes) -> cleanPng(bytes)?.let { CleanPicture(it, "png") }
        isWebp(bytes) -> cleanWebp(bytes)?.let { CleanPicture(it, "webp") }
        else -> null
    }
    return clean ?: throw Refusal(415, "only a JPEG, PNG or WebP picture can be added")
}

/** The length a browser says it is sending, checked before a byte of it is read. */
fun uploadLength(declared: String?): Int {
    val n = declared?.trim()?.toLongOrNull() ?: throw Refusal(400, "no length")
    if (n <= 0) throw Refusal(400, "there is no picture")
    if (n > Limits.PICTURE_BYTES) throw Refusal(413, "that picture is over 8 MB")
    return n.toInt()
}

private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
private fun u16be(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
private fun u32be(b: ByteArray, i: Int) = (u16be(b, i).toLong() shl 16) or u16be(b, i + 2).toLong()
private fun u32le(b: ByteArray, i: Int) =
    u8(b, i).toLong() or (u8(b, i + 1).toLong() shl 8) or (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 3).toLong() shl 24)

private fun startsWith(b: ByteArray, at: Int, ascii: String): Boolean =
    b.size >= at + ascii.length && ascii.indices.all { b[at + it] == ascii[it].code.toByte() }

private fun isJpeg(b: ByteArray) = b.size >= 3 && u8(b, 0) == 0xFF && u8(b, 1) == 0xD8 && u8(b, 2) == 0xFF
private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private fun isPng(b: ByteArray) = b.size >= 8 && (0 until 8).all { b[it] == PNG_SIGNATURE[it] }
private fun isWebp(b: ByteArray) = startsWith(b, 0, "RIFF") && startsWith(b, 8, "WEBP")

// ------------------------------------------------------------------------------------ JPEG

private val SOF = setOf(0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF)

/**
 * Walk the JPEG's segments and keep only what draws it: the tables, the frame, the scans, a
 * color profile and Adobe's color marker. JFIF stays without its thumbnail. Exif, XMP and every
 * other APPn, and comments, go; the orientation is put back alone. Stops at the end marker.
 */
internal fun cleanJpeg(b: ByteArray): ByteArray? {
    val n = b.size
    if (!isJpeg(b)) return null
    var jfif: ByteArray? = null
    var orientation = 1
    var frame = false
    var scanned = false
    val body = ByteArrayOutputStream(n)
    var i = 2
    while (true) {
        if (scanned) {
            // Entropy-coded data, up to the next marker that is not a stuffed byte or a restart.
            val start = i
            while (i < n) {
                if (u8(b, i) == 0xFF) {
                    if (i + 1 >= n) return null
                    val x = u8(b, i + 1)
                    if (x == 0x00 || x in 0xD0..0xD7) { i += 2; continue }
                    if (x == 0xFF) { i += 1; continue }
                    break
                }
                i++
            }
            body.write(b, start, i - start)
        }
        if (i + 1 >= n || u8(b, i) != 0xFF) return null
        var m = u8(b, i + 1)
        while (m == 0xFF) {
            i++
            if (i + 1 >= n) return null
            m = u8(b, i + 1)
        }
        i += 2
        if (m == 0xD9) {
            if (!scanned) return null
            body.write(0xFF); body.write(0xD9)
            break // anything after the end is not the picture
        }
        if (m == 0x01 || m in 0xD0..0xD7) {
            body.write(0xFF); body.write(m)
            continue
        }
        if (m == 0xD8 || m == 0x00) return null
        if (i + 2 > n) return null
        val len = u16be(b, i)
        if (len < 2 || i + len > n) return null
        val at = i + 2
        val size = len - 2
        val payload = b.copyOfRange(at, at + size)
        i += len
        fun keep() { body.write(0xFF); body.write(m); body.write(b, at - 2, len) }
        when {
            m == 0xE0 -> if (jfif == null && startsWith(payload, 0, "JFIF\u0000") && size >= 14) jfif = jfifWithoutThumbnail(payload)
            m == 0xE1 -> if (orientation == 1 && startsWith(payload, 0, "Exif\u0000\u0000")) orientation = exifOrientation(payload, 6) ?: 1
            m == 0xE2 -> if (startsWith(payload, 0, "ICC_PROFILE\u0000")) keep()
            m == 0xEE -> if (startsWith(payload, 0, "Adobe")) keep()
            m in 0xE3..0xEF || m == 0xFE -> Unit
            else -> {
                if (m in SOF) frame = true
                if (m == 0xDA && !frame) return null
                keep()
                if (m == 0xDA) scanned = true
            }
        }
    }
    val out = ByteArrayOutputStream(body.size() + 64)
    out.write(0xFF); out.write(0xD8)
    jfif?.let { out.write(it) }
    if (orientation in 2..8) out.write(orientationExif(orientation))
    body.writeTo(out)
    return out.toByteArray()
}

/** The JFIF segment, marker and all, with its thumbnail's size set to none and the thumbnail dropped. */
private fun jfifWithoutThumbnail(payload: ByteArray): ByteArray {
    val p = payload.copyOfRange(0, 14)
    p[12] = 0; p[13] = 0
    return byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0, (p.size + 2).toByte()) + p
}

/** The Orientation (1 to 8) in an Exif block whose TIFF header starts at [tiff], or null. */
internal fun exifOrientation(b: ByteArray, tiff: Int): Int? {
    if (b.size < tiff + 8) return null
    val little = when {
        startsWith(b, tiff, "II") -> true
        startsWith(b, tiff, "MM") -> false
        else -> return null
    }
    fun s(i: Int) = if (little) u8(b, i) or (u8(b, i + 1) shl 8) else u16be(b, i)
    fun l(i: Int) = if (little) u32le(b, i) else u32be(b, i)
    if (s(tiff + 2) != 42) return null
    val ifd = tiff + l(tiff + 4)
    if (ifd < tiff + 8 || ifd + 2 > b.size) return null
    val count = s(ifd.toInt())
    for (e in 0 until count) {
        val at = ifd.toInt() + 2 + e * 12
        if (at + 12 > b.size) return null
        if (s(at) == 0x0112 && s(at + 2) == 3) return s(at + 8).takeIf { it in 1..8 }
    }
    return null
}

/** An APP1 segment holding nothing but the Orientation: what a viewer needs to stand it upright. */
internal fun orientationExif(orientation: Int): ByteArray {
    val payload = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(
        'M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8, // TIFF header, IFD0 at 8
        0, 1, // one entry
        0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0, // Orientation, SHORT, 1
        0, 0, 0, 0, // no next IFD
    )
    val len = payload.size + 2
    return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + payload
}

// ------------------------------------------------------------------------------------ PNG

/** The chunks that draw a PNG. Text, Exif, time and anything unknown are left out. */
private val PNG_KEEP = setOf("IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT", "pHYs", "bKGD", "acTL", "fcTL", "fdAT")

internal fun cleanPng(b: ByteArray): ByteArray? {
    if (!isPng(b)) return null
    val out = ByteArrayOutputStream(b.size)
    out.write(b, 0, 8)
    var i = 8
    var first = true
    var data = false
    while (true) {
        if (i + 12 > b.size) return null
        val len = u32be(b, i)
        if (len > b.size - i - 12L) return null
        val type = String(b, i + 4, 4, Charsets.ISO_8859_1)
        if (!type.all { it in 'A'..'Z' || it in 'a'..'z' }) return null
        val end = i + 12 + len.toInt()
        val crc = CRC32().apply { update(b, i + 4, 4 + len.toInt()) }.value
        if (crc != u32be(b, end - 4)) return null
        if (first && type != "IHDR") return null
        first = false
        if (type == "IDAT") data = true
        if (type in PNG_KEEP) out.write(b, i, end - i)
        i = end
        if (type == "IEND") break
    }
    return if (data) out.toByteArray() else null
}

// ------------------------------------------------------------------------------------ WebP

/** The chunks that draw a WebP. EXIF, XMP and unknown chunks are left out. */
private val WEBP_KEEP = setOf("VP8 ", "VP8L", "VP8X", "ALPH", "ANIM", "ANMF", "ICCP")
private val WEBP_IMAGE = setOf("VP8 ", "VP8L", "ANMF")

internal fun cleanWebp(b: ByteArray): ByteArray? {
    if (!isWebp(b) || b.size < 12) return null
    val riff = u32le(b, 4)
    if (riff < 4 || riff > b.size - 8L) return null
    val stop = 8 + riff.toInt()
    val chunks = ByteArrayOutputStream(b.size)
    var i = 12
    var image = false
    while (i < stop) {
        if (i + 8 > stop) return null
        val type = String(b, i, 4, Charsets.ISO_8859_1)
        val len = u32le(b, i + 4)
        val padded = len + (len and 1)
        if (padded > stop - i - 8L) return null
        val end = i + 8 + padded.toInt()
        if (type in WEBP_IMAGE) image = true
        if (type in WEBP_KEEP) {
            val chunk = b.copyOfRange(i, end)
            // VP8X says which extra chunks follow; the Exif and XMP flags go with the chunks.
            if (type == "VP8X" && len >= 1) chunk[8] = (chunk[8].toInt() and 0x04.inv() and 0x08.inv()).toByte()
            chunks.write(chunk)
        }
        i = end
    }
    if (!image) return null
    val size = chunks.size() + 4
    val out = ByteArrayOutputStream(size + 8)
    out.write("RIFF".toByteArray(Charsets.ISO_8859_1))
    out.write(byteArrayOf(size.toByte(), (size shr 8).toByte(), (size shr 16).toByte(), (size shr 24).toByte()))
    out.write("WEBP".toByteArray(Charsets.ISO_8859_1))
    chunks.writeTo(out)
    return out.toByteArray()
}
