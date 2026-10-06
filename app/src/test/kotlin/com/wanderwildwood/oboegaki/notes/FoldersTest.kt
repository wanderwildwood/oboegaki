package com.wanderwildwood.oboegaki.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FoldersTest {

    @Test
    fun aNameGoesInItsFolderOrAtTheTop() {
        assertEquals("Voice 2026-10-06 0800.md", inFolder("", "Voice 2026-10-06 0800.md"))
        assertEquals("Kompakt/Voice 2026-10-06 0800.m4a", inFolder("Kompakt", "Voice 2026-10-06 0800.m4a"))
    }

    @Test
    fun aTypedFolderIsTidied() {
        assertEquals("Kompakt", cleanFolder(" /Kompakt/ "))
        assertEquals("Kompakt/Field notes", cleanFolder("Kompakt//Field notes/"))
        assertEquals("", cleanFolder(""))
        assertEquals("", cleanFolder(" / "))
    }

    @Test
    fun whatCannotBeAFolderIsRefused() {
        assertNull(cleanFolder("../Elsewhere"))
        assertNull(cleanFolder(".obsidian"))
        assertNull(cleanFolder("Kompakt/.trash"))
        assertNull(cleanFolder("Archive"))
        assertNull(cleanFolder("archive/Kompakt"))
        assertNull(cleanFolder("What? Notes"))
        assertNull(cleanFolder("a:b"))
    }

    @Test
    fun subfoldersAreOnlyTheOnesDirectlyInside() {
        val all = listOf("Garden", "Garden/Beds", "Garden/Beds/North", "Garden/Seeds", "Kompakt")
        assertEquals(listOf("Garden", "Kompakt"), subfolders(all, ""))
        assertEquals(listOf("Garden/Beds", "Garden/Seeds"), subfolders(all, "Garden"))
        assertEquals(emptyList<String>(), subfolders(all, "Kompakt"))
    }

    @Test
    fun aFolderKnowsItsParent() {
        assertEquals("Garden", parentFolder("Garden/Beds"))
        assertEquals("", parentFolder("Garden"))
    }
}
