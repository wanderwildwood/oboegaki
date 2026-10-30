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
