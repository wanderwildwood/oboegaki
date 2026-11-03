package com.wanderwildwood.oboegaki.desktop

import com.wanderwildwood.oboegaki.notes.Note
import com.wanderwildwood.oboegaki.sync.Incoming
import com.wanderwildwood.oboegaki.sync.Person
import com.wanderwildwood.oboegaki.sync.Share
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ShareRulesTest {

    private val ada = Person("ada", "Ada Whitlock")
    private val tomas = Person("tomas", "Tomas Reyes")
    private val toTomas = Share("17", "tomas", "Tomas Reyes", canEdit = true)

    private fun refused(status: Int, block: () -> Unit) {
        try {
            block()
            fail("expected a refusal")
        } catch (r: Refusal) {
            assertEquals(status, r.status)
        }
    }

    @Test
    fun onlyAPersonTheServerListedCanBeSharedWith() {
        assertEquals(ada, requirePerson("ada", listOf(ada, tomas)))
        refused(404) { requirePerson("mallory", listOf(ada, tomas)) }
        refused(400) { requirePerson("", listOf(ada)) }
        refused(400) { requirePerson(null, listOf(ada)) }
        refused(400) { requirePerson("a".repeat(500), listOf(ada)) }
    }

    @Test
    fun onlyThisNotesOwnShareCanBeChangedOrStopped() {
        assertEquals(toTomas, requireShare("17", listOf(toTomas)))
        // A share id of some other file on the account is not this note's to stop.
        refused(404) { requireShare("18", listOf(toTomas)) }
        refused(404) { requireShare("17", emptyList()) }
        refused(400) { requireShare("", listOf(toTomas)) }
        refused(400) { requireShare("1".repeat(50), listOf(toTomas)) }
    }

    @Test
    fun thePageSeesWhoAndWhoElse() {
        val j = sharesJson(listOf(toTomas), listOf(ada, tomas), emptyList(), "/Notes/Groceries.md")
        assertTrue(j.getBoolean("available"))
        val shares = j.getJSONArray("shares")
        assertEquals(1, shares.length())
        assertEquals("17", shares.getJSONObject(0).getString("id"))
        assertEquals("Tomas Reyes", shares.getJSONObject(0).getString("name"))
        assertTrue(shares.getJSONObject(0).getBoolean("canEdit"))
        // Someone it is already shared with is not offered again.
        val people = j.getJSONArray("people")
        assertEquals(1, people.length())
        assertEquals("ada", people.getJSONObject(0).getString("id"))
        assertFalse(j.getBoolean("sharedWithMe"))
        assertTrue(j.isNull("from"))
    }

    @Test
    fun aNoteSharedWithThisAccountSaysByWhom() {
        val j = sharesJson(emptyList(), listOf(ada), listOf(Incoming("3", "/Notes/Book club.md", "Ada Whitlock")), "/Notes/Book club.md")
        assertTrue(j.getBoolean("sharedWithMe"))
        assertEquals("Ada Whitlock", j.getString("from"))
        val other = sharesJson(emptyList(), listOf(ada), listOf(Incoming("3", "/Notes/Book club.md", "Ada Whitlock")), "/Notes/Groceries.md")
        assertFalse(other.getBoolean("sharedWithMe"))
    }

    @Test
    fun withoutNextcloudThereIsNoSharing() {
        assertFalse(noSharingJson().getBoolean("available"))
    }

    @Test
    fun theStateMarksSharedNotes() {
        val n = Note(path = "Groceries.md", title = "Groceries", preview = "", modified = 5L, text = "- [ ] oat milk\n")
        val j = noteJson(n, pinned = false, shared = true, sharedWithMe = false)
        assertTrue(j.getBoolean("shared"))
        assertFalse(j.getBoolean("sharedWithMe"))
        assertFalse(noteJson(n, pinned = false).getBoolean("shared"))
    }

    @Test
    fun everySharingRouteNeedsTheToken() {
        val routes = listOf(Route("GET", "/api/shares"), Route("POST", "/api/shares/add"), Route("POST", "/api/shares/edit"), Route("POST", "/api/shares/stop"))
        val good = "c".repeat(64)
        for (r in routes) {
            assertTrue(r.toString(), r in API_ROUTES)
            assertEquals(r.toString(), Gate.NO_TOKEN, gate(r.method, r.path, mapOf("x-notes" to "1")) { it == good })
            assertEquals(r.toString(), Gate.API, gate(r.method, r.path, mapOf("x-notes" to "1", "authorization" to "Bearer $good")) { it == good })
        }
        assertEquals(Gate.NOT_FROM_PAGE, gate("POST", "/api/shares/stop", mapOf("authorization" to "Bearer $good")) { it == good })
    }

    @Test
    fun nextcloudsOwnRefusalReachesThePageInItsWords() {
        val r = sharingFailure(com.wanderwildwood.oboegaki.sync.NextcloudSaid("Sharing Groceries.md failed, because this item is already shared with Tomas Reyes"))
        assertEquals(503, r.status)
        assertEquals("Nextcloud: Sharing Groceries.md failed, because this item is already shared with Tomas Reyes", r.message)
        // Cut short, and no control characters to break a line in the page.
        val long = sharingFailure(com.wanderwildwood.oboegaki.sync.NextcloudSaid("x\n".repeat(500)))
        assertTrue(long.message!!.length <= "Nextcloud: ".length + 200)
        assertFalse(long.message!!.contains('\n'))
    }

    @Test
    fun aNetworkFailureNeverTellsThePageTheServersAddress() {
        val r = sharingFailure(com.wanderwildwood.oboegaki.sync.Unreachable("Failed to connect to cloud.example.org/192.0.2.7:443"))
        assertEquals(503, r.status)
        assertEquals("Nextcloud did not answer", r.message)
        assertFalse(r.message!!.contains("example"))
        // Nor does any other failure's text: a parse error can quote what came back.
        val parse = sharingFailure(org.json.JSONException("Value <html><title>Login - cloud.example.org</title> of type java.lang.String"))
        assertEquals("Nextcloud did not answer", parse.message)
        val io = sharingFailure(java.io.IOException("unexpected end of stream on https://ada:secret@cloud.example.org/..."))
        assertEquals("Nextcloud did not answer", io.message)
    }

    @Test
    fun signedOutAndTheServersOwnRefusalsKeepTheirMeaning() {
        assertEquals("Nextcloud says this phone is signed out", sharingFailure(com.wanderwildwood.oboegaki.sync.Refused("signed out")).message)
        val mine = Refusal(404, "that person is not on this Nextcloud")
        assertEquals(mine, sharingFailure(mine))
    }

    @Test
    fun whatThePageIsToldAboutSharingHoldsNoAccount() {
        val j = sharesJson(listOf(toTomas), listOf(ada, tomas), listOf(Incoming("9", "/Notes/Groceries.md", "Ada Whitlock")), "/Notes/Groceries.md")
        assertEquals(setOf("available", "shares", "people", "sharedWithMe", "from"), j.keys().asSequence().toSet())
        assertEquals(setOf("id", "with", "name", "canEdit"), j.getJSONArray("shares").getJSONObject(0).keys().asSequence().toSet())
        assertEquals(setOf("id", "name"), j.getJSONArray("people").getJSONObject(0).keys().asSequence().toSet())
        assertEquals("Ada Whitlock", j.getString("from"))
    }
}
