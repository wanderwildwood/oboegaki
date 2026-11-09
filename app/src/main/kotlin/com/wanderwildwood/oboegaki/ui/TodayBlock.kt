package com.wanderwildwood.oboegaki.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.checkbox.CheckboxMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.wanderwildwood.oboegaki.R
import com.wanderwildwood.oboegaki.remind.Times
import com.wanderwildwood.oboegaki.tasks.Due
import com.wanderwildwood.oboegaki.tasks.local
import com.wanderwildwood.oboegaki.today.Entry
import java.time.LocalDate

/**
 * Today, at the top of the notes list: what is due today or overdue from the tasks, the checklist
 * items and the notes, then a line to add a task for today by typing its name. A tap opens the
 * task or the note; the box ticks; a hold moves it to another day.
 */
fun LazyListScope.todayItems(
    entries: List<Entry>,
    onOpen: (Entry) -> Unit,
    onTick: (Entry) -> Unit,
    onHold: (Entry) -> Unit,
    onAdd: (String) -> Unit,
) {
    item(key = "today") {
        TextMMD(
            text = stringResource(R.string.today_heading),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 2.dp),
        )
    }
    for (entry in entries) {
        item(key = "today:" + entryKey(entry)) { TodayRow(entry, onOpen, onTick, onHold) }
    }
    item(key = "today-add") { QuickAdd(onAdd) }
    item(key = "today-rule") { DottedRule(start = 20.dp) }
}

private fun entryKey(e: Entry): String = when (e) {
    is Entry.OfTask -> "t/${e.item.list}/${e.item.name}"
    is Entry.OfNote -> "n/${e.path}/${e.reminder.key}/${e.reminder.at}"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TodayRow(entry: Entry, onOpen: (Entry) -> Unit, onTick: (Entry) -> Unit, onHold: (Entry) -> Unit) {
    val context = LocalContext.current
    val canTick = when (entry) {
        is Entry.OfTask -> !entry.readOnly
        is Entry.OfNote -> entry.isItem
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = { onOpen(entry) }, onLongClick = { onHold(entry) })
            .padding(start = 8.dp, end = 20.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry is Entry.OfNote && !entry.isItem) {
            // A note's own reminder: nothing to tick, so the bell stands where the box would.
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_reminder),
                    contentDescription = stringResource(R.string.cd_today_note_reminder),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            CheckboxMMD(checked = false, onCheckedChange = { onTick(entry) }, enabled = canTick)
        }
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            TextMMD(
                text = entry.title.ifBlank { stringResource(R.string.task_untitled) },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val whenText = entry.at?.let { Times.time(context, it) }
            val dateText = if (entry.overdue) stringResource(R.string.today_overdue, Times.relativeDay(context, entry.day)) else null
            val where = when (entry) {
                is Entry.OfTask -> entry.listName
                is Entry.OfNote -> entry.noteTitle.takeIf { entry.isItem }
            }
            val line = listOfNotNull(dateText, whenText, where).joinToString(" · ")
            if (line.isNotEmpty()) {
                TextMMD(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (entry.overdue) FontWeight.Bold else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A task for today by its name alone: type it and press Done; the line clears for the next. */
@Composable
private fun QuickAdd(onAdd: (String) -> Unit) {
    var words by remember { mutableStateOf("") }
    fun add() {
        if (words.isNotBlank()) onAdd(words)
        words = ""
    }
    TextFieldMMD(
        value = words,
        onValueChange = { words = it.replace("\n", "") },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).textActions(),
        placeholder = { TextMMD(text = stringResource(R.string.today_add)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { add() }),
    )
}

/**
 * A held Today row, moved: to tomorrow, a week on, or a day picked (a task may take a new time
 * there, or lose its date; a note's reminder keeps its time unless one is picked).
 */
@Composable
fun MoveDialog(
    entry: Entry,
    onMove: (day: LocalDate, due: Due?) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    if (picking) {
        val current: Due = when (entry) {
            is Entry.OfTask -> entry.item.task.fields.due!!
            is Entry.OfNote -> Due.At(entry.at, null)
        }
        DueDialog(
            current = current,
            onSet = { due ->
                picking = false
                when (due) {
                    null -> onClear()
                    is Due.Day -> onMove(due.date, due)
                    is Due.At -> onMove(due.local()!!.toLocalDate(), due)
                }
            },
            onDismiss = onDismiss,
        )
        return
    }
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = stringResource(R.string.today_move_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        MoveChoice(stringResource(R.string.today_tomorrow)) { onMove(today.plusDays(1), null) }
        MoveChoice(stringResource(R.string.today_next_week)) { onMove(today.plusWeeks(1), null) }
        MoveChoice(stringResource(R.string.today_pick)) { picking = true }
    }
}

@Composable
private fun MoveChoice(words: String, onClick: () -> Unit) {
    TextMMD(
        text = words,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
    )
}
