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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.res.stringResource
import com.wanderwildwood.oboegaki.R
import com.wanderwildwood.oboegaki.notes.Pulled
import com.wanderwildwood.oboegaki.notes.SyncState
import com.wanderwildwood.oboegaki.notes.pulledAfter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A list's answer to a swipe down at its top: run [run], a sync that returns once it is done
 * with the state it left, and say so in [pulled] meanwhile. A second pull while one is still
 * running is the same pull. What it found stays said until the next press on the screen
 * ([dismissedByPress]) or leaving it: on this panel a line on a timer could go unread.
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
        }
    }

    /** Takes down what the last pull found, once it has finished; "Syncing…" stays while it runs. */
    fun dismiss() {
        if (job?.isActive != true) pulled = null
    }
}

/** Any press inside takes down what the last pull said, on its way to doing what it does. */
fun Modifier.dismissedByPress(pull: PullState): Modifier = pointerInput(pull) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        pull.dismiss()
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
