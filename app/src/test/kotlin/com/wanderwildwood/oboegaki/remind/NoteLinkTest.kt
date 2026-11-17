package com.wanderwildwood.oboegaki.remind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteLinkTest {
    @Test fun aNoteInsideTheNotesIsTaken() {
        assertEquals("Garden/beans.md", NoteLink.safe("Garden/beans.md"))
        assertEquals("task:abc", NoteLink.safe("task:abc", task = true))
    }

    @Test fun anythingElseIsNot() {
        assertNull(NoteLink.safe(null))
        assertNull(NoteLink.safe(""))
        assertNull(NoteLink.safe("/data/data/other/file"))
        assertNull(NoteLink.safe("../shared_prefs/x.xml"))
        assertNull(NoteLink.safe("Garden/../../x"))
        assertNull(NoteLink.safe("task:abc"))
    }
}
