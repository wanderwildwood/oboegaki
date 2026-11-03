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
    /** Some servers do not say the new etag after a write; this one can be made not to. */
    var silentPuts = false

    /** Some move a file's etag with nothing changed in it, as Nextcloud does on sharing. */
    fun touch(path: String) {
        files[path]?.let { files[path] = it.first to "\"t${++counter}\"" }
        blobs[path]?.let { blobs[path] = it.first to "\"t${++counter}\"" }
    }
    val files = mutableMapOf<String, Pair<String, String>>()
    val dated = mutableMapOf<String, Long>()
    private var counter = 0

    fun set(path: String, text: String) {
        files[path] = text to "\"e${++counter}\""
        ids.getOrPut(path) { ++lastId }
    }

    /**
     * Each file's id, as Nextcloud keeps one: the same through a write and a MOVE, new for a file
     * deleted and put again. A share hangs on the id ([shares]: id to person), as Nextcloud's do
     * (oc_share.file_source), so a deletion takes the share with it and a MOVE does not.
     */
    val ids = mutableMapOf<String, Long>()
    private var lastId = 0L
    val shares = mutableMapOf<Long, String>()
    var moves = 0

    fun share(path: String, with: String) { shares[ids.getValue(path)] = with }
    fun sharedWith(path: String): String? = ids[path]?.let { shares[it] }

    override fun move(from: String, to: String) {
        if (files.containsKey(to) || blobs.containsKey(to)) throw Moved("taken")
        when {
            files.containsKey(from) -> files[to] = files.remove(from)!!
            blobs.containsKey(from) -> blobs[to] = blobs.remove(from)!!
            else -> throw Moved("gone")
        }
        ids[to] = ids.remove(from)!!
        moves++
    }

    private fun forgetId(path: String) { ids.remove(path)?.let { shares.remove(it) } }

    override fun get(path: String): Fetched {
        val (text, etag) = files[path] ?: throw Moved("gone")
        return Fetched(text, etag, dated[path])
    }

    override fun put(path: String, text: String, expect: Expect): String? {
        val now = files[path]
        when (expect) {
            Expect.Absent -> if (now != null) throw Moved("exists")
            is Expect.Unchanged -> if (now?.second != expect.etag) throw Moved("changed")
        }
        set(path, text)
        return if (silentPuts) null else files[path]!!.second
    }

    val blobs = mutableMapOf<String, Pair<ByteArray, String>>()

    override fun list() = files.mapValues { it.value.second } + blobs.mapValues { it.value.second }

    override fun getBytes(path: String): FetchedBytes {
        val (bytes, etag) = blobs[path] ?: throw Moved("gone")
        return FetchedBytes(bytes, etag)
    }

    override fun putBytes(path: String, bytes: ByteArray, expect: Expect): String? {
        val now = blobs[path]
        when (expect) {
            Expect.Absent -> if (now != null) throw Moved("exists")
            is Expect.Unchanged -> if (now?.second != expect.etag) throw Moved("changed")
        }
        blobs[path] = bytes to "\"b${++counter}\""
        ids.getOrPut(path) { ++lastId }
        return if (silentPuts) null else blobs[path]!!.second
    }

    override fun delete(path: String, etag: String) {
        if (blobs.containsKey(path)) {
            if (blobs[path]?.second != etag) throw Moved("changed")
            blobs.remove(path)
            forgetId(path)
            return
        }
        if (files[path]?.second != etag) throw Moved("changed")
        files.remove(path)
        forgetId(path)
    }
}

/** One phone: its notes folder, and the sync that keeps it. */
private class Phone(root: File) {
    val notes = File(root, "notes")
    val state = File(root, "state")
    val sync = Sync(notes, File(root, "base"), state)

    fun write(path: String, text: String) = File(notes, path).apply { parentFile?.mkdirs() }.writeText(text)
    fun read(path: String): String? = File(notes, path).takeIf { it.isFile }?.readText()
    fun delete(path: String) = File(notes, path).delete()
    fun writeBytes(path: String, bytes: ByteArray) = File(notes, path).apply { parentFile?.mkdirs() }.writeBytes(bytes)
    fun readBytes(path: String): ByteArray? = File(notes, path).takeIf { it.isFile }?.readBytes()
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

    @Test
    fun aReceivedNoteKeepsTheServersDate() {
        val his = phone("his")
        server.set("old.md", "written long ago")
        server.dated["old.md"] = 1_600_000_000_000L
        his.sync.run(server)
        assertEquals(1_600_000_000_000L, File(his.notes, "old.md").lastModified())
    }

    @Test
    fun aRecordingMadeOnOnePhoneArrivesOnTheOther() {
        val his = phone("his")
        val hers = phone("hers")
        val sound = ByteArray(5000) { (it % 251).toByte() }
        his.writeBytes("Voice 0214.m4a", sound)
        his.write("Voice 0214.md", "![[Voice 0214.m4a]]\n")
        his.sync.run(server)
        hers.sync.run(server)
        assertTrue(sound.contentEquals(hers.readBytes("Voice 0214.m4a")))
        assertEquals("![[Voice 0214.m4a]]\n", hers.read("Voice 0214.md"))
    }

    @Test
    fun aRecordingDeletedOnOnePhoneGoesEverywhere() {
        val his = phone("his")
        val hers = phone("hers")
        his.writeBytes("a.m4a", byteArrayOf(1, 2, 3))
        his.sync.run(server)
        hers.sync.run(server)
        hers.delete("a.m4a")
        hers.sync.run(server)
        assertFalse(server.blobs.containsKey("a.m4a"))
        his.sync.run(server)
        assertNull(his.readBytes("a.m4a"))
    }

    @Test
    fun twoDifferentFilesUnderOneNameAreBothKept() {
        val his = phone("his")
        val hers = phone("hers")
        his.writeBytes("scan.pdf", byteArrayOf(1))
        hers.writeBytes("scan.pdf", byteArrayOf(2))
        his.sync.run(server)
        val result = hers.sync.run(server)
        assertEquals(listOf("scan (this phone).pdf"), result.kept)
        assertTrue(byteArrayOf(1).contentEquals(hers.readBytes("scan.pdf")))
        assertTrue(byteArrayOf(2).contentEquals(hers.readBytes("scan (this phone).pdf")))
        assertTrue(byteArrayOf(2).contentEquals(server.blobs["scan (this phone).pdf"]!!.first))
    }

    @Test
    fun anUnchangedRecordingIsNotSentAgain() {
        val his = phone("his")
        his.writeBytes("a.m4a", byteArrayOf(9))
        his.sync.run(server)
        val again = his.sync.run(server)
        assertEquals(0, again.sent)
        assertEquals(0, again.received)
    }

    @Test
    fun aDeletionStaysDeletedWhenTheServerNeverSaidItsEtag() {
        server.silentPuts = true
        val his = phone("his")
        his.write("Voice.md", "![[Voice.m4a]]\n")
        his.writeBytes("Voice.m4a", byteArrayOf(1, 2, 3))
        his.sync.run(server)
        his.delete("Voice.md")
        his.delete("Voice.m4a")
        his.sync.run(server)
        assertNull(his.read("Voice.md"))
        assertNull(his.readBytes("Voice.m4a"))
        assertFalse(server.files.containsKey("Voice.md"))
        assertFalse(server.blobs.containsKey("Voice.m4a"))
    }

    @Test
    fun aDeletionStaysDeletedWhenOnlyTheEtagMoved() {
        val his = phone("his")
        his.write("Voice.md", "![[Voice.m4a]]\n")
        his.writeBytes("Voice.m4a", byteArrayOf(1, 2, 3))
        his.sync.run(server)
        server.touch("Voice.md")
        server.touch("Voice.m4a")
        his.delete("Voice.md")
        his.delete("Voice.m4a")
        his.sync.run(server)
        assertNull(his.read("Voice.md"))
        assertNull(his.readBytes("Voice.m4a"))
        assertTrue(server.files.isEmpty())
        assertTrue(server.blobs.isEmpty())
    }

    @Test
    fun aDeletionIsUndoneWhenTheNoteReallyChangedThere() {
        val his = phone("his")
        val hers = phone("hers")
        his.write("list.md", "a")
        his.sync.run(server)
        hers.sync.run(server)
        hers.write("list.md", "a and b")
        hers.sync.run(server)
        his.delete("list.md")
        his.sync.run(server)
        assertEquals("a and b", his.read("list.md"))
    }

    @Test
    fun pinsSyncAndMergeLikeANote() {
        val phone = phone("phone")
        val computer = phone("computer")
        phone.write(".pinned", "Groceries.md\n")
        phone.sync.run(server)
        computer.sync.run(server)
        assertEquals("Groceries.md\n", computer.read(".pinned"))
        // Pinned on each at once: both pins kept.
        phone.write(".pinned", "Groceries.md\nideas/kiln.md\n")
        computer.write(".pinned", "Groceries.md\nWords.md\n")
        phone.sync.run(server)
        computer.sync.run(server)
        // Which of the two comes first depends on who synced first; that both are there does not.
        assertEquals(
            setOf("Groceries.md", "ideas/kiln.md", "Words.md"),
            computer.read(".pinned")!!.lines().filter { it.isNotBlank() }.toSet(),
        )
    }

    @Test
    fun otherHiddenFilesStillStayHome() {
        val his = phone("his")
        his.write(".obsidian.md", "x")
        his.write("ideas/.pinned", "x")
        his.sync.run(server)
        assertTrue(server.files.isEmpty())
    }

    @Test
    fun aNoteMovedToAnotherFolderMovesOnTheServerAndOnTheOtherPhone() {
        val his = phone("his")
        val hers = phone("hers")
        val sound = byteArrayOf(7, 8, 9)
        his.writeBytes("Voice 0214.m4a", sound)
        his.write("Voice 0214.md", "Creek\n![[Voice 0214.m4a]]\n")
        his.sync.run(server)
        hers.sync.run(server)

        // Moved on his phone, the way the note's "Move to folder" moves it.
        val shelf = com.wanderwildwood.oboegaki.notes.FileShelf(his.notes)
        val moved = com.wanderwildwood.oboegaki.notes.moveOnShelf(shelf, "Voice 0214.md", "Field notes", shelf.list())!!
        assertEquals("Field notes/Voice 0214.md", moved.path)
        recordMoves(his.state, moved.moves)
        his.sync.run(server)

        // On Nextcloud the folder is the note's category: the note is there now, and not at the top.
        assertEquals("Creek\n![[Voice 0214.m4a]]\n", server.files["Field notes/Voice 0214.md"]?.first)
        assertNull(server.files["Voice 0214.md"])
        assertTrue(server.blobs.containsKey("Field notes/Voice 0214.m4a"))

        hers.sync.run(server)
        assertEquals("Creek\n![[Voice 0214.m4a]]\n", hers.read("Field notes/Voice 0214.md"))
        assertNull(hers.read("Voice 0214.md"))
        assertTrue(sound.contentEquals(hers.readBytes("Field notes/Voice 0214.m4a")))
    }

    @Test
    fun aNoteMovedHereWhileEditedThereIsNotLost() {
        val his = phone("his")
        val hers = phone("hers")
        his.write("kiln.md", "fire\n")
        his.sync.run(server)
        hers.sync.run(server)

        hers.write("kiln.md", "fire\nglaze\n")
        hers.sync.run(server)
        val shelf = com.wanderwildwood.oboegaki.notes.FileShelf(his.notes)
        val moved = com.wanderwildwood.oboegaki.notes.moveOnShelf(shelf, "kiln.md", "Shed", shelf.list())!!
        recordMoves(his.state, moved.moves)
        his.sync.run(server)

        // The server moved the note as it stood, her edit in it; his phone takes that. Nothing is dropped.
        assertEquals("fire\nglaze\n", his.read("Shed/kiln.md"))
        assertNull(his.read("kiln.md"))
        hers.sync.run(server)
        assertEquals("fire\nglaze\n", hers.read("Shed/kiln.md"))
        assertNull(hers.read("kiln.md"))
    }

    // ------------------------------------------------ moves keep the file, and its share

    /** Moves [path] to [folder] on [phone] as the app does, noting the moves for the sync. */
    private fun moveThere(phone: Phone, path: String, folder: String): String {
        val shelf = com.wanderwildwood.oboegaki.notes.FileShelf(phone.notes)
        val moved = com.wanderwildwood.oboegaki.notes.moveOnShelf(shelf, path, folder, shelf.list())!!
        recordMoves(phone.state, moved.moves)
        return moved.path
    }

    @Test
    fun aSharedNoteMovedToAnotherFolderKeepsItsShare() {
        val his = phone("his")
        his.writeBytes("creek.m4a", byteArrayOf(1, 2))
        his.write("creek.md", "Creek\n![[creek.m4a]]\n")
        his.sync.run(server)
        server.share("creek.md", "ada")
        server.share("creek.m4a", "ada")
        val id = server.ids.getValue("creek.md")

        assertEquals("Field notes/creek.md", moveThere(his, "creek.md", "Field notes"))
        his.sync.run(server)

        assertEquals(id, server.ids["Field notes/creek.md"])
        assertEquals("ada", server.sharedWith("Field notes/creek.md"))
        assertEquals("ada", server.sharedWith("Field notes/creek.m4a"))
        assertNull(server.files["creek.md"])
        assertEquals(2, server.moves)
        // Nothing left to do: a second sync sends and fetches nothing.
        val again = his.sync.run(server)
        assertEquals(0, again.sent)
        assertFalse(movesFileFor(his.state).exists())
    }

    @Test
    fun aSharedNoteArchivedAndBroughtBackKeepsItsShare() {
        val his = phone("his")
        val hers = phone("hers")
        his.write("Garden/kiln.md", "fire\n")
        his.sync.run(server)
        hers.sync.run(server)
        server.share("Garden/kiln.md", "tomas")
        val id = server.ids.getValue("Garden/kiln.md")

        // Archive is a move into Archive/, under the folder it was in.
        assertEquals("Archive/Garden/kiln.md", moveThere(his, "Garden/kiln.md", "Archive/Garden"))
        his.sync.run(server)
        assertEquals(id, server.ids["Archive/Garden/kiln.md"])
        assertEquals("tomas", server.sharedWith("Archive/Garden/kiln.md"))
        hers.sync.run(server)
        assertEquals("fire\n", hers.read("Archive/Garden/kiln.md"))
        assertNull(hers.read("Garden/kiln.md"))

        // Brought back, and moved again before the next sync: both made as moves, in order.
        moveThere(his, "Archive/Garden/kiln.md", "Garden")
        moveThere(his, "Garden/kiln.md", "Shed")
        his.sync.run(server)
        assertEquals(id, server.ids["Shed/kiln.md"])
        assertEquals("tomas", server.sharedWith("Shed/kiln.md"))
    }

    @Test
    fun withoutAMoveTheShareWouldHaveGone() {
        // The check can fail: the old way, a deletion and a new file, loses the share.
        val his = phone("his")
        his.write("kiln.md", "fire\n")
        his.sync.run(server)
        server.share("kiln.md", "tomas")
        his.delete("kiln.md")
        his.write("Shed/kiln.md", "fire\n")
        his.sync.run(server)
        assertNull(server.sharedWith("Shed/kiln.md"))
    }

    @Test
    fun aMoveTheServerRefusesFallsBackToADeletionAndANewFile() {
        val his = phone("his")
        his.write("kiln.md", "fire\n")
        his.sync.run(server)
        server.set("Shed/kiln.md", "someone else's")
        // The name is free here but taken on the server, so the MOVE is refused.
        moveThere(his, "kiln.md", "Shed")
        his.sync.run(server)
        assertEquals(0, server.moves)
        assertNull(server.files["kiln.md"])
        assertFalse(movesFileFor(his.state).exists())
        // Both kept: nothing is dropped.
        assertTrue(server.files.values.any { "fire" in it.first })
        assertTrue(server.files.values.any { "someone else's" in it.first })
    }
}
