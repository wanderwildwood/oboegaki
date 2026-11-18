package com.wanderwildwood.oboegaki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordShortcutTest {
    @Test fun onlyTheShortcutsAliasRecordsAtOnce() {
        assertTrue(recordsAtOnce("com.wanderwildwood.oboegaki.RecordShortcut"))
    }

    @Test fun recordSentToMainActivityWaitsForStart() {
        assertFalse(recordsAtOnce("com.wanderwildwood.oboegaki.MainActivity"))
        assertFalse(recordsAtOnce(null))
        assertFalse(recordsAtOnce(""))
    }
}
