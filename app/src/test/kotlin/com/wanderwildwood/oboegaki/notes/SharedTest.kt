package com.wanderwildwood.oboegaki.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class SharedTest {

    @Test
    fun anAddressAloneBecomesALink() {
        assertEquals(
            SharedText("How to Prune an Apple Tree", "[How to Prune an Apple Tree](https://example.org/pruning)\n"),
            sharedText("How to Prune an Apple Tree", "https://example.org/pruning"),
        )
    }

    @Test
    fun otherWordsFollowTheLink() {
        assertEquals(
            SharedText(
                "Winter Bread",
                "[Winter Bread](https://example.org/bread?id=4)\n\nA slow loaf for a cold kitchen.\n",
            ),
            sharedText("Winter Bread", "A slow loaf for a cold kitchen.\nhttps://example.org/bread?id=4"),
        )
    }

    @Test
    fun theNameIsNotSaidTwice() {
        assertEquals(
            SharedText("Moss Gardens", "[Moss Gardens](https://example.org/moss)\n"),
            sharedText("Moss Gardens", "Moss Gardens https://example.org/moss"),
        )
    }

    @Test
    fun punctuationAfterTheAddressIsNotPartOfIt() {
        assertEquals(
            "[Tide Tables](https://example.org/tides)\n\nSee ().\n",
            sharedText("Tide Tables", "See (https://example.org/tides).").text,
        )
    }

    @Test
    fun theTitleIsMadeSafeAndTheLinkEscaped() {
        val shared = sharedText("Q&A: [draft] notes / part 2", "https://example.org/a_(b)")
        assertEquals("Q&A- [draft] notes - part 2", shared.title)
        assertEquals("[Q&A: \\[draft\\] notes / part 2](https://example.org/a_%28b%29)\n", shared.text)
    }

    @Test
    fun withoutAnAddressOrASubjectItIsKeptAsItCame() {
        assertEquals(SharedText("Shopping", "milk and eggs"), sharedText("Shopping", "milk and eggs"))
        assertEquals(SharedText("", "https://example.org/x"), sharedText(null, "https://example.org/x"))
        assertEquals(SharedText("", "https://example.org/x"), sharedText("  ", "https://example.org/x"))
    }

    @Test
    fun aReminderLeavesOutEmbedsAndStaysShort() {
        assertEquals("Call the vet\n\nabout the hens", reminderText("![[Voice 2026-01-02 0900.m4a]]\n\nCall the vet\n\n\n\nabout the hens\n"))
        val long = reminderText("x".repeat(1500))
        assertEquals(1000, long.length)
        assertEquals('…', long.last())
        assertEquals("", reminderText("![[Picture 2026-01-02 0900.jpg]]\n"))
    }

    @Test
    fun picturesAreFound() {
        assertEquals("Picture 2026-01-02 0900.jpg", pictureOn("![[Picture 2026-01-02 0900.jpg]]"))
        assertEquals("a.WEBP", pictureOn("  ![[a.WEBP]] "))
        assertEquals(null, pictureOn("![[scan.pdf]]"))
        assertEquals(null, pictureOn("see ![[a.png]] here"))
    }
}
