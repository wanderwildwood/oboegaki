package com.wanderwildwood.oboegaki.desktop

import com.wanderwildwood.oboegaki.notes.isSound

/*
 * Voice notes and scans for the page: which files may be asked for, what they are, and which
 * part of one a request wants. Pure, so each rule is tested without a phone.
 *
 * A file is served only by its note and its name, and only if that note embeds it, the same
 * rule as for pictures. Requests carry the token in the Authorization header like every other
 * route: there is no link with a token in it, so the page fetches the file and plays or shows
 * it from memory. Range requests are answered (as Messaging's server answers them for audio),
 * so a long recording can also be asked for in parts.
 */

/** Whether [name] is a recording or a scan the page may ask for: a plain file name, no climbing out of a folder. */
fun isSafeMediaName(name: String): Boolean =
    name.isNotEmpty() && name.length <= 255 && '/' !in name && '\\' !in name && !name.startsWith(".") &&
        name.none { it.code < 32 } && mediaType(name) != null

/** The type a browser plays or shows [name] as, or null if it is neither a recording nor a scan. */
fun mediaType(name: String): String? {
    val ext = name.substringAfterLast('.', "").lowercase()
    if (ext == "pdf") return "application/pdf"
    if (!isSound(name)) return null
    return when (ext) {
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/ogg"
        "aac" -> "audio/aac"
        else -> null
    }
}

/** The largest recording or scan served: far past an hour of voice. */
const val MEDIA_BYTES = 256L * 1024 * 1024

/** What a Range header comes to against a file of [total] bytes. */
sealed class ByteRange {
    /** No Range header (or one this server does not do, such as several ranges): the whole file, 200. */
    object Whole : ByteRange()
    /** Bytes [start] to [end], both included: 206. */
    data class Part(val start: Long, val end: Long) : ByteRange() {
        val length get() = end - start + 1
    }
    /** A range past the end of the file: 416. */
    object Unsatisfiable : ByteRange()
}

/**
 * One `bytes=` range, as browsers send for audio: `start-`, `start-end` or `-suffix`. Several
 * ranges, or anything malformed, is answered with the whole file, which RFC 9110 allows.
 */
fun byteRange(header: String?, total: Long): ByteRange {
    val h = header?.trim() ?: return ByteRange.Whole
    if (!h.startsWith("bytes=")) return ByteRange.Whole
    val spec = h.removePrefix("bytes=").trim()
    if (',' in spec || spec.count { it == '-' } != 1) return ByteRange.Whole
    val first = spec.substringBefore('-').trim()
    val last = spec.substringAfter('-').trim()
    if (first.isEmpty()) {
        // The last N bytes.
        val n = last.toLongOrNull()?.takeIf { it >= 0 } ?: return ByteRange.Whole
        if (n == 0L || total == 0L) return ByteRange.Unsatisfiable
        return ByteRange.Part(maxOf(0, total - n), total - 1)
    }
    val start = first.toLongOrNull()?.takeIf { it >= 0 } ?: return ByteRange.Whole
    val end = if (last.isEmpty()) Long.MAX_VALUE else (last.toLongOrNull() ?: return ByteRange.Whole)
    if (end < start) return ByteRange.Whole
    if (start >= total) return ByteRange.Unsatisfiable
    return ByteRange.Part(start, minOf(end, total - 1))
}
