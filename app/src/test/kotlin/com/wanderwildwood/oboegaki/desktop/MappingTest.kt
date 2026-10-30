package com.wanderwildwood.oboegaki.desktop

import com.wanderwildwood.oboegaki.notes.Note
import com.wanderwildwood.oboegaki.tasks.Due
import com.wanderwildwood.oboegaki.tasks.Fields
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class MappingTest {

    private fun note(path: String, text: String) =
        Note(path = path, title = path.substringAfterLast('/').removeSuffix(".md"), preview = "", modified = 5L, text = text)

    @Test
    fun aListNoteShowsItsItemsWithTheLineTheyAreOn() {
        val text = "---\nreminder: 2026-10-08 17:00\n---\nShopping\n- [ ] milk\n- [x] eggs ⏰ 2026-10-09 08:30\n"
        val j = noteJson(note("Home/shop.md", text), pinned = true)
        assertEquals("Home/shop.md", j.getString("id"))
        assertEquals("shop", j.getString("title"))
        assertEquals("Home", j.getString("folder"))
        assertTrue(j.getBoolean("pinned"))
        assertFalse(j.getBoolean("archived"))
        assertEquals("2026-10-08 17:00", j.getString("reminder"))
        assertEquals("Shopping\n- [ ] milk\n- [x] eggs ⏰ 2026-10-09 08:30\n", j.getString("body"))
        val items = j.getJSONArray("items")
        assertEquals(2, items.length())
        assertEquals(4, items.getJSONObject(0).getInt("line"))
        assertEquals("milk", items.getJSONObject(0).getString("text"))
        assertFalse(items.getJSONObject(0).getBoolean("done"))
        assertEquals(5, items.getJSONObject(1).getInt("line"))
        assertEquals("eggs", items.getJSONObject(1).getString("text"))
        assertTrue(items.getJSONObject(1).getBoolean("done"))
        assertEquals("2026-10-09 08:30", items.getJSONObject(1).getString("reminder"))
    }

    @Test
    fun theVersionTagIsOfTheWholeFileSoAReminderChangeMovesIt() {
        val a = noteJson(note("a.md", "text"), false).getString("v")
        val b = noteJson(note("a.md", "---\nreminder: 2026-10-08 17:00\n---\ntext"), false).getString("v")
        assertFalse(a == b)
    }

    @Test
    fun anArchivedNoteSaysWhereItCameFrom() {
        val j = noteJson(note("Archive/Home/old.md", "x"), false)
        assertTrue(j.getBoolean("archived"))
        assertEquals("Home", j.getString("folder"))
        assertEquals("", noteJson(note("Archive/top.md", "x"), false).getString("folder"))
    }

    @Test
    fun recordingsScansAndPicturesAreNamedNotServedInline() {
        val j = noteJson(note("n.md", "![[a.m4a]]\n![[scan.pdf]]\n![[kiln.jpg]]\n![[../x.png]]\n"), false)
        assertEquals("a.m4a", j.getJSONArray("recordings").getString(0))
        assertEquals("scan.pdf", j.getJSONArray("scans").getString(0))
        assertEquals(1, j.getJSONArray("pictures").length())
        assertEquals("kiln.jpg", j.getJSONArray("pictures").getString(0))
    }

    @Test
    fun aNoteWithoutARemindersaysNull() {
        assertTrue(noteJson(note("n.md", "hi"), false).isNull("reminder"))
    }

    @Test
    fun bodyAndFrontMatterSeparateAndJoinAgain() {
        val whole = "---\ntags: a\nreminder: 2026-10-08 17:00\n---\nwords\n"
        assertEquals("words\n", bodyOf(whole))
        assertEquals("---\ntags: a\nreminder: 2026-10-08 17:00\n---\nnew words", withBody(whole, "new words"))
        assertEquals("plain", bodyOf("plain"))
        assertEquals("changed", withBody("plain", "changed"))
    }

    @Test
    fun dueRoundTripsForADayAndForATime() {
        val zone = ZoneId.systemDefault()
        assertEquals(Due.Day(LocalDate.of(2026, 10, 8)), parseDueInput("2026-10-08"))
        assertEquals("2026-10-08", dueText(Due.Day(LocalDate.of(2026, 10, 8))))
        val at = parseDueInput("2026-10-08T17:30", zone) as Due.At
        assertEquals(LocalDateTime.of(2026, 10, 8, 17, 30), at.time)
        assertEquals("2026-10-08T17:30", dueText(at))
        assertNull(parseDueInput(""))
        assertNull(parseDueInput(null))
        assertNull(dueText(null))
    }

    @Test
    fun aBadDueIsRefused() {
        for (bad in listOf("tomorrow", "2026-13-01", "2026-10-08T25:00", "2026-10-08 17:30", "20261008")) {
            try {
                parseDueInput(bad)
                fail("accepted $bad")
            } catch (r: Refusal) {
                assertEquals(400, r.status)
            }
        }
    }

    @Test
    fun taskFieldsComeFromJsonWithTheLimitsApplied() {
        val f = fieldsFrom(JSONObject("""{"summary":"  Call Sam ","description":"about the kiln","due":"2026-10-08","priority":5}"""))
        assertEquals(Fields("Call Sam", "about the kiln", Due.Day(LocalDate.of(2026, 10, 8)), 5, false), f)
        val kept = fieldsFrom(JSONObject("""{"description":"new"}"""), f)
        assertEquals("Call Sam", kept.summary)
        assertEquals("new", kept.description)
        assertEquals(f.due, kept.due)
        val cleared = fieldsFrom(JSONObject("""{"due":null}"""), f)
        assertNull(cleared.due)
    }

    @Test
    fun taskFieldsAreValidated() {
        fun refused(json: String, status: Int) {
            try {
                fieldsFrom(JSONObject(json))
                fail("accepted $json")
            } catch (r: Refusal) {
                assertEquals(status, r.status)
            }
        }
        refused("""{"summary":""}""", 400)
        refused("""{"summary":"   "}""", 400)
        refused("""{"description":"x"}""", 400)
        refused("""{"summary":"a","priority":10}""", 400)
        refused("""{"summary":"a","priority":-1}""", 400)
        refused("""{"summary":"${"x".repeat(Limits.TASK_SUMMARY + 1)}"}""", 413)
        refused("""{"summary":"a","description":"${"x".repeat(Limits.TASK_DESCRIPTION + 1)}"}""", 413)
    }

    @Test
    fun aTitleOfNewlinesBecomesOneLine() {
        assertEquals("a b", fieldsFrom(JSONObject("""{"summary":"a\nb"}""")).summary)
    }
}
