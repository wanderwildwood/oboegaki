package com.wanderwildwood.oboegaki.sync

import com.wanderwildwood.oboegaki.tasks.CalDavServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialDomainTest {

    @Test
    fun aTailnetIsItsOwnDomainNotAllOfTsNet() {
        assertEquals("tail1234.ts.net", credentialDomain("box.tail1234.ts.net"))
        assertTrue(sameCredentialDomain("box.tail1234.ts.net", "pc.tail1234.ts.net"))
        assertFalse(sameCredentialDomain("box.tail1234.ts.net", "box.tail9999.ts.net"))
    }

    @Test
    fun aDuckDnsNameIsNotItsNeighbors() {
        assertEquals("ada.duckdns.org", credentialDomain("ada.duckdns.org"))
        assertTrue(sameCredentialDomain("ada.duckdns.org", "cloud.ada.duckdns.org"))
        assertFalse(sameCredentialDomain("ada.duckdns.org", "tomas.duckdns.org"))
    }

    @Test
    fun aCompanyUnderCoUkIsNotEveryCompanyThere() {
        assertEquals("example.co.uk", credentialDomain("cloud.example.co.uk"))
        assertTrue(sameCredentialDomain("cloud.example.co.uk", "dav.example.co.uk"))
        assertFalse(sameCredentialDomain("cloud.example.co.uk", "cloud.other.co.uk"))
    }

    @Test
    fun iCloudsOwnHostsStillShareIt() {
        assertEquals("icloud.com", credentialDomain("p12-caldav.icloud.com"))
        assertTrue(sameCredentialDomain("caldav.icloud.com", "p12-caldav.icloud.com"))
        assertFalse(sameCredentialDomain("caldav.icloud.com", "caldav.example.com"))
    }

    @Test
    fun anAddressIsOnlyItselfAndCaseAndATrailingDotDoNotMatter() {
        assertTrue(sameCredentialDomain("192.168.1.5", "192.168.1.5"))
        assertFalse(sameCredentialDomain("192.168.1.5", "192.168.1.6"))
        assertTrue(sameCredentialDomain("Cloud.Example.com.", "dav.example.com"))
        assertTrue(sameCredentialDomain("nextcloud", "nextcloud"))
    }

    @Test
    fun aTaskLinkToAnotherDomainIsNotFollowedWithThePassword() {
        val web = okhttp3.mockwebserver.MockWebServer().apply { start() }
        try {
            val server = CalDavServer(Account(web.url("/").toString().trimEnd('/'), "ada", "app-password-for-tests"))
            val refused = runCatching { server.get("https://cloud.example.org/remote.php/dav/calendars/ada/t/x.ics") }.exceptionOrNull()
            assertTrue(refused is java.io.IOException)
            assertEquals(0, web.requestCount)
        } finally {
            web.shutdown()
        }
    }

    @Test
    fun aTaskLinkOnTheSameServerIsAsked() {
        val web = okhttp3.mockwebserver.MockWebServer().apply {
            enqueue(okhttp3.mockwebserver.MockResponse().setBody("BEGIN:VCALENDAR\nEND:VCALENDAR\n"))
            start()
        }
        try {
            val server = CalDavServer(Account(web.url("/").toString().trimEnd('/'), "ada", "app-password-for-tests"))
            server.get("/remote.php/dav/calendars/ada/t/x.ics")
            assertEquals(1, web.requestCount)
            assertTrue(web.takeRequest().getHeader("Authorization").orEmpty().startsWith("Basic "))
        } finally {
            web.shutdown()
        }
    }
}
