package com.wanderwildwood.oboegaki.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutesTest {

    private val good = "a".repeat(64)
    private val valid: (String?) -> Boolean = { it == good }

    private fun headers(token: String? = null, fromPage: Boolean = false): Map<String, String> = buildMap {
        if (token != null) put("authorization", "Bearer $token")
        if (fromPage) put("x-notes", "1")
    }

    @Test
    fun everyRouteIsRefusedWithoutAToken() {
        assertTrue(API_ROUTES.isNotEmpty())
        for (r in API_ROUTES) {
            assertEquals(r.toString(), Gate.NO_TOKEN, gate(r.method, r.path, headers(fromPage = true), valid))
        }
    }

    @Test
    fun everyRouteIsRefusedWithATokenThePhoneNeverGaveOut() {
        for (r in API_ROUTES) {
            assertEquals(r.toString(), Gate.NO_TOKEN, gate(r.method, r.path, headers("b".repeat(64), fromPage = true), valid))
        }
    }

    @Test
    fun aTokenInTheAddressIsNotAToken() {
        for (r in API_ROUTES) {
            assertEquals(r.toString(), Gate.NO_TOKEN, gate(r.method, r.path + "?token=$good", headers(fromPage = true), valid))
        }
    }

    @Test
    fun everyRouteOpensWithTheTokenAndTheHeader() {
        for (r in API_ROUTES) {
            assertEquals(r.toString(), Gate.API, gate(r.method, r.path, headers(good, fromPage = true), valid))
        }
    }

    @Test
    fun aPostWithoutThePagesHeaderIsRefusedEvenWithTheToken() {
        for (r in API_ROUTES.filter { it.method == "POST" }) {
            assertEquals(r.toString(), Gate.NOT_FROM_PAGE, gate(r.method, r.path, headers(good), valid))
        }
    }

    @Test
    fun aPathThatIsNoRouteStillNeedsTheTokenFirst() {
        assertEquals(Gate.NO_TOKEN, gate("GET", "/api/whatever", headers(), valid))
        assertEquals(Gate.NO_TOKEN, gate("GET", "/etc/passwd", headers(), valid))
    }

    @Test
    fun onlyThePageItsTypefaceAndPairingAnswerWithoutAToken() {
        assertEquals(Gate.PAGE, gate("GET", "/", headers(), valid))
        assertEquals(Gate.PAGE, gate("GET", "/index.html", headers(), valid))
        for (path in PUBLIC_FILES.keys) assertEquals(Gate.PUBLIC_FILE, gate("GET", path, headers(), valid))
        assertEquals(Gate.PAIR, gate("POST", "/api/pair", headers(), valid))
        // Only a GET of the typeface; and nothing beside it in that folder.
        assertEquals(Gate.NO_TOKEN, gate("POST", PUBLIC_FILES.keys.first(), headers(), valid))
        assertEquals(Gate.NO_TOKEN, gate("GET", "/fonts/other.ttf", headers(), valid))
        assertEquals(Gate.NO_TOKEN, gate("GET", "/api/pair", headers(), valid))
    }

    @Test
    fun onlyGetAndPost() {
        for (m in listOf("PUT", "DELETE", "OPTIONS", "HEAD", "PATCH")) {
            assertEquals(m, Gate.BAD_METHOD, gate(m, "/api/state", headers(good, fromPage = true), valid))
        }
    }

    @Test
    fun movingANoteToAnotherFolderIsGuardedLikeEveryOtherChange() {
        val move = Route("POST", "/api/notes/move")
        assertTrue(move in API_ROUTES)
        // No token at all.
        assertEquals(Gate.NO_TOKEN, gate("POST", "/api/notes/move", headers(fromPage = true), valid))
        // The token only in the address.
        assertEquals(Gate.NO_TOKEN, gate("POST", "/api/notes/move?token=$good", headers(fromPage = true), valid))
        // The token, but not the page's own header.
        assertEquals(Gate.NOT_FROM_PAGE, gate("POST", "/api/notes/move", headers(good), valid))
        // Not by GET.
        assertEquals(Gate.NO_TOKEN, gate("GET", "/api/notes/move", headers(), valid))
        assertEquals(Gate.API, gate("POST", "/api/notes/move", headers(good, fromPage = true), valid))
    }

    @Test
    fun theTokenMustBeSentAsBearer() {
        assertEquals(Gate.NO_TOKEN, gate("GET", "/api/state", mapOf("authorization" to good), valid))
        assertEquals(Gate.NO_TOKEN, gate("GET", "/api/state", mapOf("authorization" to "Basic $good"), valid))
    }
}
