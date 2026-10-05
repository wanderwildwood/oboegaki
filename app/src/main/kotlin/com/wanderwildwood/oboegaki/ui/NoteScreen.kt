package com.wanderwildwood.oboegaki.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mudita.mmd.components.checkbox.CheckboxMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.oboegaki.R
import com.wanderwildwood.oboegaki.notes.Keeping
import com.wanderwildwood.oboegaki.notes.Line
import com.wanderwildwood.oboegaki.notes.Notes
import com.wanderwildwood.oboegaki.notes.addTask
import com.wanderwildwood.oboegaki.notes.hasTasks
import com.wanderwildwood.oboegaki.notes.lines
import com.wanderwildwood.oboegaki.notes.toggle
import com.wanderwildwood.oboegaki.notes.toggleTaskLine
import com.wanderwildwood.oboegaki.notes.recordingOn
import com.wanderwildwood.oboegaki.notes.scanOn
import com.wanderwildwood.oboegaki.notes.embeds
import com.wanderwildwood.oboegaki.hearing.Voice
import kotlinx.coroutines.delay

/**
 * One note, read or written.
 *
 * A note with tasks in it opens as a list, where a tick is one tap and an item is added from the
 * foot, because that is what a shopping list is for in a shop: a keyboard covering half the
 * panel is in the way. Everything else opens to be written in. The pencil switches between them.
 *
 * It saves itself, a moment after the typing stops and again on the way out, so there is no
 * Save to forget. While it is open on Nextcloud it asks the server every half minute, so a list
 * two people are shopping from shows what the other just ticked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteScreen(
    path: String,
    initialText: String,
    fresh: Boolean,
    initialTitle: String = "",
    onClose: () -> Unit,
    onShare: (path: String, title: String) -> Unit = { _, _ -> },
) {
    var at by remember { mutableStateOf(path) }
    var loaded by remember { mutableStateOf(if (fresh) "" else initialText) }
    var body by remember { mutableStateOf(TextFieldValue(initialText, TextRange(initialText.length))) }
    var title by remember { mutableStateOf(if (fresh) initialTitle else at.substringAfterLast('/').substringBeforeLast('.')) }
    var writing by remember { mutableStateOf(fresh || !readable(initialText)) }
    var deleted by remember { mutableStateOf(false) }
    val changed by Notes.changed.collectAsStateWithLifecycle()
    val hearing by Voice.hearing.collectAsStateWithLifecycle()

    fun save() {
        if (deleted) return
        val saved = Notes.save(at, loaded, body.text, title)
        at = saved.path
        loaded = saved.text
        if (saved.text != body.text) body = body.copy(text = saved.text, selection = TextRange(saved.text.length.coerceAtMost(body.selection.end)))
    }

    // A moment after the typing stops.
    LaunchedEffect(body.text, title) {
        delay(1500)
        save()
    }

    // When a sync has brought something in, and nothing here is unsaved, show it.
    LaunchedEffect(changed) {
        if (body.text == loaded) {
            val now = Notes.read(at)
            if (now != null && now != loaded) {
                loaded = now
                body = body.copy(text = now, selection = TextRange(now.length.coerceAtMost(body.selection.end)))
            }
        }
    }

    // Every half minute while it is open, so a shared list keeps up.
    if (Notes.preferences.keeping == Keeping.NEXTCLOUD) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(30_000)
                save()
                Notes.syncNow()
            }
        }
    }

    // On the way out by any door: back, home, the screen going off.
    val saveNow by rememberUpdatedState(::save)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                saveNow()
                Notes.afterEdit()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            saveNow()
            Notes.afterEdit()
        }
    }

    val close = {
        if (writing && !fresh && readable(body.text)) {
            writing = false
        } else {
            onClose()
        }
    }
    BackHandler(onBack = close)

    val armed = rememberArmed()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = {},
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), close) },
                actions = {
                    if (armed.value) {
                        TextMMD(
                            text = stringResource(R.string.note_delete_confirm),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable {
                                    deleted = true
                                    Notes.delete(at)
                                    onClose()
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                        )
                    } else {
                        if (writing) {
                            BarButton(Icons.Checklist, stringResource(R.string.cd_task_line)) {
                                val (text, moved) = toggleTaskLine(body.text, body.selection.end)
                                val cursor = (body.selection.end + moved).coerceIn(0, text.length)
                                body = TextFieldValue(text, TextRange(cursor))
                            }
                        } else {
                            BarButton(Icons.Edit, stringResource(R.string.cd_edit)) { writing = true }
                        }
                        // Sharing is Nextcloud's, and needs something there to share.
                        if (Notes.preferences.keeping == Keeping.NEXTCLOUD && (body.text.isNotBlank() || title.isNotBlank())) {
                            BarButton(Icons.Share, stringResource(R.string.cd_share)) {
                                save()
                                onShare(at, title.ifBlank { at.substringAfterLast('/').substringBeforeLast('.') })
                            }
                        }
                        BarButton(Icons.Delete, stringResource(R.string.cd_delete)) { armed.value = true }
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp)) {
            if (writing) {
                Writing(
                    title = title,
                    onTitle = { title = it },
                    body = body,
                    onBody = { body = it },
                    focus = fresh,
                )
            } else {
                Reading(
                    title = title,
                    folder = at.substringBeforeLast('/', ""),
                    hearing = at in hearing,
                    text = body.text,
                    onToggle = { index -> body = body.copy(text = toggle(body.text, index)) },
                    onAdd = { item -> body = body.copy(text = addTask(body.text, item)) },
                )
            }
        }
    }
}

@Composable
private fun Writing(
    title: String,
    onTitle: (String) -> Unit,
    body: TextFieldValue,
    onBody: (TextFieldValue) -> Unit,
    focus: Boolean,
) {
    val focusBody = remember { FocusRequester() }
    TextFieldMMD(
        value = title,
        onValueChange = { onTitle(it.replace("\n", "")) },
        modifier = Modifier.fillMaxWidth().textActions(),
        textStyle = MaterialTheme.typography.titleLarge,
        placeholder = { TextMMD(text = stringResource(R.string.note_title_hint), style = MaterialTheme.typography.titleLarge) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focusBody.requestFocus() }),
    )
    Spacer(Modifier.height(8.dp))
    TextField(
        value = body,
        onValueChange = onBody,
        modifier = Modifier.fillMaxSize().focusRequester(focusBody).textActions(body, onBody),
        textStyle = MaterialTheme.typography.bodyLarge,
        placeholder = { TextMMD(text = stringResource(R.string.note_body_hint), style = MaterialTheme.typography.bodyLarge) },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        // Not TextFieldMMD: its rule beneath belongs under a field, and this is the page.
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedIndicatorColor = MaterialTheme.colorScheme.surface,
            unfocusedIndicatorColor = MaterialTheme.colorScheme.surface,
        ),
    )
    if (focus) {
        LaunchedEffect(Unit) { focusBody.requestFocus() }
    }
}

@Composable
private fun Reading(
    title: String,
    folder: String,
    hearing: Boolean,
    text: String,
    onToggle: (Int) -> Unit,
    onAdd: (String) -> Unit,
) {
    var adding by remember { mutableStateOf("") }
    // A first heading that only says the title again is the file's business, not the reader's:
    // Obsidian and others write one, and here the title is already at the top.
    val parsed = remember(text, title) {
        val all = lines(text).dropLastWhile { it is Line.Text && it.text.isBlank() }
        val first = all.firstOrNull { !(it is Line.Text && it.text.isBlank()) }
        if (first is Line.Text && first.text.trimStart('#', ' ').trim().equals(title.trim(), ignoreCase = true) && first.text.startsWith("#")) {
            all - first
        } else {
            all
        }
    }

    LazyColumnMMD(modifier = Modifier.fillMaxSize()) {
        item(key = "title") {
            TextMMD(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
            )
        }
        for (line in parsed) {
            item(key = line.index) {
                when (line) {
                    is Line.Task -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(line.index) }
                            .padding(start = (line.indent.length * 8).dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The box takes its own press as well as the row: MMD's checkbox hands its
                        // click handler a lambda even when given null, so a null here swallowed every
                        // tap on the box itself and only the words ticked anything.
                        CheckboxMMD(checked = line.done, onCheckedChange = { onToggle(line.index) })
                        Spacer(Modifier.width(8.dp))
                        TextMMD(text = line.text, style = MaterialTheme.typography.bodyLarge)
                    }
                    is Line.Text -> if (scanOn(line.text) != null) {
                        val name = scanOn(line.text)!!
                        val path = if (folder.isEmpty()) name else "$folder/$name"
                        Pages(uri = Notes.shelf()?.uriOf(path), name = name)
                    } else if (recordingOn(line.text) != null) {
                        val name = recordingOn(line.text)!!
                        val path = if (folder.isEmpty()) name else "$folder/$name"
                        Player(uri = Notes.shelf()?.uriOf(path), hearing = hearing)
                    } else {
                        val heading = line.text.trimStart().startsWith("#")
                        TextMMD(
                            text = if (heading) line.text.trimStart('#', ' ') else line.text,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (heading) FontWeight.Bold else null,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
        // A list grows from its foot; a note that is not a list is added to with the pencil.
        if (parsed.any { it is Line.Task }) item(key = "add") {
            TextFieldMMD(
                value = adding,
                onValueChange = { adding = it.replace("\n", "") },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp),
                placeholder = { TextMMD(text = stringResource(R.string.note_add_item)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (adding.isNotBlank()) {
                        onAdd(adding)
                        adding = ""
                    }
                }),
            )
        }
    }
}

/** A note opens to be read rather than written when it holds tasks to tick or a recording to play. */
private fun readable(text: String): Boolean = hasTasks(text) || embeds(text).isNotEmpty()
