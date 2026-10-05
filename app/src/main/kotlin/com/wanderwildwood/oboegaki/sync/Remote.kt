package com.wanderwildwood.oboegaki.sync

/**
 * The far end of a sync: a folder of notes on a server, addressed by paths relative to it
 * ("ideas/bread.md"), each with an etag that changes whenever its contents do.
 *
 * An interface so that [Sync] can be run against a folder in memory, which is the only way to
 * test what happens when two phones edit one note without two phones.
 */
interface Remote {

    /** Every note under the folder, by path, with its etag. */
    fun list(): Map<String, String>

    /** The note at [path], and its etag as of the read. */
    fun get(path: String): Fetched

    /**
     * Put [text] at [path], only if [expect] still holds, and return the new etag if the
     * server says what it is. Throws [Moved] when it does not hold: someone else got there
     * first.
     */
    fun put(path: String, text: String, expect: Expect): String?

    /** Remove the note at [path], only if it is still [etag]. Throws [Moved] if it is not. */
    fun delete(path: String, etag: String)
}

/** A note as read from the server, and when the server says it last changed, if it says. */
data class Fetched(val text: String, val etag: String?, val modified: Long? = null)

/** What the server must still hold for a write to go ahead. */
sealed interface Expect {
    /** The note, unchanged since it was last seen with this etag. */
    data class Unchanged(val etag: String) : Expect

    /** Nothing at all: this is a new note. */
    data object Absent : Expect
}

/** The note was not as expected: it changed, appeared or vanished since it was last seen. */
class Moved(message: String) : Exception(message)
