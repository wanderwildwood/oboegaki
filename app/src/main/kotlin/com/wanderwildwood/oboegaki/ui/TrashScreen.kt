package com.wanderwildwood.oboegaki.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.oboegaki.R
import com.wanderwildwood.oboegaki.notes.Notes
import com.wanderwildwood.oboegaki.notes.TRASH_DAYS
import com.wanderwildwood.oboegaki.notes.Trashed
import java.text.DateFormat
import java.util.Date

/**
 * The trash: the notes deleted in the last [TRASH_DAYS] days, newest first, each with the day it
 * was deleted. Restore is one tap; Delete forever and Empty trash are armed (tap again within
 * six seconds). The line under the bar says when the rest goes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(onBack: () -> Unit) {
    val trash by Notes.trash.collectAsStateWithLifecycle()
    val armed = rememberArmed()
    val listState = rememberLazyListState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.trash_title)) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onBack) },
                actions = {
                    if (trash.isNotEmpty()) {
                        TextMMD(
                            text = if (armed.value) {
                                pluralStringResource(R.plurals.trash_empty_confirm, trash.size, trash.size)
                            } else {
                                stringResource(R.string.trash_empty)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (armed.value) FontWeight.Bold else null,
                            modifier = Modifier
                                .clickable {
                                    if (armed.value) {
                                        armed.value = false
                                        Notes.emptyTrashSoon()
                                    } else {
                                        armed.value = true
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            TextMMD(
                text = pluralStringResource(R.plurals.trash_rule, TRASH_DAYS, TRASH_DAYS),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
            HorizontalDividerMMD()
            if (trash.isEmpty()) {
                TextMMD(
                    text = stringResource(R.string.trash_none),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            } else {
                PagedColumn(modifier = Modifier.fillMaxSize(), state = listState) {
                    for (item in trash) {
                        item(key = item.slot) {
                            TrashRow(item, ruled = item !== trash.last())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrashRow(item: Trashed, ruled: Boolean) {
    val armed = rememberArmed()
    val day = remember(item.at) { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.at)) }
    Box(Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            TextMMD(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val from = item.folder.substringAfterLast('/').takeIf { it.isNotEmpty() }
            TextMMD(
                text = listOfNotNull(stringResource(R.string.trash_deleted, day), from).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextMMD(
                    text = stringResource(R.string.trash_restore),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .clickable { Notes.restoreSoon(item.slot) }
                        .padding(end = 24.dp, top = 8.dp, bottom = 8.dp),
                )
                TextMMD(
                    text = stringResource(if (armed.value) R.string.trash_forever_confirm else R.string.trash_forever),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (armed.value) FontWeight.Bold else null,
                    modifier = Modifier
                        .clickable {
                            if (armed.value) Notes.deleteForeverSoon(item.slot) else armed.value = true
                        }
                        .padding(top = 8.dp, bottom = 8.dp),
                )
            }
        }
        if (ruled) DottedRule(Modifier.align(Alignment.BottomStart), start = 20.dp, end = 20.dp)
    }
}
