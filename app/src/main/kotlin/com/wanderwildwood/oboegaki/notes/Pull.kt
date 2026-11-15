package com.wanderwildwood.oboegaki.notes

/**
 * What the line above a list says once a swipe down at its top has asked the server again:
 * "Syncing…" while it runs, then "Up to date" or what went wrong, until the next press.
 */
sealed interface Pulled {
    data object Syncing : Pulled
    data object UpToDate : Pulled
    data class Trouble(val state: SyncState) : Pulled
}

/** What a finished sync, which left [state] behind, has to say to the one who pulled for it. */
fun pulledAfter(state: SyncState): Pulled = when (state) {
    SyncState.Idle, SyncState.Running -> Pulled.UpToDate
    else -> Pulled.Trouble(state)
}
