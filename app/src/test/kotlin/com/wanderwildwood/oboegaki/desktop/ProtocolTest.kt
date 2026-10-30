package com.wanderwildwood.oboegaki.desktop

import com.wanderwildwood.oboegaki.sync.merge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress

class PairingTest {

    private var now = 1_000L
    private val pairing = Pairing({ now })

    @Test
    fun aCodeIsSixDigits() {
        val code = pairing.issue()
        assertTrue(code, Regex("\\d{6}").matches(code))
    }

    @Test
    fun theRightCodeWorksOnceAndOnlyOnce() {
        val code = pairing.issue()
        assertTrue(pairing.redeem(code))
        assertFalse(pairing.redeem(code))
        assertNull(pairing.current())
    }

    @Test
    fun aCodeTypedWithASpaceStillWorks() {
        val code = pairing.issue()
        assertTrue(pairing.redeem(code.take(3) + " " + code.drop(3)))
    }

    @Test
    fun aCodeExpiresAfterThreeMinutes() {
        val code = pairing.issue()
        now += Pairing.LIFETIME_MS - 1
        assertEquals(code, pairing.current())
        now += 1
        assertNull(pairing.current())
        assertFalse(pairing.redeem(code))
    }

    @Test
    fun nothingWorksBeforeOneIsAskedFor() {
        assertFalse(pairing.redeem("000000"))
        assertFalse(pairing.redeem(null))
        assertFalse(pairing.redeem(""))
    }

    @Test
    fun fiveWrongTriesBurnTheCodeEvenForTheRightOne() {
        val code = pairing.issue()
        val wrong = if (code == "000000") "111111" else "000000"
        repeat(Pairing.MAX_ATTEMPTS) { assertFalse(pairing.redeem(wrong)) }
        assertFalse(pairing.redeem(code))
    }

    @Test
    fun fourWrongTriesLeaveItStillGood() {
        val code = pairing.issue()
        val wrong = if (code == "000000") "111111" else "000000"
        repeat(Pairing.MAX_ATTEMPTS - 1) { assertFalse(pairing.redeem(wrong)) }
        assertTrue(pairing.redeem(code))
    }

    @Test
    fun askingAgainReplacesTheOldCode() {
        val first = pairing.issue()
        var second = pairing.issue()
        while (second == first) second = pairing.issue()
        assertFalse(pairing.redeem(first))
    }

    @Test
    fun aClockThatMovesBackwardsPastTheLifetimeKillsTheCode() {
        pairing.issue()
        now -= Pairing.LIFETIME_MS + 5
        assertNull(pairing.current())
    }
}

class TokenTest {

    private val token = "7K3M9PQRSTVWXYZ0123ABCDE"

    @Test
    fun aTokenIsTwentyFourCharactersOfTheAlphabet() {
        val t = newToken()
        assertEquals(TOKEN_LENGTH, t.length)
        assertTrue(t, t.all { it in "0123456789ABCDEFGHJKMNPQRSTVWXYZ" })
        assertNotEquals(t, newToken())
    }

    @Test
    fun theRightTokenMatchesInAnyCase() {
        assertTrue(tokenMatches(token, token))
        assertTrue(tokenMatches(token.lowercase(), token))
    }

    @Test
    fun aWrongOrMissingOrEmptyTokenDoesNot() {
        assertFalse(tokenMatches(null, token))
        assertFalse(tokenMatches("", token))
        assertFalse(tokenMatches(token.dropLast(1), token))
        assertFalse(tokenMatches(token.dropLast(1) + "0", token))
        assertFalse(tokenMatches("", ""))
    }

    @Test
    fun theBearerHeaderMustBeExact() {
        assertTrue(bearerMatches("Bearer $token", token))
        assertFalse(bearerMatches("Bearer ${token.lowercase()}", token))
        assertFalse(bearerMatches("bearer $token", token))
        assertFalse(bearerMatches(token, token))
        assertFalse(bearerMatches(null, token))
        assertFalse(bearerMatches("Bearer ", token))
        assertFalse(bearerMatches("Bearer anything", ""))
    }

    @Test
    fun constantTimeEqualsAgreesWithEquals() {
        assertTrue(constantTimeEquals("abc", "abc"))
        assertFalse(constantTimeEquals("abc", "abd"))
        assertFalse(constantTimeEquals("abc", "abcd"))
    }
}

class VpnRangeTest {

    private fun net(text: String, prefix: Int) = Subnet(InetAddress.getByName(text), prefix)

    @Test
    fun thisPhoneItselfIsAlwaysLetIn() {
        assertTrue(isAllowedPeer("127.0.0.1", emptyList()))
        assertTrue(isAllowedPeer("::1", emptyList()))
    }

    @Test
    fun tailscaleRangeIsLetIn() {
        assertTrue(isAllowedPeer("100.64.0.1", emptyList()))
        assertTrue(isAllowedPeer("100.100.5.5", emptyList()))
        assertTrue(isAllowedPeer("100.127.255.255", emptyList()))
        assertTrue(isAllowedPeer("fd7a:115c:a1e0::1", emptyList()))
    }

    @Test
    fun justOutsideTailscaleIsNot() {
        assertFalse(isAllowedPeer("100.63.255.255", emptyList()))
        assertFalse(isAllowedPeer("100.128.0.1", emptyList()))
        assertFalse(isAllowedPeer("101.100.0.1", emptyList()))
    }

    @Test
    fun anOrdinaryLanAddressIsNotLetIn() {
        assertFalse(isAllowedPeer("192.168.1.20", emptyList()))
        assertFalse(isAllowedPeer("10.0.0.7", emptyList()))
        assertFalse(isAllowedPeer("8.8.8.8", emptyList()))
    }

    @Test
    fun anotherVpnsNetworkIsLetInWhileItIsUp() {
        val nets = listOf(net("10.8.0.2", 24))
        assertTrue(isAllowedPeer("10.8.0.5", nets))
        assertFalse(isAllowedPeer("10.8.1.5", nets))
        assertFalse(isAllowedPeer("10.8.0.5", emptyList()))
    }

    @Test
    fun aPrefixThatIsNotAWholeNumberOfBytesIsRespected() {
        val nets = listOf(net("172.20.0.1", 20))
        assertTrue(isAllowedPeer("172.20.15.9", nets))
        assertFalse(isAllowedPeer("172.20.16.9", nets))
    }

    @Test
    fun garbageIsNeverLetIn() {
        val nets = listOf(net("10.8.0.2", 24))
        assertFalse(isAllowedPeer(null, nets))
        assertFalse(isAllowedPeer("", nets))
        assertFalse(isAllowedPeer("not an address", nets))
        assertFalse(isAllowedPeer("10.8.0.5.5", nets))
        assertFalse(isAllowedPeer("evil.example.com", nets))
    }

    @Test
    fun aZoneOnAnIpv6AddressIsIgnored() {
        assertTrue(isAllowedPeer("::1%lo", emptyList()))
    }

    @Test
    fun aVpnThatOverlapsTheWifiIsNotTrusted() {
        val wifi = listOf(net("192.168.1.30", 24))
        val vpn = listOf(net("192.168.1.99", 24), net("10.8.0.2", 24))
        val trusted = trustedVpnNets(vpn, wifi)
        assertEquals(listOf("10.8.0.2/24"), trusted.map { it.toString() })
    }

    @Test
    fun aHugeVpnPrefixIsNotTrusted() {
        assertTrue(trustedVpnNets(listOf(net("10.0.0.1", 1)), emptyList()).isEmpty())
    }

    @Test
    fun tailscaleAlreadyHasItsOwnRuleSoItIsNotListedAgain() {
        assertTrue(trustedVpnNets(listOf(net("100.101.2.3", 32)), emptyList()).isEmpty())
    }
}

class ConflictTest {

    private fun resolve(started: Boolean, base: String?, mine: String, current: String) =
        resolveEdit(started, base, mine, current, ::merge)

    @Test
    fun anEditFromTheVersionThePhoneStillHasIsJustWritten() {
        val r = resolve(true, "a\nb", "a\nB", "a\nb")
        assertEquals(Resolved("a\nB", Outcome.CLEAN), r)
    }

    @Test
    fun ifOnlyTheFrontMatterMovedTheBodiesStillAgree() {
        assertEquals(Outcome.CLEAN, resolve(false, "a\nb", "a\nB", "a\nb").outcome)
    }

    @Test
    fun editsToDifferentLinesAreJoined() {
        val r = resolve(false, "one\ntwo\nthree", "ONE\ntwo\nthree", "one\ntwo\nTHREE")
        assertEquals(Outcome.MERGED, r.outcome)
        assertEquals("ONE\ntwo\nTHREE", r.text)
    }

    @Test
    fun editsToTheSameLineKeepTheBrowsersAndAppendThePhones() {
        val r = resolve(false, "milk", "oat milk", "soy milk")
        assertEquals(Outcome.KEPT_BOTH, r.outcome)
        assertTrue(r.text, r.text.startsWith("oat milk\n\n## Edited elsewhere\n\n"))
        assertTrue(r.text, r.text.trimEnd().endsWith("soy milk"))
    }

    @Test
    fun noWordOfEitherSideIsLostWhenTheyCannotBeJoined() {
        val r = resolve(false, null, "my version of it all", "their version of it all")
        assertEquals(Outcome.KEPT_BOTH, r.outcome)
        assertTrue("my version of it all" in r.text)
        assertTrue("their version of it all" in r.text)
        assertEquals(1, Regex("## Edited elsewhere").findAll(r.text).count())
    }

    @Test
    fun anEmptyPhoneTextLeavesNothingToKeep() {
        val r = resolve(false, null, "mine", "")
        assertEquals("mine", r.text)
    }

    @Test
    fun theBrowsersTextEqualToThePhonesIsNotAConflict() {
        assertEquals(Outcome.CLEAN, resolve(false, "old", "same", "same").outcome)
    }

    @Test
    fun theVersionTagFollowsTheText() {
        assertEquals(versionOf("a"), versionOf("a"))
        assertNotEquals(versionOf("a"), versionOf("b"))
        assertEquals(12, versionOf("anything").length)
    }
}

class LimitsTest {

    private fun refused(status: Int, block: () -> Unit) {
        try {
            block()
            fail("should have been refused")
        } catch (r: Refusal) {
            assertEquals(status, r.status)
        }
    }

    @Test
    fun textAtTheLimitPassesAndOneOverIsTooLarge() {
        assertEquals("x".repeat(Limits.TITLE), requireText("x".repeat(Limits.TITLE), "t", Limits.TITLE))
        refused(413) { requireText("x".repeat(Limits.TITLE + 1), "t", Limits.TITLE) }
    }

    @Test
    fun missingTextAndNullCharactersAreBadRequests() {
        refused(400) { requireText(null, "t", 10) }
        refused(400) { requireText("a\u0000b", "t", 10) }
        refused(400) { requireText("  ", "t", 10, blankOk = false) }
    }

    @Test
    fun aTitleWithAControlCharacterIsRefused() {
        refused(400) { requireTitle("one\ntwo") }
        assertEquals("kiln", requireTitle("  kiln "))
    }

    @Test
    fun remindersMustBeRealTimesInTheAppsForm() {
        assertEquals("2026-10-08 17:00", requireReminder("2026-10-08 17:00"))
        assertNull(requireReminder(""))
        assertNull(requireReminder(null))
        refused(400) { requireReminder("2026-10-08T17:00") }
        refused(400) { requireReminder("2026-13-08 17:00") }
        refused(400) { requireReminder("2026-02-30 17:00") }
        refused(400) { requireReminder("2026-10-08 25:00") }
        refused(400) { requireReminder("tomorrow") }
    }

    @Test
    fun onlyPlainPictureNamesAreServed() {
        assertTrue(isSafePictureName("kiln 2.jpg"))
        assertTrue(isSafePictureName("a.WEBP"))
        assertFalse(isSafePictureName("../secret.jpg"))
        assertFalse(isSafePictureName("sub/a.jpg"))
        assertFalse(isSafePictureName("a\\b.png"))
        assertFalse(isSafePictureName(".hidden.png"))
        assertFalse(isSafePictureName("notes.md"))
        assertFalse(isSafePictureName("voice.m4a"))
        assertFalse(isSafePictureName(""))
        assertFalse(isSafePictureName("a\u0000.png"))
        assertFalse(isSafePictureName("x".repeat(300) + ".png"))
    }

    @Test
    fun theBodyLimitsAreWhatTheDesignSays() {
        assertEquals(512 * 1024, Limits.BODY_BYTES)
        assertEquals(512, Limits.PAIR_BODY_BYTES)
        assertEquals(200_000, Limits.NOTE_TEXT)
    }
}
