package com.wanderwildwood.oboegaki.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A server folder in memory, with etags that move on every write, as Nextcloud's do. */
private class FakeRemote : Remote {
    val files = mutableMapOf<String, Pair<String, String>>()
    private var counter = 0

    fun set(path: String, text: String) {
        files[path] = text to "\"e${++counter}\""
    }

    override fun list() = files.mapValues { it.value.second }

    override fun get(path: String): Fetched {
        val (text, etag) = files[path] ?: throw Moved("gone")
        return Fetched(text, etag)
    }

    override fun put(path: String, text: String, expect: Expect): String? {
        val now = files[path]
        when (expect) {
            Expect.Absent -> if (now != null) throw Moved("exists")
            is Expect.Unchanged -> if (now?.second != expect.etag) throw Moved("changed")
        }
        set(path, text)
        return files[path]!!.second
    }

    override fun delete(path: String, etag: String) {
        if (files[path]?.second != etag) throw Moved("changed")
        files.remove(path)
    }
}

/** One phone: its notes folder, and the sync that keeps it. */
private class Phone(root: File) {
    val notes = File(root, "notes")
    val sync = Sync(notes, File(root, "base"), File(root, "state"))

    fun write(path: String, text: String) = File(notes, path).apply { parentFile?.mkdirs() }.writeText(text)
    fun read(path: String): String? = File(notes, path).takeIf { it.isFile }?.readText()
    fun delete(path: String) = File(notes, path).delete()
}

class SyncTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val server = FakeRemote()
    private fun phone(name: String) = Phone(temp.newFolder(name))

    @Test
    fun aNoteWrittenOnOnePhoneArrivesOnTheOther() {
        val his = phone("his")
        val hers = phone("hers")
        his.write("groceries.md", "- [ ] milk\n")
        his.sync.run(server)
        hers.sync.run(server)
        assertEquals("- [ ] milk\n", hers.read("groceries.md"))
    }

    @Test
    fun notesInFoldersKeepTheirFolders() {
        val his = phone("his")
        val hers = phone("hers")
        server.set("field notes/creek.md", "cold")
        his.sync.run(server)
        assertEquals("cold", his.read("field notes/creek.md"))
        his.write("ideas/kiln.md", "wood")
        his.sync.run(server)
        hers.sync.run(server)
        assertEquals("wood", hers.read("ideas/kiln.md"))
    }

    @Test
    fun bothEditingTheListAtOnceKeepsBothEdits() {
        val his = phone("his")
        val hers = phone("hers")
        his.write("groceries.md", "- [ ] milk\n- [ ] bread\n")
        his.sync.run(server)
        hers.sync.run(server)

        his.write("groceries.md", "- [x] milk\n- [ ] bread\n")
        hers.write("groceries.md", "- [ ] milk\n- [ ] bread\n- [ ] eggs\n")
        his.sync.run(server)
        hers.sync.run(server)
        his.sync.run(server)

        val both = "- [x] milk\n- [ ] bread\n- [ ] eggs\n"
        assertEquals(both, hers.read("groceries.md"))
        assertEquals(both, his.read("groceries.md"))
        assertEquals(both, server.files["groceries.md"]!!.first)
    }

    @Test
    fun aRealDisagreementKeepsBothCopies() {
        val his = phone("his")
        val hers = phone("hers")
        his.write("plan.md", "Saturday")
        his.sync.run(server)
        hers.sync.run(server)

        his.write("plan.md", "Sunday")
        hers.write("plan.md", "Monday")
        his.sync.run(server)
        val result = hers.sync.run(server)

        assertEquals(listOf("plan (this phone).md"), result.kept)
        assertEquals("Sunday", hers.read("plan.md"))
        assertEquals("Monday", hers.read("plan (this phone).md"))
        assertEquals("Monday", server.files["plan (this phone).md"]!!.first)

        his.sync.run(server)
        assertEquals("Monday", his.read("plan (this phone).md"))
    }

    @Test
    fun deletingOnOnePhoneDeletesEverywhere() {
        val his = phone("his")
        val hers = phone("hers")
        his.write("old.md", "x")
        his.sync.run(server)
        hers.sync.run(server)

        his.delete("old.md")
        his.sync.run(server)
        assertFalse(server.files.containsKey("old.md"))
        hers.sync.run(server)
        assertNull(hers.read("old.md"))
    }

    @Test
    fun aNoteDeletedThereButEditedHereComesBack() {
        val his = phone("his")
        val hers = phone("hers")
        his.write("keep.md", "a")
        his.sync.run(server)
        hers.sync.run(server)

        his.delete("keep.md")
        his.sync.run(server)
        hers.write("keep.md", "a and b")
        hers.sync.run(server)

        assertEquals("a and b", server.files["keep.md"]!!.first)
    }

    @Test
    fun aNoteDeletedHereButEditedThereComesBack() {
        val his = phone("his")
        val hers = phone("hers")
        his.write("keep.md", "a")
        his.sync.run(server)
        hers.sync.run(server)

        hers.write("keep.md", "a and b")
        hers.sync.run(server)
        his.delete("keep.md")
        his.sync.run(server)

        assertEquals("a and b", his.read("keep.md"))
        assertTrue(server.files.containsKey("keep.md"))
    }

    @Test
    fun nothingChangedSendsNothing() {
        val his = phone("his")
        his.write("a.md", "a")
        his.sync.run(server)
        val again = his.sync.run(server)
        assertEquals(0, again.sent)
        assertEquals(0, again.received)
    }

    @Test
    fun hiddenFilesAndOtherKindsAreLeftAlone() {
        val his = phone("his")
        his.write(".obsidian/workspace.md", "x")
        his.write("photo.jpg", "x")
        his.write(".hidden.md", "x")
        his.sync.run(server)
        assertTrue(server.files.isEmpty())
    }

    @Test
    fun besideNamesKeepTheFolderAndExtension() {
        assertEquals("ideas/kiln (this phone).md", besideName("ideas/kiln.md"))
        assertEquals("kiln (this phone 2).md", besideName("kiln.md", 2))
        assertEquals("README (this phone)", besideName("README"))
    }
}
