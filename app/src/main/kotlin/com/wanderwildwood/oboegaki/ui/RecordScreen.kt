package com.wanderwildwood.oboegaki.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.oboegaki.R

/**
 * While recording: how long, and the way to stop. Nothing moves but the seconds, once a
 * second, which the panel can afford; there is no level meter, because a bouncing bar on
 * E Ink is a smear that costs a refresh a frame.
 *
 * Back stops too. Leaving the app does not: the recording carries on, and the notification
 * has its own Stop.
 */
@Composable
fun RecordScreen(seconds: Int?, onStop: () -> Unit) {
    BackHandler(onBack = onStop)
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        TextMMD(text = stringResource(R.string.record_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        // A figure the screen displays, like a speed, and so exempt from the type scale.
        TextMMD(text = clock(seconds ?: 0), fontSize = 64.sp)
        Spacer(Modifier.height(40.dp))
        ButtonMMD(onClick = onStop, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Icon(Icons.Stop, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.size(10.dp))
            TextMMD(text = stringResource(R.string.record_stop))
        }
        Spacer(Modifier.height(16.dp))
        TextMMD(text = stringResource(R.string.record_note), style = MaterialTheme.typography.labelSmall)
    }
}

/** "1:05", or "1:02:05" past the hour. */
fun clock(seconds: Int): String {
    val h = seconds / 3600
    val m = seconds / 60 % 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
