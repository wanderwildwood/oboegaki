package com.wanderwildwood.oboegaki.notes

/** [name] inside [folder], or at the top of the notes for "". */
fun inFolder(folder: String, name: String): String = if (folder.isEmpty()) name else "$folder/$name"

/**
 * A folder for new notes as it was typed, tidied: " /Kompakt/Voice/ " is "Kompakt/Voice", and ""
 * is the top of the notes. Null where it cannot be one: a step up or a hidden name, which the
 * list would never show, a character other systems refuse in a name, or the archive, where a new
 * note would be put away before it was ever seen.
 */
fun cleanFolder(typed: String): String? {
    val parts = typed.split('/').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.any { it.startsWith(".") || it.any { c -> c in FORBIDDEN || c.code < 32 } }) return null
    if (parts.firstOrNull()?.equals(ARCHIVE_FOLDER, ignoreCase = true) == true) return null
    return parts.joinToString("/")
}

/** The folders directly inside [folder] ("" for the top), from a list made by [folders]. */
fun subfolders(all: List<String>, folder: String): List<String> {
    val prefix = if (folder.isEmpty()) "" else "$folder/"
    return all.filter { it.startsWith(prefix) && it.length > prefix.length && '/' !in it.removePrefix(prefix) }
}

/** The folder [folder] is in, or "" at the top. */
fun parentFolder(folder: String): String = folder.substringBeforeLast('/', "")

private const val FORBIDDEN = "\\:*?\"<>|"
