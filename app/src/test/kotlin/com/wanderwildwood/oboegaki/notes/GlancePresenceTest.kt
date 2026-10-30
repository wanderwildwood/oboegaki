package com.wanderwildwood.oboegaki.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class GlancePresenceTest {
    @Test
    fun glanceIsMissingDisabledOrReady() {
        assertEquals(SyncApps.Presence.MISSING, SyncApps.presence(installed = false, enabled = false))
        assertEquals(SyncApps.Presence.DISABLED, SyncApps.presence(installed = true, enabled = false))
        assertEquals(SyncApps.Presence.READY, SyncApps.presence(installed = true, enabled = true))
    }
}
