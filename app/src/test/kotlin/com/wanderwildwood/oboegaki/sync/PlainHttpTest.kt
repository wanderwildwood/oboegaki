package com.wanderwildwood.oboegaki.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlainHttpTest {

    @Test
    fun httpsIsAlwaysFine() {
        assertTrue(LoginFlow.plainHttpAllowed("https://cloud.example.com"))
    }

    @Test
    fun httpOnAHomeOrVpnNetworkIsFine() {
        for (a in listOf("http://192.168.1.5", "http://10.0.0.2:8080", "http://100.101.102.103", "http://nextcloud", "http://cloud.local", "http://box.lan", "http://pc.tail1234.ts.net", "http://localhost:8080"))
            assertTrue(a, LoginFlow.plainHttpAllowed(a))
    }

    @Test
    fun httpToTheInternetIsRefused() {
        for (a in listOf("http://cloud.example.com", "http://8.8.8.8", "http://203.0.113.9:8080", "http://notlocal.com", "http://evil.local.example.com", "http://"))
            assertFalse(a, LoginFlow.plainHttpAllowed(a))
    }
}

class LoginFlowPollTest {

    private fun answer(server: String): okhttp3.mockwebserver.MockWebServer =
        okhttp3.mockwebserver.MockWebServer().apply {
            enqueue(
                okhttp3.mockwebserver.MockResponse().setBody(
                    """{"server":"$server","loginName":"ada","appPassword":"app-password-for-tests"}""",
                ),
            )
            start()
        }

    @Test
    fun anHttpsServerIsKept() {
        val web = answer("https://cloud.example.org")
        try {
            val account = LoginFlow.poll(LoginFlow.Started("unused", web.url("/poll").toString(), "t"))
            org.junit.Assert.assertEquals("https://cloud.example.org", account?.server)
        } finally {
            web.shutdown()
        }
    }

    @Test(expected = PlainHttpRefused::class)
    fun aPlainHttpServerOnTheInternetIsRefused() {
        val web = answer("http://cloud.example.org")
        try {
            LoginFlow.poll(LoginFlow.Started("unused", web.url("/poll").toString(), "t"))
        } finally {
            web.shutdown()
        }
    }

    @Test
    fun thePasswordIsNotPrinted() {
        assertFalse(Account("https://cloud.example.org", "ada", "app-password-for-tests").toString().contains("app-password"))
        assertFalse(DavAccount("https://dav.example.org/", "ada", "app-password-for-tests").toString().contains("app-password"))
    }
}

/** A server saved on plain http by an older version: kept, but nothing is sent to it. */
class SavedPlainHttpServerTest {

    @Test(expected = PlainHttpRefused::class)
    fun aWebDavServerOnTheInternetIsNotAsked() {
        WebDavRemote("http://dav.example.org/", "ada", "app-password-for-tests", "Notes").list()
    }

    @Test(expected = PlainHttpRefused::class)
    fun aNextcloudOnTheInternetIsNotAsked() {
        nextcloudRemote(Account("http://cloud.example.org", "ada", "app-password-for-tests"), "Notes").list()
    }

    @Test(expected = PlainHttpRefused::class)
    fun itsTaskListsAreNotAskedEither() {
        com.wanderwildwood.oboegaki.tasks.CalDavServer(Account("http://cloud.example.org", "ada", "app-password-for-tests")).lists()
    }

    @Test
    fun aServerAtHomeIsStillAsked() {
        val web = okhttp3.mockwebserver.MockWebServer().apply {
            enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(207).setBody("""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"/>"""))
            start()
        }
        try {
            runCatching { WebDavRemote(web.url("/dav/").toString(), "ada", "app-password-for-tests", "").list() }
            org.junit.Assert.assertEquals(1, web.requestCount)
            org.junit.Assert.assertTrue(web.takeRequest().getHeader("Authorization").orEmpty().startsWith("Basic "))
        } finally {
            web.shutdown()
        }
    }
}
