package com.wanderwildwood.oboegaki.notes

/** What a share from another app becomes: a note's title and what it says. */
data class SharedText(val title: String, val text: String)

private val URL = Regex("""https?://[^\s<>"]+""")

/**
 * Text shared in from another app. An article or a page shared with its name as the subject
 * becomes a note called that, holding a Markdown link, `[name](address)`, and whatever else
 * came with it; the bare address is not said twice, and neither is the name. Anything without
 * both a subject and an address is kept as it came.
 */
fun sharedText(subject: String?, text: String): SharedText {
    val name = subject?.trim().orEmpty()
    val found = URL.find(text)
    if (name.isEmpty() || found == null) return SharedText(name, text)
    val url = trimmedUrl(found.value)
    val rest = text.replaceFirst(url, "").lines().map { it.trimEnd() }
        .dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        .joinToString("\n").trim()
    val link = "[${escapeLinkText(name)}](${escapeLinkTarget(url)})"
    val body = if (rest.isEmpty() || rest == name) "$link\n" else "$link\n\n$rest\n"
    return SharedText(fileNameFor(name), body)
}

/**
 * A sentence's full stop, or a bracket around the address, is not part of it; a closing
 * bracket the address itself opened is, as in a wiki's "Apple_(fruit)".
 */
private fun trimmedUrl(found: String): String {
    var url = found
    while (url.isNotEmpty()) {
        val last = url.last()
        url = when {
            last in ".,;:!?'" -> url.dropLast(1)
            last == ')' && url.count { it == ')' } > url.count { it == '(' } -> url.dropLast(1)
            last == ']' && url.count { it == ']' } > url.count { it == '[' } -> url.dropLast(1)
            else -> return url
        }
    }
    return url
}

private fun escapeLinkText(s: String): String =
    s.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]").replace("\n", " ")

private fun escapeLinkTarget(s: String): String =
    s.replace("(", "%28").replace(")", "%29").replace(" ", "%20")

/**
 * A note's words for a calendar event's description: the lines that only embed a recording,
 * a scan or a picture left out, since a calendar cannot show them, and no longer than [limit].
 */
fun reminderText(text: String, limit: Int = 1000): String {
    val kept = text.lines()
        .filterNot { line -> line.trim().let { it.startsWith("![[") && it.endsWith("]]") } }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
    return if (kept.length <= limit) kept else kept.take(limit - 1).trimEnd() + "…"
}
