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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mudita.mmd.components.buttons.FloatingActionButtonMMD
import com.mudita.mmd.components.checkbox.CheckboxMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.oboegaki.R
import com.wanderwildwood.oboegaki.notes.SyncState
import com.wanderwildwood.oboegaki.remind.Reminders
import com.wanderwildwood.oboegaki.remind.Times
import com.wanderwildwood.oboegaki.tasks.Item
import com.wanderwildwood.oboegaki.tasks.TaskList
import com.wanderwildwood.oboegaki.tasks.Tasks
import com.wanderwildwood.oboegaki.tasks.day
import com.wanderwildwood.oboegaki.tasks.local
import kotlinx.coroutines.delay
import com.wanderwildwood.oboegaki.tasks.nextOccurrence
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The tasks: one section for each of the reader's Nextcloud task lists, the ones shared with
 * them included, and the list kept on this phone when there is one. Open tasks first, the soonest
 * due at the top; the done ones folded under a line that opens them. A tick is one tap.
 *
 * While it is open it asks the server every minute, so a task added in Thunderbird shows up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    onOpen: (Item) -> Unit,
    onNew: (list: String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val shown by Tasks.shown.collectAsStateWithLifecycle()
    val sync by Tasks.sync.collectAsStateWithLifecycle()
    val reminders by Reminders.version.collectAsStateWithLifecycle()
    var making by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf(setOf<String>()) }
    val ringing = remember(reminders, shown) {
        shown.flatMap { it.items }.filter { Reminders.isTaskHere(context, it.task.uid) }.map { it.task.uid }.toSet()
    }

    LaunchedEffect(Unit) {
        Tasks.reload()
        while (true) {
            Tasks.syncNow()
            delay(60_000)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.tasks_title)) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onBack) },
            )
        },
        floatingActionButton = {
            FloatingActionButtonMMD(onClick = { onNew(Tasks.defaultList().id) }) {
                Icon(Icons.Add, contentDescription = stringResource(R.string.cd_new_task), modifier = Modifier.size(28.dp))
            }
        },
    ) { contentPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            tasksSyncLine(sync)?.let { line ->
                TextMMD(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
                HorizontalDividerMMD()
            }
            LazyColumnMMD(modifier = Modifier.fillMaxSize()) {
                val nextcloud = Tasks.onNextcloud
                for (section in shown) {
                    val list = section.list
                    item(key = "list:${list.id}") { ListHeading(list) }
                    val open = section.items.filter { !it.task.fields.done }.sortedWith(openOrder)
                    val done = section.items.filter { it.task.fields.done }.sortedBy { it.task.fields.summary.lowercase() }
                    if (open.isEmpty()) {
                        item(key = "none:${list.id}") {
                            TextMMD(
                                text = stringResource(if (done.isEmpty()) R.string.tasks_none else R.string.tasks_all_done),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            )
                        }
                    }
                    for (item in open) {
                        item(key = "task:${list.id}/${item.name}") {
                            TaskRow(item, list.readOnly, item.task.uid in ringing, onOpen = { onOpen(item) })
                        }
                    }
                    if (done.isNotEmpty()) {
                        val isOpen = list.id in opened
                        item(key = "done:${list.id}") {
                            TextMMD(
                                text = stringResource(if (isOpen) R.string.tasks_done_hide else R.string.tasks_done_show, done.size),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { opened = if (isOpen) opened - list.id else opened + list.id }
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                            )
                        }
                        if (isOpen) {
                            for (item in done) {
                                item(key = "task:${list.id}/${item.name}") {
                                    TaskRow(item, list.readOnly, false, onOpen = { onOpen(item) })
                                }
                            }
                        }
                    }
                    if (list.onPhone && nextcloud && section.items.isNotEmpty()) {
                        val to = Tasks.defaultList().takeIf { !it.onPhone } ?: Tasks.writableLists().firstOrNull { !it.onPhone }
                        if (to != null) item(key = "move:${list.id}") {
                            Leave(
                                stringResource(R.string.tasks_move_to_nextcloud, to.name),
                                stringResource(R.string.tasks_move_to_nextcloud_confirm, to.name),
                            ) { Tasks.moveToNextcloud(to.id) }
                        }
                    }
                    item(key = "rule:${list.id}") { HorizontalDividerMMD() }
                }
                if (nextcloud) {
                    item(key = "new-list") {
                        TextMMD(
                            text = stringResource(R.string.tasks_new_list),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { making = true }
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                        )
                    }
                }
                // Room under the last row for the button that floats over it.
                item(key = "foot") { Spacer(Modifier.height(96.dp)) }
            }
        }
    }

    if (making) NewListDialog(onDismiss = { making = false })
}

/** Open tasks: the ones due first, soonest at the top, then the rest by priority and title. */
private val openOrder = compareBy<Item>(
    { it.task.fields.due == null },
    { it.task.fields.due?.local() ?: it.task.fields.due?.day()?.atTime(23, 59) },
    { if (it.task.fields.priority == 0) 10 else it.task.fields.priority },
    { it.task.fields.summary.lowercase() },
)

@Composable
private fun ListHeading(list: TaskList) {
    val context = LocalContext.current
    val line = when {
        list.onPhone -> stringResource(R.string.tasks_kept_here)
        list.synced == 0L -> stringResource(R.string.tasks_not_synced)
        else -> {
            val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(list.synced), ZoneId.systemDefault())
            stringResource(R.string.tasks_synced, Times.whenShort(context, at))
        }
    }
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp)) {
        TextMMD(text = list.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        TextMMD(
            text = if (list.readOnly) line + " · " + stringResource(R.string.tasks_read_only) else line,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun TaskRow(item: Item, readOnly: Boolean, rings: Boolean, onOpen: () -> Unit) {
    val f = item.task.fields
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 8.dp, end = 20.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The box ticks; the words open the task. A list shared read-only cannot be ticked here.
        CheckboxMMD(checked = f.done, onCheckedChange = { if (!readOnly) Tasks.tick(item) }, enabled = !readOnly)
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            TextMMD(
                text = f.summary.ifBlank { stringResource(R.string.task_untitled) },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val due = f.due
            val overdue = due != null && !f.done && (due.local()?.isBefore(LocalDateTime.now()) ?: due.day().isBefore(LocalDate.now()))
            val parts = listOfNotNull(
                due?.let { Tasks.dueText(it) },
                priorityWord(f.priority).takeIf { f.priority != 0 }?.let { stringResource(it) },
                stringResource(R.string.task_repeats_short).takeIf { item.task.repeats != null },
                f.description.lineSequence().firstOrNull { it.isNotBlank() }?.trim(),
            )
            if (parts.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (rings && due?.local() != null && !f.done) {
                        Icon(
                            painter = painterResource(R.drawable.ic_reminder),
                            contentDescription = stringResource(R.string.cd_rings_here),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(end = 3.dp).size(14.dp),
                        )
                    }
                    TextMMD(
                        text = parts.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (overdue) FontWeight.Bold else null,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** iCalendar's priority as a word: 1–4 high, 5 medium, 6–9 low. */
fun priorityWord(priority: Int): Int = when (priority) {
    in 1..4 -> R.string.task_priority_high
    5 -> R.string.task_priority_medium
    in 6..9 -> R.string.task_priority_low
    else -> R.string.task_priority_none
}

@Composable
private fun tasksSyncLine(sync: SyncState): String? = when (sync) {
    SyncState.Idle, SyncState.Running -> null
    SyncState.Unreachable -> stringResource(R.string.tasks_unreachable)
    SyncState.SignedOut -> stringResource(R.string.sync_signed_out)
    is SyncState.Failed -> stringResource(R.string.tasks_failed, sync.why)
}

/** A new task list on the Nextcloud, by name. */
@Composable
private fun NewListDialog(onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = stringResource(R.string.tasks_new_list), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        TextFieldMMD(
            value = name,
            onValueChange = { name = it.replace("\n", "") },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { TextMMD(text = stringResource(R.string.tasks_new_list_hint)) },
            singleLine = true,
            enabled = !working,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        )
        if (failed) {
            Spacer(Modifier.height(8.dp))
            TextMMD(text = stringResource(R.string.tasks_new_list_failed), style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            OutlinedButtonMMD(onClick = onDismiss, modifier = Modifier.weight(1f).height(48.dp)) {
                TextMMD(text = stringResource(R.string.remind_cancel), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(12.dp))
            ButtonMMD(
                onClick = {
                    working = true
                    failed = false
                    scope.launch {
                        val made = runCatching { Tasks.makeList(name.trim()) }.getOrNull()
                        working = false
                        if (made != null) onDismiss() else failed = true
                    }
                },
                enabled = name.isNotBlank() && !working,
                modifier = Modifier.weight(1f).height(48.dp),
            ) { TextMMD(text = stringResource(R.string.tasks_new_list_make), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Whether ticking this repeating task moves it on (true) or finishes it (false). */
fun movesOn(item: Item): Boolean = item.task.repeats != null && nextOccurrence(item.text) != null
