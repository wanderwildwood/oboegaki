package com.wanderwildwood.oboegaki.notes

import com.wanderwildwood.oboegaki.sync.PINS
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/*
 * Locked notes: chosen notes taken off the shelf, so nothing that reads the notes (the sync, Desktop
 * Sync, Glance's lines, search, the folder another app carries) ever sees them, and kept in the
 * app's own storage encrypted.
 *
 * Two keys. A random note key (AES-256) encrypts every locked note, each with its own nonce, in
 * AES-GCM. The note key is kept in the vault, encrypted (AES-GCM) with a key derived from the PIN
 * by PBKDF2-HMAC-SHA256, with a random salt; the vault holds the salt, the iterations, the nonce
 * and that ciphertext, never the PIN. On the phone the vault is sealed once more with a key in
 * Android's keystore ([KeyWrap]), which cannot be taken off the phone: a copy of the files alone
 * cannot be tried against every PIN elsewhere. Changing the PIN seals the same note key again.
 */

/** The shortest and longest PIN, in digits. */
const val PIN_MIN = 4
const val PIN_MAX = 8

/** Whether [pin] can be a PIN: 4 to 8 digits. */
fun pinShapeOk(pin: String): Boolean = pin.length in PIN_MIN..PIN_MAX && pin.all { it in '0'..'9' }

object LockCrypto {
    /** PBKDF2 rounds for a new vault. Kept in the vault, so it can be raised later. */
    const val ITERATIONS = 210_000
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also(random::nextBytes)

    fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    class Sealed(val nonce: ByteArray, val body: ByteArray)

    fun seal(key: ByteArray, plain: ByteArray, aad: ByteArray): Sealed {
        val nonce = randomBytes(NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return Sealed(nonce, cipher.doFinal(plain))
    }

    /** What [seal] sealed, or null when [key] is not the key or anything in it was changed. */
    fun open(key: ByteArray, sealed: Sealed, aad: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, sealed.nonce))
        cipher.updateAAD(aad)
        cipher.doFinal(sealed.body)
    } catch (_: GeneralSecurityException) {
        null
    }
}

/** A second seal around the vault: the phone's keystore on the phone, none in the tests. */
interface KeyWrap {
    fun seal(bytes: ByteArray): ByteArray
    fun open(bytes: ByteArray): ByteArray

    object None : KeyWrap {
        override fun seal(bytes: ByteArray) = bytes
        override fun open(bytes: ByteArray) = bytes
    }
}

private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
private fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text.trim())

/** Written over by a temporary file and a rename, so a phone that dies mid-write keeps the old one. */
private fun writeAtomically(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val temp = File(file.parentFile, ".${file.name}.part")
    temp.writeBytes(bytes)
    if (!temp.renameTo(file)) {
        temp.delete()
        throw IOException("${file.name} could not be written")
    }
}

/** The note key, sealed with the PIN. See the top of this file. */
class Vault(
    private val file: File,
    private val wrap: KeyWrap,
    private val iterations: Int = LockCrypto.ITERATIONS,
) {
    private companion object {
        const val HEAD = "memo-vault 1"
    }

    fun exists(): Boolean = file.isFile

    /** A new vault with a new note key, sealed with [pin]; returns the note key. */
    fun create(pin: String): ByteArray {
        require(pinShapeOk(pin))
        val key = LockCrypto.randomBytes(32)
        write(pin, key)
        return key
    }

    /**
     * The note key, or null when [pin] is not the PIN. Throws when the vault cannot be read at
     * all (gone, or the keystore no longer opens it): that is no wrong PIN.
     */
    fun open(pin: String): ByteArray? {
        val text = wrap.open(file.readBytes()).toString(Charsets.UTF_8)
        val lines = text.split('\n')
        if (lines.size < 5 || lines[0] != HEAD) throw IOException("not a vault")
        val rounds = lines[1].substringAfter(' ').toIntOrNull() ?: throw IOException("not a vault")
        val salt = unb64(lines[2])
        val sealed = LockCrypto.Sealed(unb64(lines[3]), unb64(lines[4]))
        // Not even the shape is tried: no PIN of another shape was ever set.
        if (!pinShapeOk(pin)) return null
        val kek = LockCrypto.derive(pin, salt, rounds)
        return try {
            LockCrypto.open(kek, sealed, HEAD.toByteArray())
        } finally {
            kek.fill(0)
        }
    }

    /** The same note key sealed with [new] in place of [old]; false when [old] is not the PIN. */
    fun changePin(old: String, new: String): Boolean {
        require(pinShapeOk(new))
        val key = open(old) ?: return false
        try {
            write(new, key)
        } finally {
            key.fill(0)
        }
        return true
    }

    fun forget() {
        file.delete()
    }

    private fun write(pin: String, key: ByteArray) {
        val salt = LockCrypto.randomBytes(16)
        val kek = LockCrypto.derive(pin, salt, iterations)
        val sealed = try {
            LockCrypto.seal(kek, key, HEAD.toByteArray())
        } finally {
            kek.fill(0)
        }
        val text = listOf(HEAD, "pbkdf2-sha256 $iterations", b64(salt), b64(sealed.nonce), b64(sealed.body))
            .joinToString("\n", postfix = "\n")
        writeAtomically(file, wrap.seal(text.toByteArray(Charsets.UTF_8)))
    }
}

/** A locked note as the list knows it: no title, no words, only when it last changed. */
data class LockedNote(val id: String, val modified: Long)

/** What a locked note holds once opened: where it goes back to when unlocked, and its words. */
data class LockedContent(val path: String, val text: String) {
    val title: String get() = path.substringAfterLast('/').substringBeforeLast('.')
}

/** The locked notes, one encrypted file each, in the app's own storage. */
class LockedStore(private val dir: File) {
    private companion object {
        const val HEAD = "memo-locked 1"
        const val EXT = ".locked"
        val ID = Regex("^[0-9a-f]{32}$")
    }

    private fun fileOf(id: String): File {
        // Only ever names made by [newId]: nothing else is read or written here.
        require(ID.matches(id)) { "not a locked note's id" }
        return File(dir, id + EXT)
    }

    fun list(): List<LockedNote> =
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(EXT) && ID.matches(it.name.removeSuffix(EXT)) }
            .map { LockedNote(it.name.removeSuffix(EXT), it.lastModified()) }
            .sortedByDescending { it.modified }

    fun newId(): String {
        while (true) {
            val id = LockCrypto.randomBytes(16).joinToString("") { "%02x".format(it) }
            if (!fileOf(id).exists()) return id
        }
    }

    fun write(id: String, key: ByteArray, content: LockedContent, modified: Long = System.currentTimeMillis()) {
        val plain = (content.path + "\n" + content.text).toByteArray(Charsets.UTF_8)
        val sealed = LockCrypto.seal(key, plain, aad(id))
        val text = listOf(HEAD, b64(sealed.nonce), b64(sealed.body)).joinToString("\n", postfix = "\n")
        val file = fileOf(id)
        writeAtomically(file, text.toByteArray(Charsets.UTF_8))
        file.setLastModified(modified)
    }

    /** The note, or null when [key] does not open it. Throws when it is not there or not one. */
    fun read(id: String, key: ByteArray): LockedContent? {
        val lines = fileOf(id).readText().split('\n')
        if (lines.size < 3 || lines[0] != HEAD) throw IOException("not a locked note")
        val plain = LockCrypto.open(key, LockCrypto.Sealed(unb64(lines[1]), unb64(lines[2])), aad(id))
            ?.toString(Charsets.UTF_8) ?: return null
        val newline = plain.indexOf('\n')
        if (newline < 0) throw IOException("not a locked note")
        return LockedContent(plain.substring(0, newline), plain.substring(newline + 1))
    }

    fun exists(id: String): Boolean = fileOf(id).isFile

    fun delete(id: String) {
        fileOf(id).delete()
    }

    /** Every locked note gone, for good: when the PIN is forgotten. */
    fun deleteAll() {
        dir.listFiles().orEmpty().forEach { it.delete() }
    }

    // The id is sealed in with the words, so one note's file put in place of another's does not open.
    private fun aad(id: String) = "$HEAD $id".toByteArray()
}

/** How a lock went. */
sealed interface LockOutcome {
    data class Locked(val id: String) : LockOutcome
    /** No note there to lock. */
    data object NotThere : LockOutcome
    /** It links recordings, scans or pictures beside it: their files would stay readable. */
    data object HasAttachments : LockOutcome
}

/**
 * Lock the note at [path]: written into [store] sealed with [key], read back and checked, and only
 * then taken off [shelf], with its pin. From then on nothing that reads the shelf sees it; with
 * the notes on a server, the next sync deletes the server's copy as it would any note deleted here.
 * A note that links files beside it is not locked. Throws when the shelf would not read or let go
 * of it; then nothing is locked and the note stays as it was.
 */
fun lockOnShelf(shelf: Shelf, store: LockedStore, key: ByteArray, path: String): LockOutcome {
    synchronized(notesLock) {
        val text = shelf.read(path) ?: if (shelf.exists(path)) throw IOException("$path could not be read") else return LockOutcome.NotThere
        val folder = path.substringBeforeLast('/', "")
        if (embeds(text).any { shelf.exists(inFolder(folder, it)) }) return LockOutcome.HasAttachments
        val content = LockedContent(path, text)
        val id = store.newId()
        store.write(id, key, content)
        try {
            check(store.read(id, key) == content) { "the locked copy did not read back" }
            shelf.delete(path)
            if (shelf.exists(path)) throw IOException("$path could not be deleted")
        } catch (e: Exception) {
            store.delete(id)
            throw e
        }
        // The pins are a synced file of paths: a locked note's name is not left in it.
        runCatching { unpin(shelf, path) }
        return LockOutcome.Locked(id)
    }
}

/**
 * Unlock the locked note [id]: back on [shelf] as an ordinary note, where it was, or beside it
 * as "name 2" when that name has been taken since, and synced again from then on. Returns its
 * path, or null when [key] does not open it. The locked copy goes only once the note is there.
 */
fun unlockToShelf(shelf: Shelf, store: LockedStore, key: ByteArray, id: String): String? {
    synchronized(notesLock) {
        val content = store.read(id, key) ?: return null
        val target = freeOn(shelf, content.path)
        shelf.write(target, content.text)
        if (shelf.read(target) != content.text) throw IOException("$target did not take the note")
        store.delete(id)
        return target
    }
}

/** [path], or "name 2.md" and on beside it when that is taken. */
internal fun freeOn(shelf: Shelf, path: String): String {
    if (!shelf.exists(path)) return path
    val folder = path.substringBeforeLast('/', "")
    val name = path.substringAfterLast('/')
    val stem = name.substringBeforeLast('.')
    val ext = name.substringAfterLast('.', "md")
    var n = 2
    while (true) {
        val candidate = inFolder(folder, "$stem $n.$ext")
        if (!shelf.exists(candidate)) return candidate
        n++
    }
}

private fun unpin(shelf: Shelf, path: String) {
    val before = shelf.read(PINS) ?: return
    val after = before.lines().filter { it.trim().isNotEmpty() && it.trim() != path }.joinToString("") { "$it\n" }
    if (after != before) shelf.write(PINS, after)
}

/**
 * Wrong PINs: the first [FREE] cost nothing; after that each one means a wait before the next is
 * tried, [FIRST_WAIT_MS] and doubling to [LONGEST_WAIT_MS]. Kept through the app being closed.
 * [load] and [save] are the count and the time the wait ends.
 */
class Attempts(
    private val load: () -> Pair<Int, Long>,
    private val save: (Int, Long) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val FREE = 5
        const val FIRST_WAIT_MS = 30_000L
        const val LONGEST_WAIT_MS = 15 * 60_000L
    }

    /** How long until a PIN may be tried; 0 when it may be now. */
    fun waitMs(): Long {
        val (count, until) = load()
        val now = clock()
        // A clock put back does not make the wait longer than it ever is.
        val longest = waitFor(count)
        return (until - now).coerceIn(0, longest)
    }

    fun failed() {
        val (count, _) = load()
        val next = count + 1
        save(next, if (next >= FREE) clock() + waitFor(next) else 0L)
    }

    fun succeeded() = save(0, 0L)

    private fun waitFor(count: Int): Long {
        if (count < FREE) return 0L
        val doublings = (count - FREE).coerceAtMost(10)
        return (FIRST_WAIT_MS shl doublings).coerceAtMost(LONGEST_WAIT_MS)
    }
}
