package com.wanderwildwood.oboegaki.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.FloatingActionButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.oboegaki.R
import com.wanderwildwood.oboegaki.notes.Note
import com.wanderwildwood.oboegaki.notes.SyncState

/**
 * The notes in one folder, newest first, with the folders inside it above them.
 *
 * One column of rows rather than Keep's grid of cards: at 4.3" two columns of cards hold half a
 * line of a note each, and a row holds the title and the start of what it says.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    folder: String,
    notes: List<Note>,
    shared: Set<String>,
    sync: SyncState,
    onOpen: (Note) -> Unit,
    onFolder: (String) -> Unit,
    onUp: () -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
) {
    val here = notes.filter { it.folder == folder }
    val folders = notes
        .map { it.folder }
        .filter { it != folder && (folder.isEmpty() || it.startsWith("$folder/")) }
        .map { inner ->
            val rest = if (folder.isEmpty()) inner else inner.removePrefix("$folder/")
            rest.substringBefore('/')
        }
        .distinct()
        .sortedBy { it.lowercase() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = {
                    TextMMD(
                        text = if (folder.isEmpty()) stringResource(R.string.app_name) else folder.substringAfterLast('/'),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (folder.isNotEmpty()) BarButton(Icons.Back, stringResource(R.string.cd_back), onUp)
                },
                actions = {
                    BarButton(Icons.Settings, stringResource(R.string.cd_settings), onSettings)
                    BarButton(Icons.Info, stringResource(R.string.cd_about), onAbout)
                },
            )
        },
        floatingActionButton = {
            FloatingActionButtonMMD(onClick = onNew) {
                Icon(Icons.Add, contentDescription = stringResource(R.string.cd_new_note), modifier = Modifier.size(28.dp))
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

            if (here.isEmpty() && folders.isEmpty()) {
                TextMMD(
                    text = stringResource(R.string.list_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(20.dp),
                )
            } else {
                LazyColumnMMD(modifier = Modifier.fillMaxSize()) {
                    for (name in folders) {
                        item(key = "folder:$name") {
                            FolderRow(name) { onFolder(if (folder.isEmpty()) name else "$folder/$name") }
                        }
                    }
                    for (note in here) {
                        item(key = note.path) { NoteRow(note, note.path in shared) { onOpen(note) } }
                    }
                    // Room under the last row for the button that floats over it.
                    item(key = "foot") { Spacer(Modifier.height(88.dp)) }
                }
            }
        }
    }
}

@Composable
private fun syncLine(sync: SyncState): String? = when (sync) {
    SyncState.Idle, SyncState.Running -> null
    SyncState.Unreachable -> stringResource(R.string.sync_unreachable)
    SyncState.SignedOut -> stringResource(R.string.sync_signed_out)
    is SyncState.Failed -> stringResource(R.string.sync_failed, sync.why)
}

@Composable
private fun FolderRow(name: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Folder, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        TextMMD(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NoteRow(note: Note, isShared: Boolean, onClick: () -> Unit) {
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
        val line = if (isShared) listOf(sharedWord, note.preview).filter { it.isNotEmpty() }.joinToString(" · ") else note.preview
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
