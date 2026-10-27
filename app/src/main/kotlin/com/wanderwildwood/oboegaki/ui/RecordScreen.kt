package com.wanderwildwood.oboegaki.ui

import androidx.compose.foundation.layout.heightIn
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.switcher.SwitchMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.oboegaki.R
import com.wanderwildwood.oboegaki.hearing.Voice

/**
 * While recording: how long, a pause, whether it is to be written down, and the way to stop. Nothing moves but the seconds, once a
 * second, which the panel can afford; there is no level meter, because a bouncing bar on
 * E Ink is a smear that costs a refresh a frame.
 *
 * Back stops too. Leaving the app does not: the recording carries on, and the notification
 * has its own Stop.
 */
@Composable
fun RecordScreen(seconds: Int?, paused: Boolean, onStop: () -> Unit) {
    BackHandler(onBack = onStop)
    var writeDown by remember { mutableStateOf(Voice.writeDown) }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        TextMMD(
            text = stringResource(if (paused) R.string.record_paused else R.string.record_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(16.dp))
        // A figure the screen displays, like a speed, and so exempt from the type scale.
        TextMMD(text = clock(seconds ?: 0), fontSize = 64.sp)
        Spacer(Modifier.height(32.dp))
        // Written down or kept as sound alone: a song or a room has no words to find, and an
        // hour of it would keep the phone busy for an hour.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    writeDown = !writeDown
                    Voice.writeDown = writeDown
                }
                .heightIn(min = 48.dp)
                .padding(vertical = 10.dp),
        ) {
            TextMMD(text = stringResource(R.string.record_write_down), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            SwitchMMD(checked = writeDown, onCheckedChange = null)
        }
        Spacer(Modifier.height(20.dp))
        OutlinedButtonMMD(onClick = { Voice.pause(!paused) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(if (paused) Icons.Mic else Icons.Pause, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.size(10.dp))
            TextMMD(text = stringResource(if (paused) R.string.record_resume else R.string.record_pause))
        }
        Spacer(Modifier.height(12.dp))
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
