package com.wanderwildwood.oboegaki.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CopyAsideTest {

    @get:Rule val tmp = TemporaryFolder()

    private val a = "https://cloud.example.org\nada\nNotes"
    private val b = "https://cloud.example.org\nbea\nNotes"

    private fun dir() = File(tmp.root, "nextcloud")
    private fun aside() = File(tmp.root, "aside")
    private fun note(text: String) {
        File(dir(), "notes").mkdirs()
        File(dir(), "notes/a.md").writeText(text)
    }
    private fun noteNow(): String? = File(dir(), "notes/a.md").takeIf { it.isFile }?.readText()

    @Test fun sameAccountOrNoneKnownLeavesTheCopy() {
        note("ada's")
        assertFalse(setCopyAside(dir(), aside(), a, a))
        assertFalse(setCopyAside(dir(), aside(), null, b))
        assertEquals("ada's", noteNow())
    }

    @Test fun anotherAccountNeverSeesTheCopy() {
        note("ada's, not sent")
        assertTrue(setCopyAside(dir(), aside(), a, b))
        assertNull(noteNow())
        assertFalse(dir().exists())
    }

    @Test fun backToTheFirstAccountItsCopyComesBack() {
        note("ada's, not sent")
        setCopyAside(dir(), aside(), a, b)
        note("bea's")
        assertTrue(setCopyAside(dir(), aside(), b, a))
        assertEquals("ada's, not sent", noteNow())
        assertTrue(setCopyAside(dir(), aside(), a, b))
        assertEquals("bea's", noteNow())
    }

    @Test fun aCopyAlreadyAsideIsNeverWrittenOver() {
        note("first")
        setCopyAside(dir(), aside(), a, b)
        // As if it could not be brought back: a second copy of ada's is set aside beside it.
        File(aside(), asideName(a)).renameTo(File(tmp.root, "held"))
        File(tmp.root, "held").copyRecursively(File(aside(), asideName(a)))
        note("second")
        setCopyAside(dir(), aside(), a, b)
        assertEquals(2, aside().listFiles()!!.size)
        assertEquals("first", File(aside(), asideName(a) + "/notes/a.md").readText())
    }

    @Test fun asideNamesShowNothingOfTheAccount() {
        val name = asideName(a)
        assertFalse(name.contains("ada"))
        assertEquals(name, asideName(a))
        assertFalse(name == asideName(b))
    }

    // ---- whose copy: by user id, never the login name ----

    private val server = "https://cloud.example.org"

    @Test fun signingInAgainByEmailIsTheSameAccount() {
        // The typed sign-in keeps the login as typed and the id the server gave for it.
        val first = Account(server, "ada@example.org", "pw-1", uid = "ada")
        val again = Account("HTTPS://Cloud.Example.org:443/", "ada@example.org", "pw-2", uid = "ada")
        assertEquals(accountKey(first), accountKey(again))
        assertEquals(copyKey(first, "Notes"), keptCopyKey(copyKey(again, "Notes")))
        note("not sent yet")
        assertFalse(setCopyAside(dir(), aside(), keptCopyKey(copyKey(first, "Notes")), copyKey(again, "Notes")))
        assertEquals("not sent yet", noteNow())
    }

    @Test fun anotherLoginForTheSameUserIsTheSameAccount() {
        // Home's account signed in by email, and a sign-in here typed "Ada": one Nextcloud user.
        val home = Account(server, "ada@example.org", "pw", uid = "ada")
        val typed = Account(server, "Ada", "pw", uid = "ada")
        assertEquals(accountKey(home), accountKey(typed))
        assertEquals(copyKey(home, "Notes"), copyKey(typed, "Notes"))
    }

    @Test fun anAccountKeptBeforeTheUserIdMatchesAsItWas() {
        // Kept by an older version: no user id, so it reads as the login name, the id stored then.
        val kept = Account(server, "ada", "pw")
        assertEquals("ada", kept.uid)
        assertEquals(a, copyKey(kept, "Notes"))
        assertEquals(copyKey(kept, "Notes"), copyKey(Account(server, "ada@example.org", "pw", uid = "ada"), "Notes"))
        // A key kept before the server was written one way still matches.
        assertEquals(a, keptCopyKey("https://Cloud.Example.org/\nada\nNotes"))
        assertNull(keptCopyKey(null))
    }

    @Test fun anotherUserIsAnotherAccount() {
        assertFalse(accountKey(Account(server, "ada", "pw")) == accountKey(Account(server, "bea", "pw")))
        assertFalse(copyKey(Account(server, "ada", "pw"), "Notes") == copyKey(Account(server, "ada", "pw"), "Other"))
    }
}
