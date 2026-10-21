package com.wanderwildwood.oboegaki.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class PullTest {
    @Test
    fun aCleanSyncIsUpToDate() {
        assertEquals(Pulled.UpToDate, pulledAfter(SyncState.Idle))
    }

    @Test
    fun eachTroubleIsSaidAsItself() {
        assertEquals(Pulled.Trouble(SyncState.Unreachable), pulledAfter(SyncState.Unreachable))
        assertEquals(Pulled.Trouble(SyncState.SignedOut), pulledAfter(SyncState.SignedOut))
        assertEquals(Pulled.Trouble(SyncState.Failed("Groceries.md")), pulledAfter(SyncState.Failed("Groceries.md")))
    }
}
