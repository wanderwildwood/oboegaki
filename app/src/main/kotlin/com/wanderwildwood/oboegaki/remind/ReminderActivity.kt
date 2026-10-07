package com.wanderwildwood.oboegaki.remind

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.ThemeMMD
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.oboegaki.R
import com.wanderwildwood.oboegaki.notes.Notes
import com.wanderwildwood.oboegaki.notes.preview
import com.wanderwildwood.oboegaki.notes.reminderTime
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.wanderwildwood.oboegaki.ui.monochrome

/**
 * The reminder as a whole screen, over the lock screen, when one rings with the screen off.
 * Large buttons and large words; nothing has to be unlocked to answer it. It lists every
 * reminder ringing at the time, so two due at 17:00 are one screen, not two.
 */
class ReminderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContent {
            ThemeMMD(colorScheme = monochrome) {
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                    Ringing(onDone = ::finish)
                }
            }
        }
    }
}

@Composable
private fun Ringing(onDone: () -> Unit) {
    val context = LocalContext.current
    val version by Reminders.version.collectAsState()
    val ringing = remember(version) {
        val ids = Notifier.ringing(context)
        Reminders.load(context).filter { it.id in ids }
    }
    LaunchedEffect(ringing.isEmpty()) { if (ringing.isEmpty()) onDone() }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp)) {
        TextMMD(text = stringResource(R.string.remind_screen_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        LazyColumnMMD(modifier = Modifier.weight(1f).fillMaxWidth()) {
            for (r in ringing) item(key = r.id) { RingingOne(r) }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButtonMMD(onClick = onDone, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            TextMMD(text = stringResource(R.string.remind_close), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun RingingOne(r: Record) {
    val context = LocalContext.current
    val note = r.path.substringAfterLast('/').substringBeforeLast('.')
    Column(Modifier.padding(vertical = 12.dp)) {
        TextMMD(text = Reminders.title(r), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (r.key.isNotEmpty()) TextMMD(text = note, style = MaterialTheme.typography.titleMedium)
        reminderTime(r.at)?.let {
            TextMMD(text = Times.whenShort(context, it), style = MaterialTheme.typography.bodyMedium)
        }
        // A note's reminder: the start of what it says, which is what it is a reminder of.
        if (r.key.isEmpty()) {
            val words by produceState("", r.path) {
                value = withContext(Dispatchers.IO) { runCatching { Notes.init(context); Notes.read(r.path)?.let(::preview) }.getOrNull().orEmpty() }
            }
            if (words.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                TextMMD(text = words, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            ButtonMMD(
                onClick = { Reminders.answer(context, r.id, done = true) },
                modifier = Modifier.weight(1f).height(56.dp),
            ) { TextMMD(text = stringResource(R.string.remind_done), style = MaterialTheme.typography.titleMedium) }
            Spacer(Modifier.width(12.dp))
            OutlinedButtonMMD(
                onClick = { Reminders.answer(context, r.id, done = false) },
                modifier = Modifier.weight(1f).height(56.dp),
            ) { TextMMD(text = stringResource(R.string.remind_snooze, Reminders.SNOOZE_MINUTES), style = MaterialTheme.typography.titleMedium) }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDividerMMD()
    }
}
