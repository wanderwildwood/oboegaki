package com.wanderwildwood.oboegaki.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A recording's words go under its link, and only a note holding that link counts as having them. */
class WithUnderTest {

    private val link = "![[2026-11-18 0930.m4a]]"

    @Test
    fun theWordsGoUnderTheLink() {
        val note = "# Kiln\n$link\nlater"
        assertEquals("# Kiln\n$link\n\nCall back Tomas\nlater", withUnder(note, link, "Call back Tomas"))
    }

    @Test
    fun wordsAlreadyThereAreNotAddedAgain() {
        val note = "$link\n\nOkay."
        assertEquals(note, withUnder(note, link, "Okay."))
    }

    @Test
    fun aShortPhraseInANoteWithoutTheLinkIsNotTheWords() {
        assertNull(withUnder("Okay.\nCall back", link, "Okay."))
    }
}
