package com.wanderwildwood.oboegaki.notes

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Desktop Sync started on its own, after a reboot, an update or the system stopping the app,
 * once served an empty list: only Memo's screens read the notes from storage. [Notes.listNow]
 * reads them itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ListNowTest {

    @Test
    fun readsWhatIsInStorageWithNothingElseRunning() {
        val context: Context = RuntimeEnvironment.getApplication()
        // The phone's copy of a Nextcloud folder, as a sync left it: two notes, one in a folder.
        val mirror = File(context.filesDir, "nextcloud/notes")
        File(mirror, "Groceries.md").apply { parentFile!!.mkdirs(); writeText("# Groceries\n- [ ] oats\n") }
        File(mirror, "Garden/Seeds.md").apply { parentFile!!.mkdirs(); writeText("# Seeds\nbeans, kale\n") }

        Notes.init(context)
        Notes.preferences.keeping = Keeping.NEXTCLOUD
        // Nothing has asked for the notes yet: the list in memory is still empty, as it was.
        assertTrue(Notes.list.value.isEmpty())

        val paths = Notes.listNow().map { it.path }.sorted()
        assertEquals(listOf("Garden/Seeds.md", "Groceries.md"), paths)

        // A note written since, as a sync or the browser does, is there once the app reads again.
        File(mirror, "Later.md").writeText("# Later\n")
        Notes.reloadNow()
        assertEquals(3, Notes.listNow().size)
    }

    @Test
    fun readsAgainOnlyWhenItMust() {
        assertTrue(needsReading(loaded = false, seen = null, stamp = null))
        assertTrue(needsReading(loaded = false, seen = "a", stamp = "a"))
        // A folder another app writes to: read again when its stamp has moved.
        assertTrue(needsReading(loaded = true, seen = "a", stamp = "b"))
        assertFalse(needsReading(loaded = true, seen = "a", stamp = "a"))
        // Only the app writes there, and its own writes read the list again.
        assertFalse(needsReading(loaded = true, seen = null, stamp = null))
    }
}
