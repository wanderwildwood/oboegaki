package com.wanderwildwood.oboegaki.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.checkbox.CheckboxMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.switcher.SwitchMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.oboegaki.R
import com.wanderwildwood.oboegaki.remind.Reminders
import com.wanderwildwood.oboegaki.remind.Times
import com.wanderwildwood.oboegaki.tasks.Due
import com.wanderwildwood.oboegaki.tasks.Fields
import com.wanderwildwood.oboegaki.tasks.Tasks
import com.wanderwildwood.oboegaki.tasks.day
import com.wanderwildwood.oboegaki.tasks.local
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * One task, to read and change: its title, whether it is done, when it is due and whether it
 * rings here then, its priority, its list and its notes. Like a note it saves itself, a moment
 * after a change and on the way out by any door, so there is no Save to forget.
 *
 * [name] null is a new task in [list], made once it has a title.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskScreen(list: String, name: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    // The task as last saved; null until a new one has been.
    var item by remember { mutableStateOf(name?.let { Tasks.item(list, it) }) }
    val start = item?.task?.fields ?: Fields("")
    var loaded by remember { mutableStateOf(start) }
    var summary by remember { mutableStateOf(start.summary) }
    var description by remember { mutableStateOf(start.description) }
    var due by remember { mutableStateOf(start.due) }
    var priority by remember { mutableStateOf(start.priority) }
    var done by remember { mutableStateOf(start.done) }
    var inList by remember { mutableStateOf(list) }
    // A new task set on this phone rings here, as a reminder set here does.
    var ring by remember { mutableStateOf(item?.let { Reminders.isTaskHere(context, it.task.uid) } ?: true) }
    var ringLoaded by remember { mutableStateOf(ring) }
    var deleted by remember { mutableStateOf(false) }
    val readOnly = remember(inList) { Tasks.store.list(inList)?.readOnly == true }
    val shown by Tasks.shown.collectAsStateWithLifecycle()

    var pickingDue by remember { mutableStateOf(false) }
    var pickingPriority by remember { mutableStateOf(false) }
    var pickingList by remember { mutableStateOf(false) }

    fun fields() = Fields(summary.trim(), description.trimEnd(), due, priority, done)

    fun save() {
        if (deleted || readOnly) return
        val now = fields()
        val current = item
        if (current == null) {
            if (now.summary.isEmpty()) return
            item = Tasks.add(inList, now, ring && due?.local() != null)
            loaded = now
            ringLoaded = ring
            return
        }
        var at = current
        if (inList != at.list) at = Tasks.move(at, inList) ?: return
        if (now != loaded || ring != ringLoaded || at !== current) {
            item = Tasks.edit(at, loaded, now, ring) ?: at
            loaded = now
            ringLoaded = ring
        }
    }

    // A moment after a change.
    LaunchedEffect(summary, description, due, priority, done, ring, inList) {
        delay(1500)
        save()
    }

    // What a sync brings in while the task is open shows, if nothing here is unsaved.
    LaunchedEffect(shown) {
        val current = item ?: return@LaunchedEffect
        if (fields() != loaded) return@LaunchedEffect
        val now = Tasks.item(current.list, current.name)
        if (now == null) {
            // Deleted on the server, or moved by a sync: nothing left here to show.
            if (!deleted) onClose()
            return@LaunchedEffect
        }
        val f = now.task.fields
        if (f != loaded) {
            item = now
            loaded = f
            summary = f.summary
            description = f.description
            due = f.due
            priority = f.priority
            done = f.done
        }
    }

    val saveNow by rememberUpdatedState(::save)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        // On pause, not only on stop: a stop can come late, or not before the process goes.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) saveNow()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            saveNow()
        }
    }

    BackHandler(onBack = onClose)
    val armed = rememberArmed()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = {},
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onClose) },
                actions = {
                    val current = item
                    if (current != null && !readOnly) {
                        if (armed.value) {
                            TextMMD(
                                text = stringResource(R.string.note_delete_confirm),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable {
                                        deleted = true
                                        Tasks.delete(current)
                                        onClose()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                            )
                        } else {
                            BarButton(Icons.Delete, stringResource(R.string.cd_delete_task)) { armed.value = true }
                        }
                    }
                },
            )
        },
    ) { contentPadding ->
        LazyColumnMMD(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            item(key = "title") {
                val focus = remember { FocusRequester() }
                TextFieldMMD(
                    value = summary,
                    onValueChange = { summary = it.replace("\n", "") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus).textActions(),
                    textStyle = MaterialTheme.typography.titleLarge,
                    placeholder = { TextMMD(text = stringResource(R.string.task_title_hint), style = MaterialTheme.typography.titleLarge) },
                    maxLines = 3,
                    enabled = !readOnly,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                )
                if (name == null) LaunchedEffect(Unit) { focus.requestFocus() }
                Spacer(Modifier.height(8.dp))
            }
            if (readOnly) item(key = "read-only") {
                TextMMD(
                    text = stringResource(R.string.task_read_only),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }
            item(key = "done") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !readOnly) { done = !done }
                        .padding(start = 8.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CheckboxMMD(checked = done, onCheckedChange = { if (!readOnly) done = !done }, enabled = !readOnly)
                    Spacer(Modifier.width(4.dp))
                    TextMMD(text = stringResource(R.string.task_done), style = MaterialTheme.typography.bodyLarge)
                }
                HorizontalDividerMMD()
            }
            item(key = "due") {
                SettingRow(
                    stringResource(R.string.task_field_due),
                    due?.let { Tasks.dueText(it) } ?: stringResource(R.string.task_due_none),
                ) { if (!readOnly) pickingDue = true }
            }
            if (due?.local() != null && !readOnly) item(key = "ring") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { ring = !ring }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextMMD(text = stringResource(R.string.task_ring_here), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    SwitchMMD(checked = ring, onCheckedChange = null)
                }
                HorizontalDividerMMD()
            }
            item(key = "priority") {
                SettingRow(stringResource(R.string.task_field_priority), stringResource(priorityWord(priority))) {
                    if (!readOnly) pickingPriority = true
                }
            }
            item(key = "list") {
                SettingRow(stringResource(R.string.task_field_list), Tasks.listName(inList).orEmpty()) {
                    if (!readOnly && Tasks.writableLists().size > 1) pickingList = true
                }
            }
            item?.task?.repeats?.let {
                item(key = "repeats") {
                    TextMMD(
                        text = stringResource(if (movesOn(item!!)) R.string.task_repeats_moves_on else R.string.task_repeats_finishes),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                    HorizontalDividerMMD()
                }
            }
            item(key = "notes") {
                TextFieldMMD(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(horizontal = 16.dp, vertical = 8.dp).textActions(),
                    label = { TextMMD(text = stringResource(R.string.task_field_notes)) },
                    enabled = !readOnly,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (pickingDue) {
        DueDialog(
            current = due,
            onSet = {
                // A time set on this phone rings on this phone, as a reminder set here does.
                if (it?.local() != null && it.key != due?.key) ring = true
                due = it
                pickingDue = false
            },
            onDismiss = { pickingDue = false },
        )
    }
    if (pickingPriority) {
        EInkDialog(onDismiss = { pickingPriority = false }) {
            TextMMD(text = stringResource(R.string.task_field_priority), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            for ((value, word) in listOf(0 to R.string.task_priority_none, 9 to R.string.task_priority_low, 5 to R.string.task_priority_medium, 1 to R.string.task_priority_high)) {
                val chosen = priorityWord(priority) == word
                TextMMD(
                    text = stringResource(word),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (chosen) FontWeight.Bold else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (!chosen) priority = value
                            pickingPriority = false
                        }
                        .padding(vertical = 12.dp),
                )
            }
        }
    }
    if (pickingList) {
        EInkDialog(onDismiss = { pickingList = false }) {
            TextMMD(text = stringResource(R.string.task_field_list), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            for (l in Tasks.writableLists()) {
                TextMMD(
                    text = l.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (l.id == inList) FontWeight.Bold else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            inList = l.id
                            pickingList = false
                        }
                        .padding(vertical = 12.dp),
                )
            }
        }
    }
}

/**
 * When a task is due: a day, and a time if it has one. A task with a time can ring; one due on a
 * day only cannot, as there is no moment to ring at.
 */
@Composable
private fun DueDialog(current: Due?, onSet: (Due?) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val initial = current?.local() ?: LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).plusHours(1)
    var day by remember { mutableStateOf(current?.day() ?: initial.toLocalDate()) }
    var time by remember { mutableStateOf<LocalTime?>(if (current is Due.Day) null else initial.toLocalTime()) }
    var pickingDay by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }

    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = stringResource(R.string.task_field_due), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        PickRow(stringResource(R.string.remind_day), Times.relativeDay(context, day)) { pickingDay = true }
        PickRow(stringResource(R.string.remind_time), time?.let { Times.time(context, day.atTime(it)) } ?: stringResource(R.string.task_no_time)) { pickingTime = true }
        if (time != null) {
            TextMMD(
                text = stringResource(R.string.task_no_time_choose),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().clickable { time = null }.padding(vertical = 12.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            OutlinedButtonMMD(onClick = onDismiss, modifier = Modifier.weight(1f).height(48.dp)) {
                TextMMD(text = stringResource(R.string.remind_cancel), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(12.dp))
            ButtonMMD(
                onClick = {
                    val t = time
                    onSet(if (t == null) Due.Day(day) else Due.At(day.atTime(t), ZoneId.systemDefault()))
                },
                modifier = Modifier.weight(1f).height(48.dp),
            ) { TextMMD(text = stringResource(R.string.remind_set), style = MaterialTheme.typography.bodySmall) }
        }
        if (current != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButtonMMD(onClick = { onSet(null) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                TextMMD(text = stringResource(R.string.task_due_remove), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (pickingDay) DateDialog(initial = day, onPick = { day = it }, onDismiss = { pickingDay = false })
    if (pickingTime) TimeDialog(initial = time ?: LocalTime.of(9, 0), onPick = { time = it }, onDismiss = { pickingTime = false })
}
