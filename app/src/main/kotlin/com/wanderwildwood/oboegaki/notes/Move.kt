package com.wanderwildwood.oboegaki.notes

/*
 * Moving a note to another folder. A folder here is a folder of files, the same thing Nextcloud
 * Notes calls a category and Obsidian a folder: on Nextcloud or WebDAV the sync sees the note
 * gone from one folder and new in the other, and the server follows; in a folder on the phone
 * the file simply moves.
 */

/** Why a note cannot be moved where it was asked to go, or null when it can. */
enum class MoveRefusal { BAD_FOLDER, ARCHIVED, SAME_FOLDER }

/**
 * The folder a note at [path] may be moved to, tidied as [cleanFolder] tidies one typed in, or the
 * reason it may not: a name that cannot be a folder, an archived note (it goes back from the
 * archive first, to the folder it came from), or the folder it is already in.
 */
fun moveTarget(path: String, typed: String): Pair<String?, MoveRefusal?> {
    if (isArchived(path)) return null to MoveRefusal.ARCHIVED
    val folder = cleanFolder(typed) ?: return null to MoveRefusal.BAD_FOLDER
    if (folder == path.substringBeforeLast('/', "")) return null to MoveRefusal.SAME_FOLDER
    return folder to null
}

/**
 * What moving a note with [text] from [fromFolder] to [toFolder] does to the recordings, scans and
 * pictures it links beside it. One no other note in the old folder links goes with it; one another
 * note there also links is copied, so that note still finds it. One already in the new folder
 * under that name is left as it is, never written over, and the note's link finds that one.
 * [exists] says whether a file is there; [linkedByOthers] whether another note in the old folder
 * links the file at that path.
 */
data class AttachmentPlan(val move: List<Pair<String, String>>, val copy: List<Pair<String, String>>)

fun attachmentPlan(
    text: String,
    fromFolder: String,
    toFolder: String,
    exists: (String) -> Boolean,
    linkedByOthers: (String) -> Boolean,
): AttachmentPlan {
    val move = mutableListOf<Pair<String, String>>()
    val copy = mutableListOf<Pair<String, String>>()
    for (name in embeds(text).distinct()) {
        val from = inFolder(fromFolder, name)
        val to = inFolder(toFolder, name)
        if (!exists(from) || exists(to)) continue
        if (linkedByOthers(from)) copy += from to to else move += from to to
    }
    return AttachmentPlan(move, copy)
}

/**
 * Move the note at [path] into [folder] on [shelf], with what it links, under its own name or,
 * when that is taken there, the next free one ("kiln 2.md"). [others] are the other notes, to see
 * which attachments they share. Returns where the note is now, or null when it is not there.
 * The caller holds [notesLock].
 */
fun moveOnShelf(shelf: Shelf, path: String, folder: String, others: List<Note>): String? {
    val text = shelf.read(path) ?: return null
    val fromFolder = path.substringBeforeLast('/', "")
    val stem = path.substringAfterLast('/').substringBeforeLast('.')
    var target = inFolder(folder, "$stem.md")
    var n = 2
    while (shelf.exists(target)) target = inFolder(folder, "$stem ${n++}.md")
    val plan = attachmentPlan(text, fromFolder, folder, shelf::exists) { attachment ->
        others.any { note ->
            note.path != path && note.folder == fromFolder && embeds(note.text).any { inFolder(fromFolder, it) == attachment }
        }
    }
    // The attachments first, so the note never sits in its new folder with its links not found.
    for ((from, to) in plan.copy) {
        runCatching { shelf.readBytes(from)?.let { shelf.writeBytes(to, it, "application/octet-stream") } }
    }
    for ((from, to) in plan.move) runCatching { shelf.move(from, to) }
    shelf.move(path, target)
    return target
}
