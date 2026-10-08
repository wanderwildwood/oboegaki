package com.wanderwildwood.oboegaki.tasks

import com.wanderwildwood.oboegaki.sync.Moved
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

/** A CalDAV server in memory: one or more lists, etags that move on every write, If-Match honoured. */
class MemoryServer : TaskServer {
    val lists = mutableMapOf("/dav/calendars/ada/personal/" to "Personal")
    val readOnly = mutableSetOf<String>()
    val files = mutableMapOf<String, Pair<String, String>>()
    private var n = 0
    var puts = 0

    /** Runs once, just before the next PUT is looked at: another client getting there first. */
    var beforePut: (() -> Unit)? = null

    fun write(href: String, text: String): String {
        val etag = "\"e${++n}\""
        files[href] = etag to text
        return etag
    }

    override fun lists() = lists.map { (href, name) ->
        RemoteList(href, name, href in readOnly, "c" + files.filterKeys { it.startsWith(href) }.values.joinToString { it.first })
    }

    override fun items(listHref: String) = files.filterKeys { it.startsWith(listHref) }.map { (h, v) -> RemoteItem(h, v.first, v.second) }

    override fun get(href: String): RemoteItem = files[href]?.let { RemoteItem(href, it.first, it.second) } ?: throw Moved(href)

    override fun put(href: String, text: String, ifMatch: String?): String? {
        beforePut?.let { beforePut = null; it() }
        if (lists.keys.any { href.startsWith(it) && it in readOnly }) throw ReadOnly(href)
        val now = files[href]
        if (ifMatch == null && now != null) throw Moved(href)
        if (ifMatch != null && now?.first != ifMatch) throw Moved(href)
        puts++
        return write(href, text)
    }

    override fun delete(href: String, etag: String?) {
        val now = files[href] ?: return
        if (etag != null && now.first != etag) throw Moved(href)
        files.remove(href)
    }

    override fun makeList(name: String): RemoteList {
        val href = "/dav/calendars/ada/${name.lowercase()}/"
        lists[href] = name
        return RemoteList(href, name, false, null)
    }
}

class TaskSyncTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val t0 = Instant.parse("2026-10-07T12:00:00Z")
    private val server = MemoryServer()
    private val store by lazy { TaskStore(tmp.newFolder("tasks")) }
    private fun sync() = TaskSync(store, server, now = { t0 }, said = { f, v -> "phone ${f.name}: $v" }).run()
    private val list get() = store.lists().first { !it.onPhone }.id

    private val withExtras = listOf(
        "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Nextcloud Tasks v0.16", "BEGIN:VTODO", "UID:web-1",
        "DTSTAMP:20261001T080000Z", "SUMMARY:Feed the llamas", "STATUS:NEEDS-ACTION",
        "RELATED-TO:parent-9", "CATEGORIES:Farm", "X-OC-HIDESUBTASKS:0",
        "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:-PT10M", "END:VALARM", "END:VTODO", "END:VCALENDAR", "",
    ).joinToString("\r\n")

    @Test
    fun aTaskMadeOnTheServerComesDownAndAnEditGoesBackWithEverythingElseKept() {
        server.write("/dav/calendars/ada/personal/web-1.ics", withExtras)
        sync()
        val item = store.items(list).single()
        assertEquals("Feed the llamas", item.task.fields.summary)
        assertEquals(withExtras, item.text)

        store.edit(list, item.name, item.task.fields.copy(done = true), t0)
        sync()
        val up = server.files["/dav/calendars/ada/personal/web-1.ics"]!!.second
        assertTrue(readTask(up)!!.fields.done)
        for (line in listOf("RELATED-TO:parent-9", "CATEGORIES:Farm", "X-OC-HIDESUBTASKS:0", "BEGIN:VALARM", "TRIGGER:-PT10M", "PRODID:-//Nextcloud Tasks v0.16")) {
            assertTrue(line, up.contains(line + "\r\n"))
        }
        assertFalse(store.entries(list).single().dirty)
    }

    @Test
    fun aTaskMadeHereGoesUpOnceAndIsKnownByItsAddressAfter() {
        sync()
        store.add(list, Fields("Call Tomas"), t0, uid = "phone-1")
        sync()
        sync()
        assertEquals(1, server.puts)
        val (href, v) = server.files.entries.single()
        assertEquals("/dav/calendars/ada/personal/phone-1.ics", href)
        assertEquals("Call Tomas", readTask(v.second)!!.fields.summary)
        assertEquals(href, store.entries(list).single().href)
    }

    @Test
    fun aChangeMadeOfflineWaitsAndGoesLater() {
        sync()
        store.add(list, Fields("Seed order"), t0, uid = "off-1")
        val offline = object : TaskServer by server {
            override fun lists(): List<RemoteList> = throw com.wanderwildwood.oboegaki.sync.Unreachable("no signal")
        }
        runCatching { TaskSync(store, offline, now = { t0 }).run() }
        assertTrue(server.files.isEmpty())
        assertTrue(store.entries(list).single().dirty)
        sync()
        assertEquals(1, server.files.size)
        assertFalse(store.entries(list).single().dirty)
    }

    @Test
    fun a412IsFetchedAgainAndMergedFieldByField() {
        server.write("/dav/calendars/ada/personal/web-1.ics", withExtras)
        sync()
        val item = store.items(list).single()
        // Ticked here, offline; meanwhile the web renames it and adds a note.
        store.edit(list, item.name, item.task.fields.copy(done = true), t0)
        server.beforePut = {
            val from = readTask(withExtras)!!.fields
            server.write("/dav/calendars/ada/personal/web-1.ics", applyFields(withExtras, from, from.copy(summary = "Feed the llamas hay", description = "two bales"), t0))
        }
        sync()
        val up = readTask(server.files.values.single().second)!!
        assertEquals("Feed the llamas hay", up.fields.summary)
        assertEquals("two bales", up.fields.description)
        assertTrue(up.fields.done)
        assertTrue(server.files.values.single().second.contains("RELATED-TO:parent-9\r\n"))
        assertEquals(server.files.values.single().second, store.items(list).single().text)
    }

    @Test
    fun bothSidesChangedOneFieldTheServersStandsAndThePhonesIsKeptInTheNotes() {
        server.write("/dav/calendars/ada/personal/web-1.ics", withExtras)
        sync()
        val item = store.items(list).single()
        store.edit(list, item.name, item.task.fields.copy(summary = "Feed them here"), t0)
        val from = readTask(withExtras)!!.fields
        server.write("/dav/calendars/ada/personal/web-1.ics", applyFields(withExtras, from, from.copy(summary = "Feed them there"), t0))
        sync()
        val up = readTask(server.files.values.single().second)!!
        assertEquals("Feed them there", up.fields.summary)
        assertEquals("phone SUMMARY: Feed them here", up.fields.description)
    }

    @Test
    fun deletedHereButChangedThereComesBack() {
        server.write("/dav/calendars/ada/personal/web-1.ics", withExtras)
        sync()
        val item = store.items(list).single()
        store.delete(list, item.name)
        assertTrue(store.items(list).isEmpty())
        val from = readTask(withExtras)!!.fields
        server.write("/dav/calendars/ada/personal/web-1.ics", applyFields(withExtras, from, from.copy(priority = 1), t0))
        sync()
        assertEquals(1, store.items(list).single().task.fields.priority)
        assertEquals(1, server.files.size)
    }

    @Test
    fun deletedHereAndUnchangedThereGoes() {
        server.write("/dav/calendars/ada/personal/web-1.ics", withExtras)
        sync()
        store.delete(list, store.items(list).single().name)
        sync()
        assertTrue(server.files.isEmpty())
        assertTrue(store.entries(list).isEmpty())
    }

    @Test
    fun deletedThereButChangedHereGoesBackUp() {
        server.write("/dav/calendars/ada/personal/web-1.ics", withExtras)
        sync()
        val item = store.items(list).single()
        store.edit(list, item.name, item.task.fields.copy(summary = "Still wanted"), t0)
        server.files.clear()
        sync()
        assertEquals("Still wanted", readTask(server.files.values.single().second)!!.fields.summary)
    }

    @Test
    fun deletedThereAndUnchangedHereGoesFromHere() {
        server.write("/dav/calendars/ada/personal/web-1.ics", withExtras)
        sync()
        server.files.clear()
        sync()
        assertTrue(store.items(list).isEmpty())
    }

    @Test
    fun anEditMadeWhileAPushIsOnTheWayIsKeptForTheNext() {
        sync()
        val item = store.add(list, Fields("Hay"), t0, uid = "h")
        server.beforePut = { store.edit(list, item.name, item.task.fields.copy(priority = 1), t0) }
        sync()
        assertTrue(store.entries(list).single().dirty)
        sync()
        assertEquals(1, readTask(server.files.values.single().second)!!.fields.priority)
        assertFalse(store.entries(list).single().dirty)
    }

    @Test
    fun aReadOnlyListIsShownButNotWritten() {
        server.lists["/dav/calendars/ada/shared_by_tomas/"] = "Tomas's list"
        server.readOnly += "/dav/calendars/ada/shared_by_tomas/"
        server.write("/dav/calendars/ada/shared_by_tomas/t.ics", withExtras)
        sync()
        val shared = store.lists().first { it.name == "Tomas's list" }
        assertTrue(shared.readOnly)
        val item = store.items(shared.id).single()
        store.edit(shared.id, item.name, item.task.fields.copy(done = true), t0)
        sync()
        assertFalse(readTask(server.files["/dav/calendars/ada/shared_by_tomas/t.ics"]!!.second)!!.fields.done)
        assertFalse(store.items(shared.id).single().task.fields.done)
    }

    @Test
    fun aListGoneFromTheServerGoesFromHereAndANewOneComes() {
        sync()
        server.lists.clear()
        server.lists["/dav/calendars/ada/garden/"] = "Garden"
        sync()
        assertEquals(listOf("Garden"), store.lists().filter { !it.onPhone }.map { it.name })
    }

    @Test
    fun theListKeptOnThePhoneIsNeverTouchedAndMovesUpWhole() {
        val phone = store.phoneList("On this phone")
        store.add(phone.id, Fields("Local only"), t0, uid = "p1")
        sync()
        assertTrue(server.files.isEmpty())
        assertEquals(1, store.items(PHONE).size)
        store.move(PHONE, store.items(PHONE).single().name, list)
        sync()
        assertTrue(store.items(PHONE).isEmpty())
        assertEquals("Local only", readTask(server.files.values.single().second)!!.fields.summary)
        assertNotNull(store.find("p1"))
    }

    @Test
    fun anUnchangedListIsNotAskedAgain() {
        server.write("/dav/calendars/ada/personal/web-1.ics", withExtras)
        sync()
        var asked = 0
        val counting = object : TaskServer by server {
            override fun items(listHref: String): List<RemoteItem> { asked++; return server.items(listHref) }
        }
        TaskSync(store, counting, now = { t0 }).run()
        assertEquals(0, asked)
        server.write("/dav/calendars/ada/personal/web-2.ics", withExtras.replace("web-1", "web-2"))
        TaskSync(store, counting, now = { t0 }).run()
        assertEquals(1, asked)
        assertEquals(2, store.items(list).size)
        assertNull(store.find("nobody"))
    }
}
