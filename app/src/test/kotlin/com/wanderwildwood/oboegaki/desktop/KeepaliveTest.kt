package com.wanderwildwood.oboegaki.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KeepaliveTest {
    @Test fun restrictedWhileRunningWarnsBeforeItHappens() =
        assertEquals(DesktopWarning.RESTRICTED, desktopWarning(running = true, failed = false, restricted = true))

    @Test fun aFailedStartSaysSo() =
        assertEquals(DesktopWarning.STOPPED, desktopWarning(running = false, failed = true, restricted = false))

    @Test fun offWithoutAFailureSaysNothing() {
        // Switched off by the reader, or on and on its way back after the system stopped it.
        assertEquals(DesktopWarning.NONE, desktopWarning(running = false, failed = false, restricted = true))
        assertEquals(DesktopWarning.NONE, desktopWarning(running = false, failed = false, restricted = false))
    }

    @Test fun runningAndAllowedSaysNothing() =
        assertEquals(DesktopWarning.NONE, desktopWarning(running = true, failed = false, restricted = false))

    @Test fun aRebootAndAnUpdateBothBringItBack() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val receiver = Regex("""<receiver\s+android:name="\.desktop\.DesktopRestartReceiver"[\s\S]*?</receiver>""").find(manifest)?.value
        assertTrue("no restart receiver for Desktop Sync", receiver != null)
        assertTrue(receiver!!.contains("android.intent.action.BOOT_COMPLETED"))
        assertTrue(receiver.contains("android.intent.action.MY_PACKAGE_REPLACED"))
        assertTrue("no Application to bring it back at process start", manifest.contains("android:name=\".MemoApplication\""))
    }

    @Test fun theServiceIsStickyAndOnlyTheReaderOrAFailedStartTurnsItOff() {
        val source = File("src/main/kotlin/com/wanderwildwood/oboegaki/desktop/DesktopService.kt").readText()
        assertTrue(source.contains("return START_STICKY"))
        assertFalse(source.contains("START_NOT_STICKY"))
        val off = "desktopWanted = false"
        // The reader's Stop, and a start that could not bind; nothing in onDestroy or a restart.
        assertEquals(2, Regex(Regex.escape(off)).findAll(source).count())
        val destroy = source.substring(source.indexOf("override fun onDestroy("))
        assertFalse(destroy.substring(0, destroy.indexOf("\n    }\n")).contains(off))
    }
}
