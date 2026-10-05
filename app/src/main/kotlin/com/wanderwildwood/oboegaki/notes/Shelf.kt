package com.wanderwildwood.oboegaki.notes

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.Os
import com.wanderwildwood.oboegaki.sync.isNote
import java.io.File
import java.io.FileOutputStream

/**
 * One note: where it is ([path], relative to the notes folder, "ideas/kiln.md"), what it is
 * called, how it starts, and when it last changed.
 */
data class Note(
    val path: String,
    val title: String,
    val preview: String,
    val modified: Long,
    /** Everything it says, for search and for telling a list from a page. */
    val text: String = "",
) {
    val folder: String get() = path.substringBeforeLast('/', "")
}

/**
 * Wherever the notes are kept. The rest of the app speaks only in paths relative to the notes
 * folder, and never knows whether that folder is a directory the app owns or one the reader
 * chose through the system picker.
 */
interface Shelf {
    fun list(): List<Note>
    fun read(path: String): String?
    fun write(path: String, text: String)
    fun delete(path: String)
    fun rename(from: String, to: String)
    fun exists(path: String): Boolean

    /** Put a recording, a scan or a picture at [path]. */
    fun writeBytes(path: String, bytes: ByteArray, mime: String)

    /** Somewhere a player can open the file at [path], or null if it is not there. */
    fun uriOf(path: String): Uri?
}

/**
 * Every touch of the notes goes through this one lock: the editor's saves and a sync's writes
 * alike, so neither can land between the other's read and write.
 */
val notesLock = Any()

/** A directory the app owns: the copy of the Nextcloud folder that [com.wanderwildwood.oboegaki.sync.Sync] keeps. */
class FileShelf(private val root: File) : Shelf {

    override fun list(): List<Note> = synchronized(notesLock) {
        root.walkTopDown()
            .onEnter { it == root || !it.name.startsWith(".") }
            .filter { it.isFile && isNote(it.name) }
            .map { file ->
                val path = file.relativeTo(root).invariantSeparatorsPath
                note(path, file.readText(), file.lastModified())
            }
            .toList()
    }

    override fun read(path: String): String? = synchronized(notesLock) {
        File(root, path).takeIf { it.isFile }?.readText()
    }

    override fun write(path: String, text: String) = synchronized(notesLock) {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".${file.name}.part")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.writeText(text)
            temp.delete()
        }
    }

    override fun delete(path: String) {
        synchronized(notesLock) { File(root, path).delete() }
    }

    override fun rename(from: String, to: String) {
        synchronized(notesLock) {
            val target = File(root, to)
            target.parentFile?.mkdirs()
            File(root, from).renameTo(target)
        }
    }

    override fun exists(path: String): Boolean = File(root, path).isFile

    override fun writeBytes(path: String, bytes: ByteArray, mime: String) = synchronized(notesLock) {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".${file.name}.part")
        temp.writeBytes(bytes)
        if (!temp.renameTo(file)) {
            file.writeBytes(bytes)
            temp.delete()
        }
    }

    override fun uriOf(path: String): Uri? = File(root, path).takeIf { it.isFile }?.let(Uri::fromFile)
}

/**
 * A folder the reader chose through the system picker, which may belong to a sync app of their
 * own. Everything goes through the document provider, so the app needs no storage permission:
 * the grant covers that folder and nothing else.
 *
 * Providers name documents by id rather than path, so each listing remembers which id each path
 * had, and the subfolders are walked one query each.
 */
class FolderShelf(private val resolver: ContentResolver, private val tree: Uri) : Shelf {

    private val ids = mutableMapOf<String, String>()
    private val rootId = DocumentsContract.getTreeDocumentId(tree)

    override fun list(): List<Note> = synchronized(notesLock) {
        ids.clear()
        ids[""] = rootId
        val out = mutableListOf<Note>()
        val pending = ArrayDeque(listOf("" to rootId))
        while (pending.isNotEmpty()) {
            val (dir, id) = pending.removeFirst()
            for (child in children(id)) {
                val path = if (dir.isEmpty()) child.name else "$dir/${child.name}"
                if (child.name.startsWith(".")) continue
                ids[path] = child.id
                if (child.isFolder) {
                    pending += path to child.id
                } else if (isNote(child.name)) {
                    val text = runCatching { readId(child.id) }.getOrDefault("")
                    out += note(path, text, child.modified)
                }
            }
        }
        out
    }

    override fun read(path: String): String? = synchronized(notesLock) {
        val id = idOf(path) ?: return null
        runCatching { readId(id) }.getOrNull()
    }

    override fun write(path: String, text: String) = synchronized(notesLock) {
        val id = idOf(path) ?: create(path)
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        val bytes = text.toByteArray(Charsets.UTF_8)
        // "rwt" asks for truncation, and ftruncate settles it for a provider that ignores the
        // ask: without both, a shorter note is written over the start of the longer one and
        // the old tail stays on the end. The Typewriter learned this the hard way.
        val descriptor = runCatching { resolver.openFileDescriptor(uri, "rwt") }.getOrNull()
            ?: resolver.openFileDescriptor(uri, "rw")
            ?: error("The folder would not let $path be written.")
        descriptor.use {
            FileOutputStream(it.fileDescriptor).use { stream ->
                stream.write(bytes)
                stream.flush()
                try {
                    Os.ftruncate(it.fileDescriptor, bytes.size.toLong())
                } catch (_: ErrnoException) {
                    // Already truncated on open where "rwt" was honoured.
                }
            }
        }
    }

    override fun delete(path: String) {
        synchronized(notesLock) {
            val id = idOf(path) ?: return
            DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id))
            ids.remove(path)
        }
    }

    override fun rename(from: String, to: String) {
        synchronized(notesLock) {
            val id = idOf(from) ?: return
            // Only the name changes; moving between folders is not offered.
            val moved = DocumentsContract.renameDocument(
                resolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, id),
                to.substringAfterLast('/'),
            ) ?: return
            ids.remove(from)
            ids[to] = DocumentsContract.getDocumentId(moved)
        }
    }

    override fun exists(path: String): Boolean = synchronized(notesLock) { idOf(path) != null }

    override fun writeBytes(path: String, bytes: ByteArray, mime: String) = synchronized(notesLock) {
        val id = idOf(path) ?: create(path, mime)
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        (resolver.openOutputStream(uri, "wt") ?: error("The folder would not let $path be written."))
            .use { it.write(bytes) }
    }

    override fun uriOf(path: String): Uri? = synchronized(notesLock) {
        idOf(path)?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it) }
    }

    private data class Child(val id: String, val name: String, val isFolder: Boolean, val modified: Long)

    private fun children(parentId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val out = mutableListOf<Child>()
        resolver.query(uri, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1) ?: continue
                out += Child(
                    id = cursor.getString(0),
                    name = name,
                    isFolder = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    modified = if (cursor.isNull(3)) 0L else cursor.getLong(3),
                )
            }
        }
        return out
    }

    /** The id for [path], looking it up afresh when no listing has seen it yet. */
    private fun idOf(path: String): String? {
        ids[path]?.let { return it }
        var id = rootId
        var at = ""
        for (part in path.split('/')) {
            val child = children(id).firstOrNull { it.name == part } ?: return null
            at = if (at.isEmpty()) part else "$at/$part"
            id = child.id
            ids[at] = id
        }
        return id
    }

    /** A new document at [path], and any folders above it. */
    private fun create(path: String, mime: String = "text/plain"): String {
        var parent = rootId
        var at = ""
        val parts = path.split('/')
        for (part in parts.dropLast(1)) {
            at = if (at.isEmpty()) part else "$at/$part"
            parent = idOf(at) ?: DocumentsContract.getDocumentId(
                DocumentsContract.createDocument(
                    resolver,
                    DocumentsContract.buildDocumentUriUsingTree(tree, parent),
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    part,
                ) ?: error("The folder would not take $at."),
            ).also { ids[at] = it }
        }
        // Asked for as text/plain with the extension in the name: asking for text/markdown is
        // how some providers end up writing "name.md.txt".
        val uri = DocumentsContract.createDocument(
            resolver,
            DocumentsContract.buildDocumentUriUsingTree(tree, parent),
            mime,
            parts.last(),
        ) ?: error("The folder would not take $path.")
        val id = DocumentsContract.getDocumentId(uri)
        ids[path] = id
        return id
    }

    private fun readId(id: String): String =
        resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id))
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("unreadable")
}

/** A note's title is its file name, as Obsidian and every other folder-of-notes app has it. */
fun note(path: String, text: String, modified: Long): Note {
    val name = path.substringAfterLast('/')
    val title = name.substringBeforeLast('.').ifEmpty { name }
    return Note(path = path, title = title, preview = preview(text), modified = modified, text = text)
}

/**
 * The first line worth reading: past front matter, past a heading that only repeats the title,
 * and with Markdown's marks taken off, so a shopping list previews as "milk" rather than "- [ ] milk".
 */
fun preview(text: String): String {
    var lines = text.lineSequence().map { it.trim() }
    if (text.startsWith("---")) {
        val all = text.lines()
        val close = all.drop(1).indexOfFirst { it.trim() == "---" }
        if (close >= 0) lines = all.drop(close + 2).asSequence().map { it.trim() }
    }
    return lines
        // Headings, and lines that only link a recording or a scan: neither is something to read.
        .filter { it.isNotEmpty() && !it.startsWith("#") && !(it.startsWith("![[") && it.endsWith("]]")) }
        .map { plain(it) }
        .filter { it.isNotEmpty() }
        .take(3)
        .joinToString(" · ")
}

/**
 * A line with Markdown's marks taken off: list and task marks, a quote's ">", and the stars and
 * underscores of emphasis, so "> *Robin Wall Kimmerer*" previews as the name.
 */
fun plain(line: String): String {
    var s = line.trim()
    while (s.startsWith(">")) s = s.removePrefix(">").trimStart()
    s = s.removePrefix("- [ ] ").removePrefix("- [x] ").removePrefix("- [X] ")
        .removePrefix("- ").removePrefix("* ").removePrefix("+ ")
    return s.replace(Regex("""(?<![\w*_`])(\*\*|__|\*|_|`)(\S(?:.*?\S)?)\1(?![\w*_`])"""), "$2").trim()
}

/** A title made safe to be a file name on any of the places a note might be synced to. */
fun fileNameFor(title: String): String =
    title.trim()
        .replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "-")
        .trim('.', ' ')
        .take(120)
