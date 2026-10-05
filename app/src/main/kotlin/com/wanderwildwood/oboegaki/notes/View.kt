package com.wanderwildwood.oboegaki.notes

/** The folder archived notes are moved into, inside the notes folder. */
const val ARCHIVE_FOLDER = "Archive"

/** Whether the note at [path] is archived. */
fun isArchived(path: String): Boolean = path.startsWith("$ARCHIVE_FOLDER/")

/** Everything the list can be narrowed to. Stored as a string so a folder can be one. */
object Showing {
    const val ALL = ""
    const val SHARED = "shared"
    const val LISTS = "lists"
    const val VOICE = "voice"
    const val SCANS = "scans"
    const val ARCHIVE = "archive"
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
    pinned: Set<String> = emptySet(),
): List<Note> {
    val folder = Showing.folderOf(showing)
    val words = query.lowercase().split(' ', '\t', '\n').filter { it.isNotBlank() }
    return notes
        // Archived notes are out of the way everywhere but their own view, and a search, which
        // is how something put away is found again.
        .filter { note ->
            when {
                showing == Showing.ARCHIVE -> isArchived(note.path)
                words.isNotEmpty() && showing == Showing.ALL -> true
                else -> !isArchived(note.path)
            }
        }
        .filter { note ->
            when {
                showing == Showing.ARCHIVE -> true
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
                Order.OLDEST -> kept.sortedBy { it.modified }
                Order.TITLE -> kept.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            }
        }
        // Pinned notes above the rest, each group in the order chosen. Not in the archive,
        // where nothing is kept to hand.
        .let { sorted ->
            if (showing == Showing.ARCHIVE || pinned.isEmpty()) sorted
            else sorted.filter { it.path in pinned } + sorted.filter { it.path !in pinned }
        }
}

/** Every folder that holds a note, nested ones included, in alphabetical order; not the archive. */
fun folders(notes: List<Note>): List<String> =
    notes.filter { !isArchived(it.path) }.map { it.folder }
        .filter { it.isNotEmpty() }
        .flatMap { path -> path.split('/').indices.map { i -> path.split('/').take(i + 1).joinToString("/") } }
        .distinct()
        .sortedWith(String.CASE_INSENSITIVE_ORDER)
