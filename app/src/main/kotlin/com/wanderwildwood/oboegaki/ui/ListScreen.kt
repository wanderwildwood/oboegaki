package com.wanderwildwood.oboegaki.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.FloatingActionButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.oboegaki.R
import com.wanderwildwood.oboegaki.notes.Note
import com.wanderwildwood.oboegaki.notes.Order
import com.wanderwildwood.oboegaki.notes.Showing
import com.wanderwildwood.oboegaki.notes.SyncState
import com.wanderwildwood.oboegaki.notes.arrange
import com.wanderwildwood.oboegaki.notes.folders

/**
 * Every note, the last one touched at the top, whatever folder it is in.
 *
 * One column of rows rather than Keep's grid of cards: at 4.3" two columns of cards hold half a
 * line of a note each, and a row holds the title and the start of what it says. The line above
 * the list says what it is showing and in what order, and is the way to change both; the
 * magnifier narrows it to what has been typed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    notes: List<Note>,
    shared: Set<String>,
    canShare: Boolean,
    sync: SyncState,
    showing: String,
    order: Order,
    onView: (showing: String, order: Order) -> Unit,
    onOpen: (Note) -> Unit,
    onNew: (folder: String) -> Unit,
    onRecord: () -> Unit,
    onScan: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
) {
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var choosing by remember { mutableStateOf(false) }
    val shown = arrange(notes, shared, showing, order, if (searching) query else "")
    val allFolders = folders(notes)

    if (searching) {
        BackHandler {
            searching = false
            query = ""
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.app_name)) },
                actions = {
                    BarButton(Icons.Search, stringResource(R.string.cd_search)) {
                        searching = !searching
                        if (!searching) query = ""
                    }
                    BarButton(Icons.Settings, stringResource(R.string.cd_settings), onSettings)
                    BarButton(Icons.Info, stringResource(R.string.cd_about), onAbout)
                },
            )
        },
        floatingActionButton = {
            // Paper, voice, and the pen: one tap from the list to each way of taking a note.
            Column(horizontalAlignment = Alignment.End) {
                FloatingActionButtonMMD(onClick = onScan) {
                    Icon(Icons.Scan, contentDescription = stringResource(R.string.cd_scan), modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.height(14.dp))
                FloatingActionButtonMMD(onClick = onRecord) {
                    Icon(Icons.Mic, contentDescription = stringResource(R.string.cd_record), modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.height(14.dp))
                FloatingActionButtonMMD(onClick = { onNew(Showing.folderOf(showing) ?: "") }) {
                    Icon(Icons.Add, contentDescription = stringResource(R.string.cd_new_note), modifier = Modifier.size(28.dp))
                }
            }
        },
    ) { contentPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            syncLine(sync)?.let { line ->
                TextMMD(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
                HorizontalDividerMMD()
            }

            if (searching) {
                val focus = remember { FocusRequester() }
                TextFieldMMD(
                    value = query,
                    onValueChange = { query = it.replace("\n", "") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus).textActions(),
                    placeholder = { TextMMD(text = stringResource(R.string.list_search_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
            }

            TextMMD(
                text = viewLabel(showing, order),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { choosing = true }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )

            if (shown.isEmpty()) {
                TextMMD(
                    text = stringResource(if (notes.isEmpty()) R.string.list_empty else R.string.list_none_here),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            } else {
                LazyColumnMMD(modifier = Modifier.fillMaxSize()) {
                    for (note in shown) {
                        item(key = note.path) {
                            NoteRow(note, note.path in shared, showFolder = Showing.folderOf(showing) == null) { onOpen(note) }
                        }
                    }
                    // Room under the last row for the buttons that float over it.
                    item(key = "foot") { Spacer(Modifier.height(232.dp)) }
                }
            }
        }
    }

    if (choosing) {
        ViewDialog(
            showing = showing,
            order = order,
            folders = allFolders,
            canShare = canShare,
            onChoose = { s, o ->
                choosing = false
                onView(s, o)
            },
            onDismiss = { choosing = false },
        )
    }
}

/** "All notes · newest first", or whatever the list has been narrowed to. */
@Composable
private fun viewLabel(showing: String, order: Order): String {
    val what = Showing.folderOf(showing)?.substringAfterLast('/') ?: stringResource(
        when (showing) {
            Showing.SHARED -> R.string.view_shared
            Showing.LISTS -> R.string.view_lists
            Showing.VOICE -> R.string.view_voice
            Showing.SCANS -> R.string.view_scans
            else -> R.string.view_all
        },
    )
    val how = stringResource(if (order == Order.TITLE) R.string.view_by_title else R.string.view_newest)
    return "$what · $how"
}

/**
 * What to show and in what order. Choosing anything applies it and closes, so the common case,
 * one change, is one tap; the current choices are in bold.
 */
@Composable
private fun ViewDialog(
    showing: String,
    order: Order,
    folders: List<String>,
    canShare: Boolean,
    onChoose: (String, Order) -> Unit,
    onDismiss: () -> Unit,
) {
    EInkDialog(onDismiss = onDismiss) {
        LazyColumnMMD(modifier = Modifier.fillMaxWidth().height(440.dp)) {
            item { DialogHeading(stringResource(R.string.view_show)) }
            item { Choice(stringResource(R.string.view_all), showing == Showing.ALL) { onChoose(Showing.ALL, order) } }
            item { Choice(stringResource(R.string.view_lists), showing == Showing.LISTS) { onChoose(Showing.LISTS, order) } }
            item { Choice(stringResource(R.string.view_voice), showing == Showing.VOICE) { onChoose(Showing.VOICE, order) } }
            item { Choice(stringResource(R.string.view_scans), showing == Showing.SCANS) { onChoose(Showing.SCANS, order) } }
            if (canShare) {
                item { Choice(stringResource(R.string.view_shared), showing == Showing.SHARED) { onChoose(Showing.SHARED, order) } }
            }
            for (folder in folders) {
                item(key = "folder-$folder") {
                    val depth = folder.count { it == '/' }
                    Choice(
                        label = "    ".repeat(depth) + folder.substringAfterLast('/'),
                        chosen = showing == Showing.folder(folder),
                    ) { onChoose(Showing.folder(folder), order) }
                }
            }
            item { DialogHeading(stringResource(R.string.view_order)) }
            item { Choice(stringResource(R.string.view_newest_choice), order == Order.CHANGED) { onChoose(showing, Order.CHANGED) } }
            item { Choice(stringResource(R.string.view_by_title_choice), order == Order.TITLE) { onChoose(showing, Order.TITLE) } }
        }
    }
}

@Composable
private fun DialogHeading(text: String) {
    TextMMD(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun Choice(label: String, chosen: Boolean, onClick: () -> Unit) {
    TextMMD(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = if (chosen) FontWeight.Bold else null,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
    )
}

@Composable
private fun syncLine(sync: SyncState): String? = when (sync) {
    SyncState.Idle, SyncState.Running -> null
    SyncState.Unreachable -> stringResource(R.string.sync_unreachable)
    SyncState.SignedOut -> stringResource(R.string.sync_signed_out)
    is SyncState.Failed -> stringResource(R.string.sync_failed, sync.why)
}

@Composable
private fun NoteRow(note: Note, isShared: Boolean, showFolder: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        TextMMD(
            text = note.title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val sharedWord = stringResource(R.string.list_shared)
        val line = listOfNotNull(
            note.folder.substringAfterLast('/').takeIf { showFolder && it.isNotEmpty() },
            sharedWord.takeIf { isShared },
            note.preview.takeIf { it.isNotEmpty() },
        ).joinToString(" · ")
        if (line.isNotEmpty()) {
            TextMMD(
                text = line,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
