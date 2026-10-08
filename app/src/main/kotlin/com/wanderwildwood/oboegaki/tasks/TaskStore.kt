package com.wanderwildwood.oboegaki.tasks

import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant
import java.util.UUID

/** A list of tasks: a Nextcloud calendar that holds tasks, or [PHONE], the one kept here. */
data class TaskList(
    val id: String,
    /** Its address on the server, or null for the list kept only on this phone. */
    val href: String?,
    val name: String,
    val readOnly: Boolean = false,
    /** The server's tag for the list as a whole, which moves whenever anything in it does. */
    val ctag: String? = null,
    /** When it last synced, or 0. */
    val synced: Long = 0,
) {
    val onPhone get() = href == null
}

/**
 * What the sync knows of one task here. [href] and [etag] are the server's, null until it has
 * been there. [dirty]: changed here since. [deleted]: deleted here, waiting to go from the server.
 */
data class Entry(
    val name: String,
    val href: String?,
    val etag: String?,
    val dirty: Boolean = false,
    val deleted: Boolean = false,
)

/** A task in a list, as the screens see it. */
data class Item(val list: String, val name: String, val text: String, val task: Task, val entry: Entry)

const val PHONE = "phone"

/**
 * The tasks on this phone, in the app's own storage: each task its own iCalendar file, as the
 * server has it, with the copy last seen on the server beside it, which is what a later sync
 * merges against. Changes made here wait in it, marked, until a sync takes them up; so a tick in a
 * shop with no signal is kept, and goes when there is one.
 *
 *     <root>/lists                one line per list
 *     <root>/<list>/state         one line per task: name, href, etag, dirty, deleted
 *     <root>/<list>/<name>.ics    the task here
 *     <root>/<list>/base/<name>.ics   the task as last seen on the server
 *
 * Every method holds one lock, so a sync and the screens never see half a change.
 */
class TaskStore(private val root: File) {

    private val lock = Any()

    private fun listsFile() = File(root, "lists")
    private fun dir(list: String) = File(root, list)
    private fun stateFile(list: String) = File(dir(list), "state")
    private fun file(list: String, name: String) = File(dir(list), "$name.ics")
    private fun baseFile(list: String, name: String) = File(File(dir(list), "base"), "$name.ics")

    // ---------------------------------------------------------------- lists

    fun lists(): List<TaskList> = synchronized(lock) {
        val f = listsFile()
        if (!f.isFile) return@synchronized emptyList()
        f.readLines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 6) return@mapNotNull null
            TaskList(p[0], p[1].ifEmpty { null }, p[2], p[3] == "1", p[4].ifEmpty { null }, p[5].toLongOrNull() ?: 0)
        }
    }

    fun saveLists(lists: List<TaskList>) = synchronized(lock) {
        val text = lists.joinToString("") { l ->
            listOf(l.id, l.href.orEmpty(), clean(l.name), if (l.readOnly) "1" else "0", l.ctag.orEmpty(), l.synced.toString())
                .joinToString("\t") + "\n"
        }
        writeAtomic(listsFile(), text)
    }

    fun list(id: String): TaskList? = lists().firstOrNull { it.id == id }

    fun updateList(id: String, change: (TaskList) -> TaskList) = synchronized(lock) {
        saveLists(lists().map { if (it.id == id) change(it) else it })
    }

    /** The list kept on this phone, made the first time it is asked for. */
    fun phoneList(name: String): TaskList = synchronized(lock) {
        list(PHONE) ?: TaskList(PHONE, null, name).also { saveLists(lists() + it) }
    }

    /** A list and everything in it gone from this phone; the server is not touched. */
    fun dropList(id: String) = synchronized(lock) {
        saveLists(lists().filter { it.id != id })
        dir(id).deleteRecursively()
    }

    // ---------------------------------------------------------------- entries

    fun entries(list: String): List<Entry> = synchronized(lock) {
        val f = stateFile(list)
        if (!f.isFile) return@synchronized emptyList()
        f.readLines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 5) return@mapNotNull null
            Entry(p[0], p[1].ifEmpty { null }, p[2].ifEmpty { null }, p[3] == "1", p[4] == "1")
        }
    }

    private fun saveEntries(list: String, entries: List<Entry>) {
        val text = entries.joinToString("") { e ->
            listOf(e.name, e.href.orEmpty(), e.etag.orEmpty(), if (e.dirty) "1" else "0", if (e.deleted) "1" else "0")
                .joinToString("\t") + "\n"
        }
        writeAtomic(stateFile(list), text)
    }

    private fun putEntry(list: String, entry: Entry) {
        val all = entries(list)
        saveEntries(list, if (all.any { it.name == entry.name }) all.map { if (it.name == entry.name) entry else it } else all + entry)
    }

    fun entry(list: String, name: String): Entry? = entries(list).firstOrNull { it.name == name }

    fun read(list: String, name: String): String? = synchronized(lock) { file(list, name).takeIf { it.isFile }?.readText() }

    fun base(list: String, name: String): String? = synchronized(lock) { baseFile(list, name).takeIf { it.isFile }?.readText() }

    /** Every task in a list that has not been deleted here. */
    fun items(list: String): List<Item> = synchronized(lock) {
        entries(list).filter { !it.deleted }.mapNotNull { e ->
            val text = read(list, e.name) ?: return@mapNotNull null
            val task = readTask(text) ?: return@mapNotNull null
            Item(list, e.name, text, task, e)
        }
    }

    fun item(list: String, name: String): Item? = items(list).firstOrNull { it.name == name }

    /** The task with this UID, in whichever list it is. */
    fun find(uid: String): Item? = synchronized(lock) {
        lists().firstNotNullOfOrNull { l -> items(l.id).firstOrNull { it.task.uid == uid } }
    }

    // ---------------------------------------------------------------- changes made here

    /** A new task in [list], waiting to go up. */
    fun add(list: String, fields: Fields, now: Instant, uid: String = UUID.randomUUID().toString()): Item = synchronized(lock) {
        val name = freeName(list, uid)
        val text = newTask(uid, fields, now)
        dir(list).mkdirs()
        writeAtomic(file(list, name), text)
        putEntry(list, Entry(name, null, null, dirty = true))
        Item(list, name, text, readTask(text)!!, entry(list, name)!!)
    }

    /**
     * The task's fields changed to [to], everything else in its file kept. With [from], what the
     * screen began with, only the fields that changed from it are written, so a field a sync
     * brought in while the task was open is not put back.
     */
    fun edit(list: String, name: String, to: Fields, now: Instant, from: Fields? = null): Item? = synchronized(lock) {
        val text = read(list, name) ?: return@synchronized null
        val current = readTask(text)?.fields ?: return@synchronized null
        val target = if (from == null) to else changedFields(current, from, to)
        if (current != target) replace(list, name, applyFields(text, current, target, now))
        item(list, name)
    }

    /** The task's whole file replaced with [text], as a repeating task's tick does. */
    fun replace(list: String, name: String, text: String) = synchronized(lock) {
        writeAtomic(file(list, name), text)
        entry(list, name)?.let { putEntry(list, it.copy(dirty = true)) }
    }

    /** Deleted here: gone at once if it never reached the server, else once the sync has taken it off there. */
    fun delete(list: String, name: String) = synchronized(lock) {
        val e = entry(list, name) ?: return@synchronized
        if (e.href == null) forget(list, name) else putEntry(list, e.copy(deleted = true))
    }

    /** The task moved to another list: a copy there, its file as it is, and this one deleted. */
    fun move(from: String, name: String, to: String): Item? = synchronized(lock) {
        if (from == to) return@synchronized item(from, name)
        val text = read(from, name) ?: return@synchronized null
        val task = readTask(text) ?: return@synchronized null
        val newName = freeName(to, task.uid.ifEmpty { UUID.randomUUID().toString() })
        dir(to).mkdirs()
        writeAtomic(file(to, newName), text)
        putEntry(to, Entry(newName, null, null, dirty = true))
        delete(from, name)
        item(to, newName)
    }

    // ---------------------------------------------------------------- what the sync writes

    /** The server's copy taken as it is, here and as the base. */
    fun take(list: String, name: String, href: String, etag: String?, text: String) = synchronized(lock) {
        dir(list).mkdirs()
        writeAtomic(file(list, name), text)
        writeAtomic(baseFile(list, name), text)
        putEntry(list, Entry(name, href, etag))
    }

    /**
     * [local], this phone's copy as it was sent, went up and the server now holds [server] with
     * [etag]. If nothing changed here meanwhile, the server's copy is the copy here too; if it
     * did, the change made meanwhile stays, still to go up, laid over the server's copy by
     * [rebase] so it does not undo what the server brought.
     */
    fun commit(list: String, name: String, href: String, server: String, etag: String?, local: String, rebase: (String) -> String) = synchronized(lock) {
        val current = read(list, name)
        val still = current == local
        writeAtomic(file(list, name), if (still || current == null) server else rebase(current))
        writeAtomic(baseFile(list, name), server)
        val deleted = entry(list, name)?.deleted == true
        putEntry(list, Entry(name, href, etag, dirty = !still, deleted = deleted))
    }

    /** Gone from here: its file, its base and its line. */
    fun forget(list: String, name: String) = synchronized(lock) {
        file(list, name).delete()
        baseFile(list, name).delete()
        saveEntries(list, entries(list).filter { it.name != name })
    }

    /** A name for a task's file from [seed] (its UID, or the last part of its address), not yet taken. */
    fun freeName(list: String, seed: String): String = synchronized(lock) {
        val base = safeName(seed)
        val taken = entries(list).map { it.name }.toSet()
        if (base !in taken) return@synchronized base
        var n = 2
        while ("$base-$n" in taken) n++
        "$base-$n"
    }

    private fun writeAtomic(f: File, text: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".new")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')
}

/** A string as a file name: letters, digits, dot, dash and underscore kept, the rest %-encoded. */
fun safeName(seed: String): String {
    val decoded = runCatching { URLDecoder.decode(seed.replace("+", "%2B"), "UTF-8") }.getOrDefault(seed)
    val out = URLEncoder.encode(decoded.removeSuffix(".ics"), "UTF-8").replace("+", "%20").replace("*", "%2A")
    return out.ifEmpty { "task" }.take(120)
}

/** [current] with each field that went from [from] to [to] set to [to]'s. */
fun changedFields(current: Fields, from: Fields, to: Fields) = Fields(
    summary = if (to.summary != from.summary) to.summary else current.summary,
    description = if (to.description != from.description) to.description else current.description,
    due = if (to.due?.key != from.due?.key) to.due else current.due,
    priority = if (to.priority != from.priority) to.priority else current.priority,
    done = if (to.done != from.done) to.done else current.done,
)
