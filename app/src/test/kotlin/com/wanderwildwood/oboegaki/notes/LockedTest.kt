package com.wanderwildwood.oboegaki.notes

import com.wanderwildwood.oboegaki.glance.LockWords
import com.wanderwildwood.oboegaki.glance.PinnedNote
import com.wanderwildwood.oboegaki.glance.pinnedLines
import com.wanderwildwood.oboegaki.sync.Expect
import com.wanderwildwood.oboegaki.sync.Fetched
import com.wanderwildwood.oboegaki.sync.FetchedBytes
import com.wanderwildwood.oboegaki.sync.Moved
import com.wanderwildwood.oboegaki.sync.PINS
import com.wanderwildwood.oboegaki.sync.Remote
import com.wanderwildwood.oboegaki.sync.Sync
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A server folder in memory, with etags that move on every write, and every text ever put. */
private class Server : Remote {
    val files = mutableMapOf<String, Pair<String, String>>()
    val everPut = mutableListOf<String>()
    private var n = 0

    override fun list() = files.mapValues { it.value.second }
    override fun get(path: String): Fetched = files[path]?.let { Fetched(it.first, it.second) } ?: throw Moved("gone")
    override fun put(path: String, text: String, expect: Expect): String {
        val now = files[path]
        when (expect) {
            Expect.Absent -> if (now != null) throw Moved("exists")
            is Expect.Unchanged -> if (now?.second != expect.etag) throw Moved("changed")
        }
        everPut += text
        files[path] = text to "\"e${++n}\""
        return files[path]!!.second
    }
    override fun getBytes(path: String): FetchedBytes = throw Moved("gone")
    override fun putBytes(path: String, bytes: ByteArray, expect: Expect): String = throw Moved("no")
    override fun delete(path: String, etag: String) {
        if (files[path]?.second != etag) throw Moved("changed")
        files.remove(path)
    }
}

class LockedTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Few rounds: the stretching is the same code, only quicker for a test. */
    private val rounds = 1_000

    private val secret = "The safe's combination is 31-07-44"

    private fun vault(dir: File = tmp.root) = Vault(File(dir, "vault"), KeyWrap.None, rounds)
    private fun store(dir: File = tmp.root) = LockedStore(File(dir, "locked"))

    /** Every byte of every file under [dir], as text, to look for words that should not be there. */
    private fun everything(dir: File): String =
        dir.walkTopDown().filter { it.isFile }.joinToString("\n") { it.readBytes().toString(Charsets.ISO_8859_1) }

    // ---------------------------------------------------------------- the crypto

    @Test
    fun aLockedNoteOpensWithTheRightPinAndReadsBackWhole() {
        val v = vault()
        val key = v.create("2468")
        val s = store()
        val id = s.newId()
        val content = LockedContent("Private/Bank.md", "$secret\n\nÜnïcode, and a second line.")
        s.write(id, key, content)

        val again = v.open("2468")
        assertNotNull(again)
        assertArrayEquals(key, again)
        assertEquals(content, s.read(id, again!!))
        assertEquals("Bank", s.read(id, again)!!.title)
    }

    @Test
    fun aWrongPinOpensNothing() {
        val v = vault()
        v.create("2468")
        assertNull(v.open("2469"))
        assertNull(v.open("8642"))
        // Not a PIN's shape at all: nothing is tried.
        assertNull(v.open("24680000000"))
        assertNull(v.open("abcd"))
    }

    @Test
    fun aWrongKeyOpensNoNoteAndAChangedFileOpensNone() {
        val v = vault()
        val key = v.create("1357")
        val s = store()
        val id = s.newId()
        s.write(id, key, LockedContent("a.md", secret))
        assertNull(s.read(id, LockCrypto.randomBytes(32)))

        // One byte of the sealed words changed: it no longer opens, rather than opening wrong.
        val file = File(tmp.root, "locked/$id.locked")
        val lines = file.readText().split('\n').toMutableList()
        val body = java.util.Base64.getDecoder().decode(lines[2])
        body[3] = (body[3].toInt() xor 1).toByte()
        lines[2] = java.util.Base64.getEncoder().encodeToString(body)
        file.writeText(lines.joinToString("\n"))
        assertNull(s.read(id, key))
    }

    @Test
    fun oneNotesFilePutInPlaceOfAnothersDoesNotOpen() {
        val key = vault().create("1357")
        val s = store()
        val a = s.newId()
        val b = s.newId()
        s.write(a, key, LockedContent("a.md", "a"))
        s.write(b, key, LockedContent("b.md", "b"))
        File(tmp.root, "locked/$a.locked").copyTo(File(tmp.root, "locked/$b.locked"), overwrite = true)
        assertNull(s.read(b, key))
    }

    @Test
    fun neitherThePinNorTheWordsAreKeptInTheClear() {
        val v = vault()
        val key = v.create("97531")
        val s = store()
        s.write(s.newId(), key, LockedContent("Private/Bank.md", secret))
        val all = everything(tmp.root)
        assertFalse(all.contains("97531"))
        assertFalse(all.contains("31-07-44"))
        assertFalse(all.contains("Bank"))
        assertFalse(all.contains("Private"))
        // Salt, rounds, nonce and the sealed key are what the vault holds.
        val vaultText = File(tmp.root, "vault").readText().lines()
        assertEquals("memo-vault 1", vaultText[0])
        assertEquals("pbkdf2-sha256 $rounds", vaultText[1])
        assertEquals(5, vaultText.filter { it.isNotEmpty() }.size)
    }

    @Test
    fun twoVaultsWithTheSamePinHaveDifferentSaltsAndKeys() {
        val one = vault(tmp.newFolder("one"))
        val two = vault(tmp.newFolder("two"))
        val k1 = one.create("2468")
        val k2 = two.create("2468")
        assertFalse(k1.contentEquals(k2))
        assertFalse(File(tmp.root, "one/vault").readText() == File(tmp.root, "two/vault").readText())
    }

    @Test
    fun aNewPinOpensTheSameNotesAndTheOldOneNoLonger() {
        val v = vault()
        val key = v.create("1111")
        val s = store()
        val id = s.newId()
        s.write(id, key, LockedContent("n.md", secret))
        assertFalse(v.changePin("2222", "3333"))
        assertTrue(v.changePin("1111", "3333"))
        assertNull(v.open("1111"))
        val now = v.open("3333")!!
        assertEquals(secret, s.read(id, now)!!.text)
    }

    @Test
    fun pinsAreFourToEightDigits() {
        assertTrue(pinShapeOk("1234"))
        assertTrue(pinShapeOk("12345678"))
        assertFalse(pinShapeOk("123"))
        assertFalse(pinShapeOk("123456789"))
        assertFalse(pinShapeOk("12a4"))
        assertFalse(pinShapeOk("12 34"))
    }

    @Test
    fun theKeystoreSealIsUsedForTheVault() {
        val flipped = object : KeyWrap {
            override fun seal(bytes: ByteArray) = bytes.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
            override fun open(bytes: ByteArray) = seal(bytes)
        }
        val v = Vault(File(tmp.root, "vault"), flipped, rounds)
        val key = v.create("2468")
        // Without the seal, the vault cannot even be read as one.
        assertFalse(File(tmp.root, "vault").readText().startsWith("memo-vault"))
        assertArrayEquals(key, v.open("2468"))
        val unsealed = Vault(File(tmp.root, "vault"), KeyWrap.None, rounds)
        assertTrue(runCatching { unsealed.open("2468") }.isFailure)
    }

    // ---------------------------------------------------------------- wrong PINs

    @Test
    fun afterFiveWrongPinsEachMoreWaitsLongerAndARightOneStartsAgain() {
        var count = 0
        var until = 0L
        var now = 1_000_000L
        val a = Attempts(load = { count to until }, save = { c, u -> count = c; until = u }, clock = { now })
        repeat(4) { a.failed() }
        assertEquals(0, a.waitMs())
        a.failed()
        assertEquals(30_000L, a.waitMs())
        now += 30_000L
        assertEquals(0, a.waitMs())
        a.failed()
        assertEquals(60_000L, a.waitMs())
        now += 10_000L
        assertEquals(50_000L, a.waitMs())
        // A clock put back a day does not make it a day's wait.
        now -= 86_400_000L
        assertEquals(60_000L, a.waitMs())
        repeat(20) { a.failed() }
        assertEquals(Attempts.LONGEST_WAIT_MS, a.waitMs())
        a.succeeded()
        assertEquals(0, a.waitMs())
        assertEquals(0, count)
    }

    // ---------------------------------------------------------------- off the shelf

    private fun shelfWith(vararg notes: Pair<String, String>): Pair<File, FileShelf> {
        val dir = tmp.newFolder("notes")
        for ((path, text) in notes) File(dir, path).apply { parentFile?.mkdirs() }.writeText(text)
        return dir to FileShelf(dir)
    }

    @Test
    fun lockingTakesTheNoteAndItsPinOffTheShelfSoNothingThatReadsItSeesIt() {
        val (dir, shelf) = shelfWith("Private/Bank.md" to secret, "Shopping.md" to "- [ ] milk", PINS to "Private/Bank.md\nShopping.md\n")
        val key = vault().create("2468")
        val s = store()

        val out = lockOnShelf(shelf, s, key, "Private/Bank.md")
        assertTrue(out is LockOutcome.Locked)

        // The list (the screens, search, Desktop Sync's listNow), the files a move or the sync walks,
        // and the pins Glance reads: none of them has it, by name or by word.
        assertEquals(listOf("Shopping.md"), shelf.list().map { it.path })
        assertFalse(shelf.files().any { it.contains("Bank") })
        assertEquals("Shopping.md\n", shelf.read(PINS))
        assertFalse(everything(dir).contains("31-07-44"))
        assertFalse(everything(dir).contains("Bank"))

        // And it is all there, locked.
        assertEquals(1, s.list().size)
        assertEquals(LockedContent("Private/Bank.md", secret), s.read((out as LockOutcome.Locked).id, key))
    }

    @Test
    fun glancesPinnedLinesNoLongerHaveALockedNote() {
        val (_, shelf) = shelfWith("Bank.md" to "- [ ] $secret", "Shopping.md" to "- [ ] milk", PINS to "Bank.md\nShopping.md\n")
        val key = vault().create("2468")
        lockOnShelf(shelf, store(), key, "Bank.md")
        // As PinnedOnLockScreen reads them: the pins, then each pinned note there.
        val pinned = shelf.read(PINS)!!.lines().filter { it.isNotBlank() }.mapNotNull { p -> shelf.read(p)?.let { PinnedNote(p, it) } }
        val words = LockWords("Notes", { "$it · all done" }, { t, n -> "$t · $n to do" }, { "and $it more" })
        val lines = pinnedLines(pinned, emptySet(), words).joinToString("\n") { it.text }
        assertFalse(lines.contains("Bank"))
        assertFalse(lines.contains("31-07-44"))
        assertTrue(lines.contains("milk"))
    }

    @Test
    fun aNoteWithRecordingsScansOrPicturesBesideItIsNotLocked() {
        val (dir, shelf) = shelfWith("Voice 2026-11-19.md" to "![[Voice 2026-11-19.m4a]]\n\nwords")
        File(dir, "Voice 2026-11-19.m4a").writeBytes(byteArrayOf(1, 2, 3))
        val key = vault().create("2468")
        assertEquals(LockOutcome.HasAttachments, lockOnShelf(shelf, store(), key, "Voice 2026-11-19.md"))
        assertEquals("![[Voice 2026-11-19.m4a]]\n\nwords", shelf.read("Voice 2026-11-19.md"))
        assertTrue(store().list().isEmpty())
    }

    @Test
    fun unlockingPutsItBackWhereItWasOrBesideANoteNowOfThatName() {
        val (_, shelf) = shelfWith("Private/Bank.md" to secret)
        val key = vault().create("2468")
        val s = store()
        val id = (lockOnShelf(shelf, s, key, "Private/Bank.md") as LockOutcome.Locked).id
        assertNull(unlockToShelf(shelf, s, LockCrypto.randomBytes(32), id))
        assertEquals(1, s.list().size)

        assertEquals("Private/Bank.md", unlockToShelf(shelf, s, key, id))
        assertEquals(secret, shelf.read("Private/Bank.md"))
        assertTrue(s.list().isEmpty())

        val id2 = (lockOnShelf(shelf, s, key, "Private/Bank.md") as LockOutcome.Locked).id
        shelf.write("Private/Bank.md", "another note, made since")
        assertEquals("Private/Bank 2.md", unlockToShelf(shelf, s, key, id2))
        assertEquals("another note, made since", shelf.read("Private/Bank.md"))
        assertEquals(secret, shelf.read("Private/Bank 2.md"))
    }

    // ---------------------------------------------------------------- the sync

    private fun phone(): Pair<FileShelf, Sync> {
        val root = tmp.newFolder("phone")
        val notes = File(root, "notes").apply { mkdirs() }
        return FileShelf(notes) to Sync(notes, File(root, "base"), File(root, "state"))
    }

    @Test
    fun aNoteLockedAfterSyncingIsDeletedOnTheServerAndNeverSentAgain() {
        val server = Server()
        val (shelf, sync) = phone()
        shelf.write("Bank.md", secret)
        shelf.write("Shopping.md", "- [ ] milk")
        sync.run(server)
        assertEquals(setOf("Bank.md", "Shopping.md"), server.files.keys)

        val key = vault().create("2468")
        val s = store()
        val id = (lockOnShelf(shelf, s, key, "Bank.md") as LockOutcome.Locked).id
        val sent = server.everPut.size
        sync.run(server)
        assertEquals(setOf("Shopping.md"), server.files.keys)

        // Edited while locked, and synced again and again: nothing of it goes up.
        s.write(id, key, LockedContent("Bank.md", "$secret, and the new PIN 9182"))
        sync.run(server)
        sync.run(server)
        assertEquals(sent, server.everPut.size)
        assertFalse(server.everPut.any { it.contains("9182") })

        // Unlocked, it is an ordinary note again and goes up.
        unlockToShelf(shelf, s, key, id)
        sync.run(server)
        assertEquals("$secret, and the new PIN 9182", server.files["Bank.md"]?.first)
    }

    @Test
    fun aNoteLockedBeforeItWasEverSyncedNeverReachesTheServer() {
        val server = Server()
        val (shelf, sync) = phone()
        shelf.write("Shopping.md", "- [ ] milk")
        sync.run(server)
        shelf.write("Diary.md", secret)
        lockOnShelf(shelf, store(), vault().create("2468"), "Diary.md")
        sync.run(server)
        assertEquals(setOf("Shopping.md"), server.files.keys)
        assertFalse(server.everPut.any { it.contains("31-07-44") })
    }

    @Test
    fun aNoteChangedOnAnotherDeviceBeforeTheDeletionWentUpComesBackAsThatVersion() {
        // The sync's own rule: a deletion here of a version this phone never saw is not made. The
        // other device's version comes back as an ordinary note; the locked one stays locked.
        val server = Server()
        val (shelf, sync) = phone()
        shelf.write("Bank.md", secret)
        sync.run(server)
        val key = vault().create("2468")
        val s = store()
        val id = (lockOnShelf(shelf, s, key, "Bank.md") as LockOutcome.Locked).id
        server.files["Bank.md"] = "edited elsewhere" to "\"other\""
        sync.run(server)
        assertEquals("edited elsewhere", shelf.read("Bank.md"))
        assertEquals(secret, s.read(id, key)!!.text)
    }
}
