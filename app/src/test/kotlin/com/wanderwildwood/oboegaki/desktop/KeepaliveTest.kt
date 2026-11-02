package com.wanderwildwood.oboegaki.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class KeepaliveTest {
    @Test fun restrictedWhileRunningWarnsBeforeItHappens() =
        assertEquals(DesktopWarning.RESTRICTED, desktopWarning(running = true, wanted = true, restricted = true))

    @Test fun offButWantedMeansTheSystemStoppedIt() =
        assertEquals(DesktopWarning.STOPPED, desktopWarning(running = false, wanted = true, restricted = false))

    @Test fun switchedOffByTheReaderSaysNothing() =
        assertEquals(DesktopWarning.NONE, desktopWarning(running = false, wanted = false, restricted = true))

    @Test fun runningAndAllowedSaysNothing() =
        assertEquals(DesktopWarning.NONE, desktopWarning(running = true, wanted = true, restricted = false))
}
