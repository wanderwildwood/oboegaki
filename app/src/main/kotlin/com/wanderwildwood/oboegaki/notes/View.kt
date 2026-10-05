package com.wanderwildwood.oboegaki.notes

/** Everything the list can be narrowed to. Stored as a string so a folder can be one. */
object Showing {
    const val ALL = ""
    const val SHARED = "shared"
    const val LISTS = "lists"
    const val VOICE = "voice"
    const val SCANS = "scans"
    fun folder(path: String) = "folder:$path"
    fun folderOf(showing: String): String? = showing.removePrefix("folder:").takeIf { showing.startsWith("folder:") }
}

/**
 * The notes the list shows, in the order it shows them.
 *
 * Newest first unless asked otherwise: the note most likely wanted is the one last touched, and
 * it is at the top whichever folder it is in. A search keeps a note if every word typed is in
 * its title or in what it says, in any order and any case.
 */
fun arrange(
    notes: List<Note>,
    shared: Set<String>,
    showing: String,
    order: Order,
    query: String,
): List<Note> {
    val folder = Showing.folderOf(showing)
    val words = query.lowercase().split(' ', '\t', '\n').filter { it.isNotBlank() }
    return notes
        .filter { note ->
            when {
                folder != null -> note.folder == folder || note.folder.startsWith("$folder/")
                showing == Showing.SHARED -> note.path in shared
                showing == Showing.LISTS -> hasTasks(note.text)
                showing == Showing.VOICE -> embeds(note.text).any(::isSound)
                showing == Showing.SCANS -> embeds(note.text).any { it.lowercase().endsWith(".pdf") }
                else -> true
            }
        }
        .filter { note ->
            words.isEmpty() || (note.title + "\n" + note.text).lowercase().let { hay -> words.all { it in hay } }
        }
        .let { kept ->
            when (order) {
                Order.CHANGED -> kept.sortedByDescending { it.modified }
                Order.TITLE -> kept.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            }
        }
}

/** Every folder that holds a note, nested ones included, in alphabetical order. */
fun folders(notes: List<Note>): List<String> =
    notes.map { it.folder }
        .filter { it.isNotEmpty() }
        .flatMap { path -> path.split('/').indices.map { i -> path.split('/').take(i + 1).joinToString("/") } }
        .distinct()
        .sortedWith(String.CASE_INSENSITIVE_ORDER)
