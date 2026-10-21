package com.wanderwildwood.oboegaki.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.wanderwildwood.oboegaki.R
import com.wanderwildwood.oboegaki.notes.PULLED_LINE_MS
import com.wanderwildwood.oboegaki.notes.Pulled
import com.wanderwildwood.oboegaki.notes.SyncState
import com.wanderwildwood.oboegaki.notes.pulledAfter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A list's answer to a swipe down at its top: run [run], a sync that returns once it is done
 * with the state it left, and say so in [pulled] meanwhile. A second pull while one is still
 * running or still being reported is the same pull.
 */
class PullState internal constructor(
    private val scope: CoroutineScope,
    private val run: State<suspend () -> SyncState>,
) {
    var pulled by mutableStateOf<Pulled?>(null)
        private set
    private var job: Job? = null

    fun pull() {
        if (job?.isActive == true) return
        job = scope.launch {
            Log.i("oboegaki", "pulled at the top of a list: syncing")
            pulled = Pulled.Syncing
            pulled = pulledAfter(run.value())
            delay(PULLED_LINE_MS)
            pulled = null
        }
    }
}

@Composable
fun rememberPull(run: suspend () -> SyncState): PullState {
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(run)
    return remember { PullState(scope, latest) }
}

/** [pulled] in words; trouble in the same words the list's standing line uses for it. */
@Composable
fun pulledWords(pulled: Pulled?, trouble: @Composable (SyncState) -> String?): String? = when (pulled) {
    null -> null
    Pulled.Syncing -> stringResource(R.string.pull_syncing)
    Pulled.UpToDate -> stringResource(R.string.pull_up_to_date)
    is Pulled.Trouble -> trouble(pulled.state)
}
