package com.wanderwildwood.oboegaki.hearing

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

class SpeechTest {
    @Test
    fun englishComesFirstAndEachCodeOnce() {
        assertEquals("en", Speech.languages.first().code)
        assertEquals(Speech.languages.size, Speech.languages.map { it.code }.toSet().size)
    }

    @Test
    fun namesALanguageByItsCode() {
        assertEquals("Deutsch", Speech.named("de"))
        assertEquals("xx", Speech.named("xx"))
    }

    @Test
    fun hexMatchesSha256sum() {
        val digest = MessageDigest.getInstance("SHA-256").digest("abc".toByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Speech.hex(digest))
    }
}
