package com.wanderwildwood.oboegaki.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.ProcessTextKey
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * The apps that act on selected text — Dictionary's Define, a translator — in the menu over a
 * selection in a note, with what they hand back written into the note.
 *
 * Compose already lists these apps, but it starts them with `startActivity`, which tells the
 * app the text is read-only and has no way to take an answer back. So a thesaurus could look
 * a word up from here and never put another in its place. This replaces Compose's entries
 * with the same apps under the same labels, started for a result.
 *
 * The answer goes over the range that was selected, and only if that range still holds what
 * was sent: the note is a live field, and a sync or a stray key while the other app was open
 * could have moved the text under it. Then nothing is written, rather than the wrong thing.
 *
 * Typewriter's version, which works on a TextFieldState; this one is for a field holding a
 * TextFieldValue, so the answer arrives as a new value through [onValue].
 */
@Composable
fun Modifier.textActions(value: TextFieldValue, onValue: (TextFieldValue) -> Unit): Modifier {
    val context = LocalContext.current
    val now by rememberUpdatedState(value)
    val update by rememberUpdatedState(onValue)
    val actions = remember {
        val query = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        // The flags-object overload is Android 13; the Kompakt is 12.
        @Suppress("DEPRECATION")
        context.packageManager.queryIntentActivities(query, 0)
            .filter { it.activityInfo.exported && it.activityInfo.packageName !in NOT_SHOWN }
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) to it.loadLabel(context.packageManager).toString() }
    }
    val sent = remember { arrayOfNulls<Pair<TextRange, String>>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val (range, text) = sent[0] ?: return@rememberLauncherForActivityResult
        sent[0] = null
        val answer = result.data?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        if (result.resultCode != Activity.RESULT_OK || answer == null) return@rememberLauncherForActivityResult
        val current = now.text
        if (range.max > current.length || current.substring(range.min, range.max) != text) return@rememberLauncherForActivityResult
        update(
            TextFieldValue(
                text = current.take(range.min) + answer + current.drop(range.max),
                selection = TextRange(range.min + answer.length),
            ),
        )
    }
    return this
        .filterTextContextMenuComponents { it.key !is ProcessTextKey }
        .appendTextContextMenuComponents {
            val range = now.selection
            if (range.collapsed) return@appendTextContextMenuComponents
            actions.forEachIndexed { i, (component, label) ->
                item(key = Action(i), label = label) {
                    val text = now.text.substring(range.min, range.max)
                    sent[0] = range to text
                    runCatching {
                        launcher.launch(
                            Intent(Intent.ACTION_PROCESS_TEXT)
                                .setType("text/plain")
                                .setComponent(component)
                                .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false),
                        )
                    }.onFailure { sent[0] = null }
                    close()
                }
            }
        }
}

/**
 * Apps left out of the menu. EinkBro offers an online dictionary under its own name, which
 * duplicates Define beside it; he asked for it gone, and EinkBro has no setting to withdraw it.
 */
private val NOT_SHOWN = setOf("info.plateaukao.einkbro")

/** A menu entry of this file's own, so the filter above does not take it out again. */
private data class Action(val index: Int)
