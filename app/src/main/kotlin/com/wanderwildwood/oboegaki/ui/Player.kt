package com.wanderwildwood.oboegaki.ui

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.oboegaki.R
import kotlinx.coroutines.delay

/**
 * A recording in a note: play or pause, and where it has got to. The time steps once a
 * second while it plays and stands still otherwise.
 */
@Composable
fun Player(uri: Uri?, hearing: Boolean) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var at by remember { mutableStateOf(0) }
    val length = remember(uri) {
        uri?.let {
            runCatching {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(context, it)
                    (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L) / 1000
                }
            }.getOrNull()?.toInt()
        } ?: 0
    }

    DisposableEffect(uri) {
        onDispose {
            player?.release()
            player = null
        }
    }
    LaunchedEffect(playing) {
        while (playing) {
            at = (player?.currentPosition ?: 0) / 1000
            delay(1000)
        }
    }

    fun toggle() {
        if (uri == null) return
        val p = player ?: runCatching {
            MediaPlayer().apply {
                setDataSource(context, uri)
                setOnCompletionListener {
                    playing = false
                    at = 0
                    it.seekTo(0)
                }
                prepare()
            }
        }.getOrNull()?.also { player = it } ?: return
        if (p.isPlaying) {
            p.pause()
            playing = false
        } else {
            p.start()
            playing = true
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = uri != null) { toggle() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(44.dp).border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (playing) Icons.Pause else Icons.Play,
                contentDescription = stringResource(if (playing) R.string.cd_pause else R.string.cd_play),
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        val label = when {
            uri == null -> stringResource(R.string.player_missing)
            playing || at > 0 -> "${clock(at)} / ${clock(length)}"
            else -> stringResource(R.string.player_recording, clock(length))
        }
        Column {
            TextMMD(text = label, style = MaterialTheme.typography.bodyLarge)
            if (hearing) TextMMD(text = stringResource(R.string.player_hearing), style = MaterialTheme.typography.labelSmall)
        }
    }
}
