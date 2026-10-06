package com.wanderwildwood.oboegaki.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.oboegaki.R
import com.wanderwildwood.oboegaki.notes.Keeping
import com.wanderwildwood.oboegaki.notes.Notes

/**
 * How to have these notes in Obsidian on a computer: a few ways, two sentences each, in plain
 * text. Nothing here opens a link; the names are enough to search for.
 *
 * With the notes in a folder on the phone, the plainest way comes first: they are already files,
 * and a cable and a copy are all it takes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObsidianScreen(onBack: () -> Unit) {
    val preferences = Notes.preferences
    val inFolder = preferences.keeping == Keeping.FOLDER
    val place = folderPlace(preferences.folder)
    val where = if (place != null) stringResource(R.string.obsidian_copy_place, place) else stringResource(R.string.obsidian_copy_unknown)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.obsidian_title)) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onBack) },
            )
        },
    ) { contentPadding ->
        LazyColumnMMD(modifier = Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 20.dp)) {
            item {
                Column {
                    Spacer(Modifier.height(16.dp))
                    TextMMD(text = stringResource(R.string.obsidian_intro), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (inFolder) {
                item { Way(stringResource(R.string.obsidian_copy_heading), stringResource(R.string.obsidian_copy, where)) }
            }
            item { Way(stringResource(R.string.obsidian_webdav_heading), stringResource(R.string.obsidian_webdav)) }
            item { Way(stringResource(R.string.obsidian_davx5_heading), stringResource(R.string.obsidian_davx5)) }
            item { Way(stringResource(R.string.obsidian_syncthing_heading), stringResource(R.string.obsidian_syncthing)) }
            item { Way(stringResource(R.string.obsidian_nextcloud_heading), stringResource(R.string.obsidian_nextcloud)) }
            item { Way(stringResource(R.string.obsidian_sync_heading), stringResource(R.string.obsidian_sync)) }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun Way(heading: String, text: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(20.dp))
        TextMMD(text = heading, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        TextMMD(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Where a folder chosen through the system's picker is, as it shows on a computer: "Documents/
 * Notes" for content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FNotes.
 * Only the phone's own storage is named this way, because only its tree ids are paths; anything
 * else, a memory card or another app's storage, is null and called "the folder you chose".
 */
fun folderPlace(uri: Uri?): String? {
    if (uri == null || uri.authority != "com.android.externalstorage.documents") return null
    val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    if (!id.startsWith("primary:")) return null
    return id.removePrefix("primary:").trim('/').ifEmpty { null }
}
