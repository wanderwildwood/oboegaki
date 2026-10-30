package com.wanderwildwood.oboegaki.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivatePeerTest {

    @Test
    fun homeAndOfficeAddressesAreLetIn() {
        for (ip in listOf("127.0.0.1", "10.1.2.3", "172.16.0.9", "172.31.255.1", "192.168.1.20", "169.254.3.4", "100.64.0.1", "100.127.1.1", "::1", "fe80::1", "fe80::abcd%wlan0", "fd12:3456::1", "fc00::5", "fd7a:115c:a1e0::1", "::ffff:192.168.1.5"))
            assertTrue(ip, isPrivatePeer(ip))
    }

    @Test
    fun theInternetAndMobileDataAreRefused() {
        for (ip in listOf("8.8.8.8", "172.32.0.1", "172.15.0.1", "100.128.0.1", "100.63.0.1", "192.169.1.1", "11.0.0.1", "2001:db8::1", "2607:f8b0:4005::1", "2a00:1450::1", "::ffff:8.8.8.8", "fec0::1", "fb00::1"))
            assertFalse(ip, isPrivatePeer(ip))
    }

    @Test
    fun anythingThatIsNotAnAddressIsRefused() {
        for (ip in listOf(null, "", "localhost", "example.com", "abc.def", "deadbeef", "10.0.0", "10.0.0.256", "1.2.3.4.5", "::zz"))
            assertFalse(ip.toString(), isPrivatePeer(ip))
    }
}
