package com.wanderwildwood.oboegaki.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredSecretTest {
    private val seal = { s: String -> "enc:v1:" + s.reversed() }
    private val open = { s: String -> s.removePrefix("enc:v1:").reversed() }

    @Test fun `a plain password is read and handed back sealed`() {
        val r = readStoredSecret("app-pass", seal, open)
        assertEquals("app-pass", r.value)
        assertTrue(r.migrated!!.startsWith("enc:v1:"))
        assertFalse(r.migrated!!.contains("app-pass"))
    }

    @Test fun `a sealed password is opened and left alone`() {
        val r = readStoredSecret(seal("app-pass"), seal, open)
        assertEquals("app-pass", r.value)
        assertNull(r.migrated)
    }

    @Test fun `a sealed password whose key is gone reads as nothing`() {
        assertNull(readStoredSecret("enc:v1:xx", seal) { null }.value)
    }

    @Test fun `nothing stored is nothing`() {
        assertNull(readStoredSecret(null, seal, open).value)
        assertNull(readStoredSecret("", seal, open).value)
    }
}
