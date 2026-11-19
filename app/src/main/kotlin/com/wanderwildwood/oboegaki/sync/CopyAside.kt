package com.wanderwildwood.oboegaki.sync

import java.io.File
import java.security.MessageDigest

/**
 * The phone's copy of a server's notes, in [dir], belongs to one account and folder ([from]).
 * When the sync is about to use another one ([to]), the copy is set aside in [aside], under a
 * name made from [from], and never synced into the new account: that may be someone else's.
 * Nothing in it is lost, notes not yet sent included. If [to] has a copy set aside from before,
 * it comes back, so what was waiting there goes up to its own account; otherwise the new
 * account's notes are fetched fresh into an empty [dir].
 *
 * Returns true when the copy in [dir] changed. [from] null means the copy is not known to be
 * anyone's (none yet, or kept by an older version); it is then taken as [to]'s.
 */
fun setCopyAside(dir: File, aside: File, from: String?, to: String): Boolean {
    if (from == null || from == to) return false
    if (dir.exists()) {
        aside.mkdirs()
        // One already there under that name (never brought back) is kept too, never written over.
        var keep = File(aside, asideName(from))
        var n = 2
        while (keep.exists()) keep = File(aside, asideName(from) + "-" + n++)
        if (!dir.renameTo(keep)) throw java.io.IOException("could not set the copy aside")
    }
    val back = File(aside, asideName(to))
    if (back.exists() && !back.renameTo(dir)) throw java.io.IOException("could not bring a copy back")
    return true
}

/** A file name for an account and folder: the same each time, and nothing of either readable. */
fun asideName(of: String): String =
    MessageDigest.getInstance("SHA-256").digest(of.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

/**
 * Whose the phone's Nextcloud data is: the server, written one way ([HomeAccount.who]), and the
 * user id. Never the login name: one user can sign in as "ada", "Ada" or "ada@example.org", and
 * signing in again another way is still the same account, whose copy and task lists stay.
 * Accounts kept by an older version read their user id as the login name, which is the id the
 * typed sign-in stored then, so they still match.
 */
fun accountKey(account: Account): String = HomeAccount.who(account.server, account.uid)

/** The account and notes folder a Nextcloud copy belongs to, as [setCopyAside] takes it. */
fun copyKey(account: Account, folder: String): String = accountKey(account) + "\n" + folder

/** A [copyKey] kept earlier (perhaps before the server was written one way) written as [copyKey] writes it now. */
fun keptCopyKey(kept: String?): String? =
    kept?.split('\n', limit = 3)?.let { if (it.size == 3) HomeAccount.who(it[0], it[1]) + "\n" + it[2] else null } ?: kept
