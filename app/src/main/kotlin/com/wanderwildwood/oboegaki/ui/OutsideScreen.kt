package com.wanderwildwood.oboegaki.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.oboegaki.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A Markdown or text file opened from another app, Files (tana) most of all: shown to read,
 * with a way to keep it among the notes.
 *
 * It is not edited where it is. The app that opened it lends it to read only, and what Files
 * hands over is its own copy of a file on a server, so a change saved here would reach neither.
 * Keeping it makes a note of it, which syncs and can be written in like any other.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutsideScreen(uri: Uri, canKeep: Boolean, onKeep: (name: String, text: String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(uri) {
        val read = withContext(Dispatchers.IO) {
            runCatching {
                val shown = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
                    ?: uri.lastPathSegment?.substringAfterLast('/')
                    ?: ""
                shown to context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            }
        }
        read.onSuccess { (n, t) ->
            name = n
            text = t
        }.onFailure { failed = true }
    }

    BackHandler(onBack = onClose)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = name.substringBeforeLast('.').ifEmpty { name }, maxLines = 1) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onClose) },
            )
        },
    ) { contentPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp)) {
            val body = text
            when {
                failed -> TextMMD(text = stringResource(R.string.outside_unreadable), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                body == null -> Unit
                else -> {
                    LazyColumnMMD(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        item { Spacer(Modifier.height(8.dp)) }
                        body.split('\n').forEachIndexed { i, line ->
                            item(key = i) {
                                val heading = line.trimStart().startsWith("#")
                                TextMMD(
                                    text = if (heading) line.trimStart('#', ' ') else line,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (heading) FontWeight.Bold else null,
                                    modifier = Modifier.padding(vertical = 2.dp),
                                )
                            }
                        }
                    }
                    if (canKeep) {
                        ButtonMMD(
                            onClick = { onKeep(name, body) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).height(52.dp),
                        ) { TextMMD(text = stringResource(R.string.outside_keep)) }
                    }
                }
            }
        }
    }
}
