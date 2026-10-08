package com.wanderwildwood.oboegaki.tasks

import com.wanderwildwood.oboegaki.sync.Moved
import java.io.IOException
import java.net.URLDecoder
import java.time.Instant

/** A list of tasks as the server describes it. */
data class RemoteList(val href: String, val name: String, val readOnly: Boolean, val ctag: String?)

/** A task as the server holds it. */
data class RemoteItem(val href: String, val etag: String?, val text: String)

/** The server would not let this phone write to the list: it was shared read-only. */
class ReadOnly(message: String) : IOException(message)

/**
 * The far end of a task sync: the reader's task lists on a CalDAV server. An interface so the
 * sync can be run against lists in memory, which is how two clients editing one task are tested.
 */
interface TaskServer {
    /** Every list that can hold tasks, the reader's own and those shared with them. */
    fun lists(): List<RemoteList>

    /** Every task in a list. */
    fun items(listHref: String): List<RemoteItem>

    /** One task now; throws [Moved] when it is gone. */
    fun get(href: String): RemoteItem

    /**
     * Writes [text] at [href], only if the server's copy is still [ifMatch], or, with null, only
     * if there is none. Returns the new etag when the server says it. Throws [Moved] when the
     * condition does not hold, [ReadOnly] when the list may not be written to.
     */
    fun put(href: String, text: String, ifMatch: String?): String?

    /** Removes the task, only if it is still [etag]. Throws [Moved] when it is not; gone already is fine. */
    fun delete(href: String, etag: String?)

    /** A new list of tasks, called [name]. */
    fun makeList(name: String): RemoteList
}

/** How one sync went: lists it could not finish, and conflicts kept in a task's notes. */
data class TaskSyncResult(val received: Int, val sent: Int, val failed: List<String>)

/**
 * Brings the lists here and on the server together.
 *
 * Changes made here are pushed with If-Match on the etag last seen, so nothing on the server is
 * written over unseen. When the server's copy changed meanwhile (412), it is fetched again and the
 * two are merged field by field against the copy both started from ([mergeFields]); a field
 * changed on one side only takes that side, and where both changed one field, the server's
 * stands and the phone's is kept in the notes. Then it is pushed again.
 *
 * A task deleted here but changed on the server comes back, and one deleted on the server but
 * changed here goes back up: in both, a change would otherwise be lost without a word.
 */
class TaskSync(
    private val store: TaskStore,
    private val server: TaskServer,
    private val now: () -> Instant = Instant::now,
    private val said: (FieldName, String) -> String = { f, v -> "This phone had ${f.name.lowercase()}: $v" },
    private val dueWords: (Due?) -> String = { it?.key ?: "none" },
) {

    private var received = 0
    private var sent = 0

    fun run(): TaskSyncResult {
        received = 0
        sent = 0
        val remote = server.lists()
        val known = store.lists()
        // The server's lists, by their address; a list gone from the server goes from here too.
        val updated = mutableListOf<TaskList>()
        for (r in remote) {
            val old = known.firstOrNull { it.href != null && same(it.href, r.href) }
            updated += old?.copy(name = r.name, readOnly = r.readOnly, href = r.href)
                ?: TaskList(freeId(r.href, known.map { it.id } + updated.map { it.id }), r.href, r.name, r.readOnly)
        }
        for (gone in known.filter { it.href != null && remote.none { r -> same(r.href, it.href) } }) store.dropList(gone.id)
        store.saveLists(known.filter { it.href == null } + updated)

        val failed = mutableListOf<String>()
        for (list in updated) {
            val r = remote.first { same(it.href, list.href!!) }
            try {
                val pending = store.entries(list.id).any { it.dirty || it.deleted || it.href == null }
                if (pending || list.ctag == null || list.ctag != r.ctag) syncList(list)
                store.updateList(list.id) { it.copy(ctag = r.ctag, synced = now().toEpochMilli()) }
            } catch (e: IOException) {
                failed += list.name
            }
        }
        return TaskSyncResult(received, sent, failed)
    }

    private fun syncList(list: TaskList) {
        val href = list.href!!
        val remote = server.items(href).associateBy { norm(it.href) }
        val seen = mutableSetOf<String>()
        for (e in store.entries(list.id)) {
            if (e.href == null) {
                if (list.readOnly) continue
                push(list, e, href.trimEnd('/') + "/" + e.name + ".ics", null)
                continue
            }
            seen += norm(e.href)
            val r = remote[norm(e.href)]
            when {
                e.deleted -> when {
                    r == null -> store.forget(list.id, e.name)
                    r.etag != e.etag -> take(list, e.name, r)
                    list.readOnly -> take(list, e.name, r)
                    else -> try {
                        server.delete(e.href, e.etag)
                        store.forget(list.id, e.name)
                        sent++
                    } catch (_: Moved) {
                        runCatching { server.get(e.href) }.getOrNull()?.let { take(list, e.name, it) } ?: store.forget(list.id, e.name)
                    }
                }
                r == null -> if (e.dirty && !list.readOnly) push(list, e, e.href, null) else store.forget(list.id, e.name)
                r.etag != null && r.etag == e.etag -> if (e.dirty) push(list, e, e.href, e.etag)
                e.dirty && !list.readOnly -> {
                    val local = store.read(list.id, e.name) ?: continue
                    push(list, e, e.href, r.etag, mergeText(store.base(list.id, e.name), local, r.text), local)
                }
                else -> take(list, e.name, r)
            }
        }
        for ((key, r) in remote) {
            if (key in seen) continue
            take(list, store.freeName(list.id, r.href.trimEnd('/').substringAfterLast('/')), r)
        }
    }

    private fun take(list: TaskList, name: String, r: RemoteItem) {
        store.take(list.id, name, r.href, r.etag, r.text)
        received++
    }

    /**
     * Sends [text] (this phone's copy, unless a merge made it something else) to [href]. On a 412
     * the server's copy is fetched and merged again, at most three times.
     */
    private fun push(list: TaskList, e: Entry, href: String, ifMatch: String?, text: String? = null, localAtStart: String? = null) {
        val local = localAtStart ?: store.read(list.id, e.name) ?: return
        val base = store.base(list.id, e.name)
        var body = text ?: local
        var condition = ifMatch
        repeat(3) {
            try {
                val etag = server.put(href, body, condition)
                sent++
                // The server may keep the file other than as sent; then its copy is fetched.
                val back = if (etag != null) RemoteItem(href, etag, body) else runCatching { server.get(href) }.getOrNull() ?: RemoteItem(href, null, body)
                store.commit(list.id, e.name, href, back.text, back.etag, local) { current -> mergeText(local, current, back.text) }
                return
            } catch (_: Moved) {
                val now = runCatching { server.get(href) }.getOrNull()
                if (now == null) {
                    condition = null
                } else {
                    body = mergeText(base, local, now.text)
                    condition = now.etag
                }
            } catch (_: ReadOnly) {
                store.updateList(list.id) { it.copy(readOnly = true) }
                val there = runCatching { server.get(href) }.getOrNull()
                if (there != null) take(list, e.name, there) else store.forget(list.id, e.name)
                return
            }
        }
        throw IOException("$href kept changing")
    }

    /** This phone's copy laid over the server's, field by field, from the copy both began as. */
    fun mergeText(base: String?, local: String, remote: String): String {
        val r = readTask(remote) ?: return local
        val l = readTask(local)?.fields ?: return remote
        val b = base?.let(::readTask)?.fields ?: r.fields
        val merged = mergeFields(b, l, r.fields, said, dueWords)
        return applyFields(remote, r.fields, merged, now())
    }

    private fun freeId(href: String, taken: List<String>): String {
        val seed = "nc-" + safeName(href.trimEnd('/').substringAfterLast('/'))
        if (seed !in taken) return seed
        var n = 2
        while ("$seed-$n" in taken) n++
        return "$seed-$n"
    }
}

/** An address as compared: its path, decoded, without a closing slash. */
fun norm(href: String): String {
    val path = if (href.startsWith("http://") || href.startsWith("https://")) java.net.URI(href).rawPath else href
    return runCatching { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrDefault(path).trimEnd('/')
}

private fun same(a: String, b: String) = norm(a) == norm(b)
