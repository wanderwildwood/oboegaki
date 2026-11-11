package com.wanderwildwood.oboegaki.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.FloatingActionButtonMMD
import androidx.compose.foundation.lazy.rememberLazyListState
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.oboegaki.R
import com.wanderwildwood.oboegaki.notes.Note
import com.wanderwildwood.oboegaki.notes.Order
import com.wanderwildwood.oboegaki.notes.Showing
import com.wanderwildwood.oboegaki.notes.Moving
import com.wanderwildwood.oboegaki.notes.SyncState
import com.wanderwildwood.oboegaki.notes.arrange
import com.wanderwildwood.oboegaki.notes.folders
import com.wanderwildwood.oboegaki.notes.parentFolder
import com.wanderwildwood.oboegaki.notes.subfolders
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wanderwildwood.oboegaki.tasks.Tasks
import com.wanderwildwood.oboegaki.today.Entry
import com.wanderwildwood.oboegaki.today.TodayActions
import com.wanderwildwood.oboegaki.today.gather
import java.time.LocalDate

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
    pinned: Set<String>,
    canShare: Boolean,
    sync: SyncState,
    moving: Moving?,
    showing: String,
    order: Order,
    onView: (showing: String, order: Order) -> Unit,
    onOpen: (Note) -> Unit,
    onNew: (folder: String) -> Unit,
    onRecord: () -> Unit,
    onScan: () -> Unit,
    onSettings: () -> Unit,
    onTasks: () -> Unit,
    onOpenToday: (Entry) -> Unit,
    onTrash: () -> Unit,
    // A sync to run when the list is pulled down from its top, returning the state it left;
    // null when the notes live only on this phone.
    onPull: (suspend () -> SyncState)? = null,
) {
    val pull = rememberPull { onPull?.invoke() ?: SyncState.Idle }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var choosing by remember { mutableStateOf(false) }
    val shown = arrange(notes, shared, showing, order, if (searching) query else "", pinned)
    val listState = rememberLazyListState()
    val allFolders = folders(notes)
    // Supernote's tablets have no camera, and some have no microphone: no button for either then.
    val context = LocalContext.current
    // The keyboard up, whatever opened it: the buttons give way rather than stand over the field.
    val keyboardUp = rememberKeyboardUp()
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    val hasMicrophone = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) }

    // Today: what is due from the tasks and the notes, above all the notes (not in a folder, a
    // narrowed view or a search). The tasks are read when the list opens and asked of the server.
    val taskLists by Tasks.shown.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        Tasks.reload()
        Tasks.syncNow()
    }
    val showToday = !searching && showing == Showing.ALL
    val today = remember(notes, taskLists) { gather(notes, taskLists, LocalDate.now()) }
    var holding by remember { mutableStateOf<Entry?>(null) }

    if (searching) {
        BackHandler {
            searching = false
            query = ""
        }
    }

    // The + opens out to the ways of taking a note (paper, voice, the pen) and folds back when
    // one is chosen, on Back, on a touch anywhere else, and whenever the buttons give way.
    // A device with neither camera nor microphone has one way only, so its + goes straight there.
    var opened by remember { mutableStateOf(false) }
    val opens = hasCamera || hasMicrophone
    val buttonsShown = !searching && !keyboardUp
    if (!buttonsShown && opened) opened = false
    if (opened) BackHandler { opened = false }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.app_name)) },
                actions = {
                    BarButton(Icons.Tasks, stringResource(R.string.cd_tasks), onTasks)
                    BarButton(Icons.Search, stringResource(R.string.cd_search)) {
                        searching = !searching
                        if (!searching) query = ""
                    }
                    BarButton(Icons.Settings, stringResource(R.string.cd_settings), onSettings)
                },
            )
        },
        floatingActionButton = {
            // One + in the corner (11 Nov 2026; there were three buttons, standing over the
            // scrollbar). Not while searching: with the keyboard up it stood over the search field.
            if (buttonsShown) Column(horizontalAlignment = Alignment.End) {
                val newNote = { opened = false; onNew(Showing.folderOf(showing) ?: "") }
                if (opened) {
                    if (hasCamera) {
                        FloatingActionButtonMMD(onClick = { opened = false; onScan() }) {
                            Icon(Icons.Scan, contentDescription = stringResource(R.string.cd_scan), modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                    if (hasMicrophone) {
                        FloatingActionButtonMMD(onClick = { opened = false; onRecord() }) {
                            Icon(Icons.Mic, contentDescription = stringResource(R.string.cd_record), modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                    FloatingActionButtonMMD(onClick = newNote) {
                        Icon(Icons.Edit, contentDescription = stringResource(R.string.cd_new_note), modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    FloatingActionButtonMMD(onClick = { opened = false }) {
                        Icon(Icons.Close, contentDescription = stringResource(R.string.cd_add_close), modifier = Modifier.size(28.dp))
                    }
                } else {
                    FloatingActionButtonMMD(onClick = { if (opens) opened = true else newNote() }) {
                        Icon(
                            Icons.Add,
                            contentDescription = stringResource(if (opens) R.string.cd_add_open else R.string.cd_new_note),
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }
        },
    ) { contentPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            (movingLine(moving) ?: pulledWords(pull.pulled) { syncLine(it) } ?: syncLine(sync))?.let { line ->
                TextMMD(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
                DottedRule(start = 20.dp)
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
                    .heightIn(min = 48.dp)
                    .wrapContentHeight(Alignment.CenterVertically)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )

            // Inside a folder, the way back up and the folders in it come first, so a folder is
            // walked like one rather than found only through the line above.
            val current = Showing.folderOf(showing)?.takeIf { !searching }
            val inside = current?.let { subfolders(allFolders, it) }.orEmpty()

            if (shown.isEmpty() && current == null && !showToday) {
                TextMMD(
                    text = stringResource(if (notes.isEmpty()) R.string.list_empty else R.string.list_none_here),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            } else {
                PagedColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    // Sideways the buttons stand in a column at the right edge of a wide screen:
                    // the rows end before them rather than run under.
                    contentPadding = if (rememberShortWindow() && !searching && !keyboardUp) PaddingValues(end = FAB_ROOM) else PaddingValues(0.dp),
                    onPullAtTop = if (onPull != null) pull::pull else null,
                    // A list of tall rows: the note the foot cuts through shows there, and the next
                    // page opens on it whole, rather than a blank band at the foot (5 Nov 2026).
                    showCutRow = true,
                    // The scrollbar ends above the + button, so its lower arrow is not under it.
                    barClearBelow = if (buttonsShown) FAB_CLEAR else 0.dp,
                ) {
                    // A dotted rule under every row but the last, as Contacts draws its list.
                    val lastRow = shown.lastOrNull()?.path?.let { "note:$it" }
                        ?: inside.lastOrNull()?.let { "folder:$it" }
                        ?: "up"
                    if (showToday) {
                        todayItems(
                            today,
                            onOpen = onOpenToday,
                            onTick = { TodayActions.tick(context, it) },
                            onHold = { holding = it },
                            onAdd = { TodayActions.add(it) },
                        )
                        if (shown.isEmpty()) item(key = "empty") {
                            TextMMD(
                                text = stringResource(if (notes.isEmpty()) R.string.list_empty else R.string.list_none_here),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            )
                        }
                    }
                    if (current != null) {
                        val parent = parentFolder(current)
                        item(key = "up") {
                            FolderRow(
                                if (parent.isEmpty()) stringResource(R.string.list_up_all) else stringResource(R.string.list_up, parent.substringAfterLast('/')),
                                null,
                                ruled = lastRow != "up",
                            ) { onView(if (parent.isEmpty()) Showing.ALL else Showing.folder(parent), order) }
                        }
                        for (folder in inside) {
                            item(key = "folder:$folder") {
                                val count = notes.count { it.folder == folder || it.folder.startsWith("$folder/") }
                                FolderRow(folder.substringAfterLast('/'), pluralStringResource(R.plurals.list_folder, count, count), ruled = lastRow != "folder:$folder") {
                                    onView(Showing.folder(folder), order)
                                }
                            }
                        }
                        if (shown.isEmpty()) {
                            item(key = "none") {
                                TextMMD(
                                    text = stringResource(R.string.list_none_here),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                    for (note in shown) {
                        item(key = note.path) {
                            NoteRow(note, note.path in shared, note.path in pinned, showFolder = Showing.folderOf(showing) == null, ruled = lastRow != "note:${note.path}") { onOpen(note) }
                        }
                    }
                }
            }
        }
        // While the + is open, a touch anywhere on the list folds it back and does nothing else.
        if (opened) {
            Box(
                Modifier.fillMaxSize().padding(contentPadding).clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) { opened = false },
            )
        }
    }

    holding?.let { entry ->
        MoveDialog(
            entry,
            onMove = { day, exact ->
                TodayActions.move(context, entry, day, exact)
                holding = null
            },
            onClear = {
                TodayActions.clear(context, entry)
                holding = null
            },
            onDismiss = { holding = null },
        )
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
            onTrash = {
                choosing = false
                onTrash()
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
            Showing.ARCHIVE -> R.string.view_archive
            else -> R.string.view_all
        },
    )
    val how = stringResource(
        when (order) {
            Order.TITLE -> R.string.view_by_title
            Order.OLDEST -> R.string.view_oldest
            Order.CHANGED -> R.string.view_newest
        },
    )
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
    onTrash: () -> Unit,
    onDismiss: () -> Unit,
) {
    EInkDialog(onDismiss = onDismiss) {
        PagedColumn(modifier = Modifier.fillMaxWidth().height(dialogListHeightDp(440, rememberWindowHeightDp()).dp), showCutRow = true) {
            item { DialogHeading(stringResource(R.string.view_show)) }
            item { Choice(stringResource(R.string.view_all), showing == Showing.ALL) { onChoose(Showing.ALL, order) } }
            item { Choice(stringResource(R.string.view_lists), showing == Showing.LISTS) { onChoose(Showing.LISTS, order) } }
            item { Choice(stringResource(R.string.view_voice), showing == Showing.VOICE) { onChoose(Showing.VOICE, order) } }
            item { Choice(stringResource(R.string.view_scans), showing == Showing.SCANS) { onChoose(Showing.SCANS, order) } }
            if (canShare) {
                item { Choice(stringResource(R.string.view_shared), showing == Showing.SHARED) { onChoose(Showing.SHARED, order) } }
            }
            item { Choice(stringResource(R.string.view_archive), showing == Showing.ARCHIVE) { onChoose(Showing.ARCHIVE, order) } }
            item { Choice(stringResource(R.string.view_trash), false) { onTrash() } }
            if (folders.isNotEmpty()) item { DialogHeading(stringResource(R.string.view_folders)) }
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
            item { Choice(stringResource(R.string.view_oldest_choice), order == Order.OLDEST) { onChoose(showing, Order.OLDEST) } }
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
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically),
    )
}

@Composable
private fun movingLine(moving: Moving?): String? = when (moving) {
    Moving.Running -> stringResource(R.string.moving_running)
    is Moving.Done -> if (moving.failed > 0) pluralStringResource(R.plurals.moving_failed, moving.failed, moving.failed) else null
    null -> null
}

@Composable
private fun syncLine(sync: SyncState): String? = when (sync) {
    SyncState.Idle, SyncState.Running -> null
    SyncState.Unreachable -> stringResource(R.string.sync_unreachable)
    SyncState.SignedOut -> stringResource(R.string.sync_signed_out)
    is SyncState.Failed -> stringResource(R.string.sync_failed, sync.why)
}

/**
 * A row with the dotted rule under it, drawn over the foot of its padding so a row stands as tall
 * as it did without one.
 */
@Composable
private fun Ruled(ruled: Boolean, row: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        row()
        if (ruled) DottedRule(Modifier.align(Alignment.BottomStart), start = 20.dp, end = 20.dp)
    }
}

/** A folder in the list: its name, and how many notes are in it; or the way back up. */
@Composable
private fun FolderRow(name: String, line: String?, ruled: Boolean, onClick: () -> Unit) = Ruled(ruled) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        TextMMD(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (line != null) TextMMD(text = line, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun NoteRow(note: Note, isShared: Boolean, isPinned: Boolean, showFolder: Boolean, ruled: Boolean, onClick: () -> Unit) = Ruled(ruled) {
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
        val pinnedWord = stringResource(R.string.list_pinned)
        val line = listOfNotNull(
            pinnedWord.takeIf { isPinned },
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

/** The width the floating buttons take at the right edge: 56 dp and their margin. */
private val FAB_ROOM = 72.dp

/** One floating button's height and its margin: how far above the foot the scrollbar ends. */
internal val FAB_CLEAR = 72.dp

/** Room under the list for the three buttons that float over its foot; the page dots stop above it. */
