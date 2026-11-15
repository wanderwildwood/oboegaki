package com.wanderwildwood.oboegaki.ui

import androidx.compose.runtime.mutableStateOf
import com.wanderwildwood.oboegaki.notes.Pulled
import com.wanderwildwood.oboegaki.notes.SyncState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric for android.util.Log, which the pull writes to; a bare Application, so the
// app's own start does not set up Notes with this test's files for the tests after it.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = android.app.Application::class)
class PullStateTest {

    private fun pullState(run: suspend () -> SyncState) =
        PullState(CoroutineScope(Dispatchers.Unconfined), mutableStateOf(run))

    @Test
    fun whatAPullFoundStaysUntilAPress() {
        val pull = pullState { SyncState.Unreachable }
        pull.pull()
        assertEquals(Pulled.Trouble(SyncState.Unreachable), pull.pulled)
        // Nothing takes it down on its own: it is still there, until the press.
        assertEquals(Pulled.Trouble(SyncState.Unreachable), pull.pulled)
        pull.dismiss()
        assertNull(pull.pulled)
    }

    @Test
    fun aPressWhileSyncingLeavesSyncingSaid() {
        val done = CompletableDeferred<SyncState>()
        val pull = pullState { done.await() }
        pull.pull()
        assertEquals(Pulled.Syncing, pull.pulled)
        pull.dismiss()
        assertEquals(Pulled.Syncing, pull.pulled)
        done.complete(SyncState.Idle)
        assertEquals(Pulled.UpToDate, pull.pulled)
    }
}
