package com.wanderwildwood.oboegaki.notes

import com.wanderwildwood.oboegaki.sync.isNote

/*
 * The trash: where a deleted note waits before it is gone.
 *
 * A hidden folder at the top of the notes, `.trash`, which no listing, sync, search, pin or
 * reminder looks into (every walk of the notes skips a name that starts with a dot). Each deleted
 * note has a folder of its own in it, named for the moment it was deleted, holding the note, the
 * recordings and scans it linked that no other note links, and a small `.meta` file saying where
 * the note came from and when it was deleted:
 *
 *     .trash/1761832800000/kiln.md
 *     .trash/1761832800000/kiln.m4a
 *     .trash/1761832800000/.meta        from: Garden/kiln.md
 *                                       at: 1761832800000
 *
 * Everything here is plain, so the rules can be tested without a phone.
 */

/** The hidden folder, inside the notes folder. */
const val TRASH_FOLDER = ".trash"

/** How long a deleted note is kept. The one place this number is written. */
const val TRASH_DAYS = 7

/** The file in a trashed note's folder that says where it came from. */
const val TRASH_META = ".meta"

private const val DAY_MS = 24L * 60 * 60 * 1000

/** Whether [path] is the trash or inside it. */
fun isTrashPath(path: String): Boolean = path == TRASH_FOLDER || path.startsWith("$TRASH_FOLDER/")

/** Whether any step of [path] is hidden, which is where no note is ever listed from. */
fun isHiddenPath(path: String): Boolean = path.split('/').any { it.startsWith(".") }

/** Anything deleted at or before this moment is older than [TRASH_DAYS] days. */
fun trashCutoff(now: Long): Long = now - TRASH_DAYS * DAY_MS

/** Whether a note deleted at [deletedAt] has been in the trash longer than [TRASH_DAYS] days. */
fun trashExpired(deletedAt: Long, now: Long): Boolean = deletedAt < trashCutoff(now)

/** When the note deleted at [deletedAt] goes for good. */
fun trashedUntil(deletedAt: Long): Long = deletedAt + TRASH_DAYS * DAY_MS

/** A name for a new note's folder in the trash: the moment, moved on by one while that is taken. */
fun trashSlot(at: Long, taken: Set<String>): String {
    var n = at
    while (n.toString() in taken) n++
    return n.toString()
}

/** Where a note is kept in the trash: its folder there, and its own file name. */
fun trashedPath(slot: String, from: String): String = "$TRASH_FOLDER/$slot/${from.substringAfterLast('/')}"

/** Where a trashed note came from and when it was deleted. */
data class TrashMeta(val from: String, val at: Long)

fun trashMetaText(meta: TrashMeta): String = "from: ${meta.from}\nat: ${meta.at}\n"

fun parseTrashMeta(text: String?): TrashMeta? {
    if (text == null) return null
    var from: String? = null
    var at: Long? = null
    for (line in text.lines()) {
        when {
            line.startsWith("from: ") -> from = line.removePrefix("from: ").trim()
            line.startsWith("at: ") -> at = line.removePrefix("at: ").trim().toLongOrNull()
        }
    }
    if (from.isNullOrEmpty() || at == null) return null
    // Whatever the file says, it can only put a note back below the notes, never up and out.
    if (from.split('/').any { it == ".." || it.isEmpty() }) return null
    return TrashMeta(from, at)
}

/** A file or folder as a shelf lists it. */
data class Entry(val name: String, val isFolder: Boolean, val modified: Long)

/** A note in the trash, as the screens show it. [slot] is its folder in the trash and its id. */
data class Trashed(val slot: String, val title: String, val from: String, val at: Long) {
    /** The file in the trash. */
    val path: String get() = trashedPath(slot, from)
    val folder: String get() = from.substringBeforeLast('/', "")
}

/**
 * What one folder of the trash holds, from its files and its `.meta` text: the note, or null
 * when there is no note in it. A folder with no readable `.meta` still shows, as from the top of
 * the notes, deleted when its files were last written, so nothing in the trash is out of reach.
 */
fun trashEntry(slot: String, files: List<Entry>, metaText: String?): Trashed? {
    val meta = parseTrashMeta(metaText)
    val named = meta?.from?.substringAfterLast('/')
    val file = files.firstOrNull { !it.isFolder && it.name == named }
        ?: files.firstOrNull { !it.isFolder && isNote(it.name) }
        ?: return null
    val from = if (meta != null && file.name == named) meta.from else file.name
    val at = meta?.at ?: slot.toLongOrNull() ?: file.modified
    return Trashed(slot, file.name.substringBeforeLast('.').ifEmpty { file.name }, from, at)
}

/**
 * Where a restored note goes: the folder it came from, or the top of the notes when that folder
 * is gone, under its own name, or with " (1)", " (2)" and on while [taken] says the name is in
 * use. Nothing is ever written over.
 */
fun restoreTarget(from: String, folderExists: Boolean, taken: (String) -> Boolean): String {
    val folder = if (folderExists) from.substringBeforeLast('/', "") else ""
    val name = from.substringAfterLast('/')
    val first = inFolder(folder, name)
    if (!taken(first)) return first
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var n = 1
    while (true) {
        val candidate = inFolder(folder, "$stem ($n)$ext")
        if (!taken(candidate)) return candidate
        n++
    }
}

/** The trashed notes old enough to go, as of [now]. */
fun expiredIn(all: List<Trashed>, now: Long): List<Trashed> = all.filter { trashExpired(it.at, now) }

/**
 * Whether a request from the page to trash a note is out of date: it says which version of the
 * note it was looking at, and the note is no longer that. A request that names none is not.
 */
fun trashIsStale(requested: String?, currentVersion: String): Boolean =
    !requested.isNullOrEmpty() && requested != currentVersion
