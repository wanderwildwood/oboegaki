package com.wanderwildwood.oboegaki.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.switcher.SwitchMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.oboegaki.R
import com.wanderwildwood.oboegaki.notes.Notes
import com.wanderwildwood.oboegaki.sync.Person
import com.wanderwildwood.oboegaki.sync.Share
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Who a note is shared with, and with whom it could be.
 *
 * Sharing is Nextcloud's own: the other person's phone syncs the same file, so a shopping list
 * ticked in one place is ticked in the other. A new share can edit, because a list two people
 * shop from is the reason this exists; the switch beside a name takes that back.
 *
 * The note is synced up first, since the server can only share what it has.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(path: String, title: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var shares by remember { mutableStateOf<List<Share>?>(null) }
    var people by remember { mutableStateOf<List<Person>>(emptyList()) }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }

    suspend fun load() {
        val sharing = Notes.sharing() ?: return
        val result = withContext(Dispatchers.IO) {
            runCatching {
                Notes.syncAndWait()
                val server = Notes.serverPath(path)
                sharing.sharesOf(server) to sharing.people()
            }
        }
        result.onSuccess { (s, p) ->
            shares = s
            people = p
            problem = null
        }.onFailure { problem = it.message }
        busy = false
    }

    fun act(block: () -> Unit) {
        busy = true
        scope.launch {
            val failed = withContext(Dispatchers.IO) { runCatching(block).exceptionOrNull() }
            if (failed != null) problem = failed.message
            load()
            Notes.syncNow()
        }
    }

    LaunchedEffect(path) { load() }
    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.share_title)) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onBack) },
            )
        },
    ) { contentPadding ->
        val sharing = Notes.sharing()
        val current = shares.orEmpty()
        val others = people.filter { person -> current.none { it.with == person.id } }
        val note = when {
            problem != null -> stringResource(R.string.share_problem, problem!!)
            busy && shares == null -> stringResource(R.string.share_loading)
            else -> null
        }

        LazyColumnMMD(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            item(key = "title") {
                TextMMD(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
            if (note != null) {
                item(key = "note") {
                    TextMMD(
                        text = note,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }

            if (current.isNotEmpty()) {
                item(key = "with-head") { Heading(stringResource(R.string.share_shared_with)) }
                for (share in current) {
                    item(key = "share-${share.id}") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !busy) { act { sharing!!.setCanEdit(share.id, !share.canEdit) } }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                TextMMD(text = share.name, style = MaterialTheme.typography.bodyLarge)
                                TextMMD(
                                    text = stringResource(if (share.canEdit) R.string.share_can_edit else R.string.share_can_read),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            SwitchMMD(checked = share.canEdit, onCheckedChange = null)
                        }
                    }
                    item(key = "stop-${share.id}") {
                        val armed = rememberArmed()
                        TextMMD(
                            text = if (armed.value) {
                                stringResource(R.string.share_stop_confirm, share.name)
                            } else {
                                stringResource(R.string.share_stop, share.name)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (armed.value) FontWeight.Bold else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !busy) {
                                    if (armed.value) act { sharing!!.stop(share.id) } else armed.value = true
                                }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                        )
                    }
                }
            }

            if (others.isNotEmpty()) {
                item(key = "others-head") { Heading(stringResource(R.string.share_with)) }
                for (person in others) {
                    item(key = "person-${person.id}") {
                        TextMMD(
                            text = person.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !busy) {
                                    act { sharing!!.share(Notes.serverPath(path), person.id, canEdit = true) }
                                }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                        )
                    }
                }
            } else if (!busy && shares != null && current.isEmpty()) {
                item(key = "nobody") {
                    TextMMD(
                        text = stringResource(R.string.share_nobody),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    TextMMD(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}
