package com.wanderwildwood.oboegaki.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairedBrowsersTest {

    private var clock = 1_000_000L
    private var saved = ""
    private fun make(initial: String = "") = PairedBrowsers(now = { clock }, initial = initial, save = { saved = it })
    private val day = 24L * 60 * 60 * 1000

    @Test fun issuedTokensAreLongRandomAndDifferent() {
        val b = make()
        val a = b.issue("Firefox on Linux")
        val c = b.issue("Chrome on Mac")
        assertEquals(64, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
        assertNotEquals(a, c)
        assertEquals(2, b.list.value.size)
    }

    @Test fun eachBrowserHasItsOwnToken() {
        val b = make()
        val a = b.issue("A")
        val c = b.issue("C")
        val idA = b.check(a)
        val idC = b.check(c)
        assertNotNull(idA); assertNotNull(idC)
        assertNotEquals(idA, idC)
    }

    @Test fun unknownEmptyAndOverlongTokensFail() {
        val b = make()
        val t = b.issue("A")
        assertNull(b.check(null))
        assertNull(b.check(""))
        assertNull(b.check(t.uppercase()))
        assertNull(b.check(t.dropLast(1)))
        assertNull(b.check("x".repeat(5000)))
    }

    @Test fun tokenIsNeverStoredInTheClear() {
        val b = make()
        val t = b.issue("A")
        assertFalse(saved.contains(t))
    }

    @Test fun unusedForThirtyDaysExpires() {
        val b = make()
        val t = b.issue("A")
        clock += 29 * day
        assertNotNull(b.check(t))   // use renews it
        clock += 29 * day
        assertNotNull(b.check(t))
        clock += 31 * day
        assertNull(b.check(t))
        assertTrue(b.list.value.isEmpty())
    }

    @Test fun expiryTellsTheListenerWhichBrowserWentAway() {
        val b = make()
        val t = b.issue("A")
        val id = b.check(t)!!
        var told: Set<String> = emptySet()
        b.onRemoved = { told = it }
        clock += 31 * day
        b.refresh()
        assertEquals(setOf(id), told)
    }

    @Test fun removeStopsOnlyThatBrowser() {
        val b = make()
        val a = b.issue("A")
        val c = b.issue("C")
        var told: Set<String> = emptySet()
        b.onRemoved = { told = it }
        val idA = b.check(a)!!
        assertTrue(b.remove(idA))
        assertEquals(setOf(idA), told)
        assertNull(b.check(a))
        assertNotNull(b.check(c))
        assertFalse(b.remove(idA))
    }

    @Test fun removeAllStopsEveryone() {
        val b = make()
        val tokens = (1..3).map { b.issue("B$it") }
        b.removeAll()
        tokens.forEach { assertNull(b.check(it)) }
        assertTrue(b.list.value.isEmpty())
    }

    @Test fun atMostEightAndTheLongestUnusedGoesFirst() {
        val b = make()
        val tokens = (1..8).map { clock += 1000; b.issue("B$it") }
        clock += 1000
        assertNotNull(b.check(tokens[0]))   // the first is now the freshest
        clock += 1000
        val ninth = b.issue("B9")
        assertEquals(8, b.list.value.size)
        assertNull(b.check(tokens[1]))      // second was the least recently used
        assertNotNull(b.check(tokens[0]))
        assertNotNull(b.check(ninth))
    }

    @Test fun savesAndReloads() {
        val b = make()
        val t = b.issue("Firefox on Linux")
        val again = make(saved)
        assertNotNull(again.check(t))
        assertEquals("Firefox on Linux", again.list.value.single().label)
    }

    @Test fun expiredEntriesAreDroppedWhenLoaded() {
        val b = make()
        val t = b.issue("A")
        clock += 40 * day
        val again = make(saved)
        assertNull(again.check(t))
    }

    @Test fun garbledStorageIsIgnored() {
        val b = make("nonsense\n1\t2\t3\n\t\t\t\t")
        assertTrue(b.list.value.isEmpty())
    }

    @Test fun labelsAreCleaned() {
        assertEquals("A browser", cleanLabel(null))
        assertEquals("A browser", cleanLabel("  \t\n"))
        assertEquals("ab", cleanLabel("a\tb\n"))
        assertEquals(40, cleanLabel("x".repeat(100)).length)
    }

    @Test fun labelsFromUserAgents() {
        assertEquals("Firefox on Linux", browserLabel("Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0"))
        assertEquals("Chrome on Windows", browserLabel("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"))
        assertEquals("Safari on Mac", browserLabel("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Version/17.0 Safari/605.1.15"))
        assertEquals("Chrome on Android", browserLabel("Mozilla/5.0 (Linux; Android 13; Pixel) Chrome/120 Mobile Safari/537.36"))
        assertEquals("A browser", browserLabel(null))
        assertEquals("A browser", browserLabel("curl/8"))
    }

    @Test fun bearerHeaderParsing() {
        assertEquals("abc", bearerToken("Bearer abc"))
        assertNull(bearerToken("Basic abc"))
        assertNull(bearerToken(null))
    }
}
