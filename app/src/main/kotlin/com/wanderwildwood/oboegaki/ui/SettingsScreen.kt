package com.wanderwildwood.oboegaki.ui

import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.width
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.switcher.SwitchMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.oboegaki.R
import com.wanderwildwood.oboegaki.remind.Reminders
import com.wanderwildwood.oboegaki.tasks.Tasks
import com.wanderwildwood.oboegaki.notes.Keeping
import com.wanderwildwood.oboegaki.notes.Notes
import com.wanderwildwood.oboegaki.notes.SyncApps
import com.wanderwildwood.oboegaki.notes.SyncState
import com.wanderwildwood.oboegaki.notes.cleanFolder
import com.wanderwildwood.oboegaki.notes.folders
import com.wanderwildwood.oboegaki.glance.GlanceProvider
import com.wanderwildwood.oboegaki.hearing.Speech
import com.wanderwildwood.oboegaki.hearing.Voice
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.wanderwildwood.oboegaki.sync.CheckResult
import com.wanderwildwood.oboegaki.sync.LoginFlow
import com.wanderwildwood.oboegaki.sync.NextcloudCheck
import com.wanderwildwood.oboegaki.sync.Problem
import com.wanderwildwood.oboegaki.sync.PlainHttpRefused
import com.wanderwildwood.oboegaki.sync.Refused
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Where the notes are kept, how to reach them from Obsidian, and the lock screen.
 *
 * Each place has its own short list. Leaving one goes last and asks first, because it is the
 * row that loses something: for a server, this phone's copy, and for a folder, the grant.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    sync: SyncState,
    onMove: (to: Keeping, bring: Boolean) -> Unit,
    onSignIn: () -> Unit,
    onWebDav: () -> Unit,
    onObsidian: () -> Unit,
    onAbout: () -> Unit,
    onBack: () -> Unit,
) {
    val preferences = Notes.preferences
    val context = LocalContext.current
    var renaming by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var choosingFolder by remember { mutableStateOf(false) }
    var choosingSpeech by remember { mutableStateOf(false) }
    var choosingTasks by remember { mutableStateOf(false) }
    var choosingSize by remember { mutableStateOf(false) }
    var turning by remember { mutableStateOf(preferences.turnWithPhone) }
    val taskLists = remember { Tasks.writableLists() }
    var tasksIn by remember { mutableStateOf(Tasks.defaultList().id) }
    val speech by Speech.state.collectAsState()
    val spoken by Speech.chosen.collectAsState()
    var newFolder by remember { mutableStateOf(preferences.newFolder) }
    var duraSpeedDone by remember { mutableStateOf(preferences.duraSpeedDone) }
    val carriers = remember { SyncApps.carriers(context) }
    val hasDuraSpeed = remember { SyncApps.hasDuraSpeed(context) }
    val hasReminders = remember { Reminders.load(context).isNotEmpty() }
    // Read again on every return from the phone's settings, where it is changed.
    var checks by remember { mutableStateOf(0) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        checks++
        onPauseOrDispose { }
    }
    val fullScreenOn = remember(checks) {
        android.os.Build.VERSION.SDK_INT < 34 ||
            context.getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()
    }
    val keptFolder = remember(preferences.folder) { folderName(preferences.folder, context.contentResolver) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.settings_title)) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onBack) },
                actions = { BarButton(Icons.Info, stringResource(R.string.cd_about), onAbout) },
            )
        },
    ) { contentPadding ->
        PagedColumn(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            item {
                SettingRow(
                    stringResource(R.string.settings_kept),
                    when (preferences.keeping) {
                        Keeping.NEXTCLOUD -> stringResource(R.string.setup_nextcloud)
                        Keeping.WEBDAV -> stringResource(R.string.settings_kept_webdav, hostOf(preferences.dav?.address))
                        Keeping.FOLDER -> stringResource(R.string.settings_kept_folder, keptFolder)
                        Keeping.NOWHERE -> ""
                    },
                ) { choosing = true }
            }
            item {
                SettingRow(
                    stringResource(R.string.settings_new_in),
                    newFolder.ifEmpty { stringResource(R.string.settings_new_top) },
                ) { choosingFolder = true }
            }
            // Where new tasks go, once there is more than one list they could go in.
            if (taskLists.size > 1) item {
                SettingRow(
                    stringResource(R.string.settings_tasks_in),
                    taskLists.firstOrNull { it.id == tasksIn }?.name ?: Tasks.defaultList().name,
                ) { choosingTasks = true }
            }
            item {
                SettingRow(stringResource(R.string.obsidian_title), "", onClick = onObsidian)
            }
            item {
                var on by remember { mutableStateOf(preferences.lockScreen) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            on = !on
                            preferences.lockScreen = on
                            GlanceProvider.changed(context)
                        }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        TextMMD(text = stringResource(R.string.settings_lock_screen), style = MaterialTheme.typography.bodyLarge)
                        TextMMD(text = stringResource(R.string.settings_lock_screen_what), style = MaterialTheme.typography.labelSmall)
                        val presence = remember { SyncApps.glanceStatus(context) }
                        val pinned = remember { runCatching { Notes.pinnedNow().size }.getOrDefault(0) }
                        TextMMD(
                            text = when {
                                presence == SyncApps.Presence.MISSING -> stringResource(R.string.glance_missing)
                                presence == SyncApps.Presence.DISABLED -> stringResource(R.string.glance_disabled)
                                !on -> stringResource(R.string.glance_ready_off)
                                else -> pluralStringResource(R.plurals.glance_ready_pinned, pinned, pinned)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    SwitchMMD(checked = on, onCheckedChange = null)
                }
            }
            item {
                SettingRow(
                    stringResource(R.string.speech_title),
                    when (val now = speech) {
                        is Speech.State.Downloading -> stringResource(R.string.speech_downloading, Speech.named(now.language), now.percent)
                        is Speech.State.Failed -> stringResource(R.string.speech_failed, Speech.named(now.language))
                        Speech.State.Idle -> Speech.named(spoken)
                    },
                ) {
                    val now = speech
                    if (now is Speech.State.Failed) Speech.choose(context, now.language) else choosingSpeech = true
                }
            }
            item {
                var full by remember { mutableStateOf(Voice.full(context)) }
                SettingRow(
                    stringResource(R.string.quality_title),
                    stringResource(if (full) R.string.quality_full else R.string.quality_speech),
                ) {
                    full = !full
                    Voice.setFull(context, full)
                }
            }
            when (preferences.keeping) {
                Keeping.NEXTCLOUD -> {
                    val account = preferences.account
                    item {
                        SettingRow(
                            stringResource(R.string.settings_account),
                            account?.let { stringResource(R.string.settings_account_value, it.user, Uri.parse(it.server).host ?: it.server) }
                                ?: stringResource(R.string.settings_signed_out),
                            onClick = onSignIn,
                        )
                    }
                    item {
                        SettingRow(stringResource(R.string.settings_remote_folder), preferences.remoteFolder.ifEmpty { "/" }) { renaming = true }
                    }
                    item {
                        SettingRow(stringResource(R.string.settings_sync_now), syncValue(sync, preferences.lastSync)) { Notes.syncNow() }
                    }
                    item {
                        Leave(stringResource(R.string.settings_sign_out), stringResource(R.string.settings_sign_out_confirm)) {
                            Notes.signOut()
                            onBack()
                        }
                    }
                }
                Keeping.WEBDAV -> {
                    val dav = preferences.dav
                    item {
                        SettingRow(
                            stringResource(R.string.settings_server),
                            dav?.let { stringResource(R.string.settings_account_value, it.user, hostOf(it.address)) }
                                ?: stringResource(R.string.settings_signed_out),
                            onClick = onWebDav,
                        )
                    }
                    item {
                        SettingRow(stringResource(R.string.settings_dav_folder), preferences.davFolder.ifEmpty { "/" }) { renaming = true }
                    }
                    item {
                        SettingRow(stringResource(R.string.settings_sync_now), syncValue(sync, preferences.lastSync)) { Notes.syncNow() }
                    }
                    item {
                        Leave(stringResource(R.string.settings_forget), stringResource(R.string.settings_forget_confirm)) {
                            Notes.signOut()
                            onBack()
                        }
                    }
                }
                Keeping.FOLDER -> {
                    // A note made on the computer reaches the folder only while the app that
                    // carries it is running, so these are about that app, not Notes.
                    val syncthing = SyncApps.syncthingFork(context)
                    if (syncthing != null) {
                        item {
                            var on by remember { mutableStateOf(preferences.wakeSyncthing) }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        on = !on
                                        preferences.wakeSyncthing = on
                                        if (on) SyncApps.wakeSyncthing(context)
                                    }
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    TextMMD(text = stringResource(R.string.settings_wake_syncthing), style = MaterialTheme.typography.bodyLarge)
                                    TextMMD(text = stringResource(R.string.settings_wake_syncthing_note), style = MaterialTheme.typography.labelSmall)
                                }
                                Spacer(Modifier.width(16.dp))
                                SwitchMMD(checked = on, onCheckedChange = null)
                            }
                        }
                    }
                }
                Keeping.NOWHERE -> Unit
            }
            item {
                SettingRow(
                    stringResource(R.string.text_size_title),
                    stringResource(when (TextSize.level.intValue) { 0 -> R.string.text_size_small; 2 -> R.string.text_size_large; else -> R.string.text_size_medium }),
                ) { choosingSize = true }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            turning = !turning
                            preferences.turnWithPhone = turning
                            (context as? com.wanderwildwood.oboegaki.MainActivity)?.turningChanged()
                        }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        TextMMD(text = stringResource(R.string.turn_title), style = MaterialTheme.typography.bodyLarge)
                        TextMMD(text = stringResource(R.string.turn_note), style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.width(16.dp))
                    SwitchMMD(checked = turning, onCheckedChange = null)
                }
            }
            desktopSyncRows()
            // Android 14 on takes the full screen away from an app installed from a file.
            if (hasReminders && !fullScreenOn) {
                item {
                    SettingRow(stringResource(R.string.remind_full_screen), stringResource(R.string.remind_allow_full_screen)) {
                        runCatching {
                            context.startActivity(
                                Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, android.net.Uri.parse("package:" + context.packageName))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                }
            }
            // One row for everything DuraSpeed can stop: Notes, when reminders are set here,
            // and the apps carrying a folder of notes. Its list cannot be read, so the second
            // row is how the reader says the apps are on it; the row then folds away.
            val folderCarriers = if (preferences.keeping == Keeping.FOLDER) carriers else emptyList()
            if (hasDuraSpeed && !duraSpeedDone && (folderCarriers.isNotEmpty() || hasReminders)) {
                item {
                    val named = when (folderCarriers.size) {
                        0 -> ""
                        1 -> folderCarriers[0]
                        else -> stringResource(R.string.settings_duraspeed_and, folderCarriers.dropLast(1).joinToString(", "), folderCarriers.last())
                    }
                    val text = when {
                        !hasReminders -> stringResource(R.string.settings_duraspeed, named)
                        named.isEmpty() -> stringResource(R.string.settings_duraspeed_reminders)
                        else -> stringResource(R.string.settings_duraspeed_both, named)
                    }
                    SettingRow(text, stringResource(R.string.settings_duraspeed_open)) {
                        runCatching { context.startActivity(SyncApps.duraSpeedInfo().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }
                item {
                    SettingRow(stringResource(R.string.settings_duraspeed_done), "") {
                        preferences.duraSpeedDone = true
                        duraSpeedDone = true
                    }
                }
            }
        }
    }

    if (choosing) {
        KeptDialog(
            onDone = { to, bring ->
                choosing = false
                onMove(to, bring)
            },
            onDismiss = { choosing = false },
        )
    }

    if (choosingSize) {
        EInkDialog(onDismiss = { choosingSize = false }) {
            TextMMD(text = stringResource(R.string.text_size_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            listOf(R.string.text_size_small, R.string.text_size_medium, R.string.text_size_large).forEachIndexed { i, label ->
                TextMMD(
                    text = stringResource(label),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (i == TextSize.level.intValue) FontWeight.Bold else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { TextSize.choose(preferences, i); choosingSize = false }
                        .heightIn(min = 48.dp)
                        .padding(vertical = 12.dp),
                )
            }
        }
    }
    if (choosingTasks) {
        EInkDialog(onDismiss = { choosingTasks = false }) {
            TextMMD(text = stringResource(R.string.settings_tasks_in), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            for (l in taskLists) {
                TextMMD(
                    text = l.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (l.id == tasksIn) FontWeight.Bold else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            preferences.tasksList = l.id
                            tasksIn = l.id
                            choosingTasks = false
                        }
                        .heightIn(min = 48.dp)
                        .padding(vertical = 12.dp),
                )
            }
        }
    }
    if (choosingSpeech) {
        SpeechDialog(
            current = (speech as? Speech.State.Downloading)?.language ?: spoken,
            onDone = { language ->
                choosingSpeech = false
                if (language != null) Speech.choose(context, language)
            },
        )
    }

    if (choosingFolder) {
        val notes by Notes.list.collectAsState()
        FolderDialog(
            heading = stringResource(R.string.settings_new_in),
            note = stringResource(R.string.settings_new_note),
            current = newFolder,
            folders = folders(notes),
            useLabel = stringResource(R.string.settings_new_use),
            onDone = { folder ->
                choosingFolder = false
                if (folder != null) {
                    newFolder = folder
                    preferences.newFolder = folder
                }
            },
        )
    }

    if (renaming) {
        val onWebDav = preferences.keeping == Keeping.WEBDAV
        RemoteFolderDialog(
            current = Notes.remoteFolder,
            title = stringResource(if (onWebDav) R.string.settings_dav_folder else R.string.settings_remote_folder),
            note = stringResource(if (onWebDav) R.string.dav_folder_note else R.string.settings_remote_folder_note),
            onDone = { folder ->
                renaming = false
                if (folder != null && folder != Notes.remoteFolder) Notes.changeRemoteFolder(folder)
            },
        )
    }
}

@Composable
private fun syncValue(sync: SyncState, last: Long): String = when (sync) {
    SyncState.Running -> stringResource(R.string.settings_syncing)
    SyncState.Unreachable -> stringResource(R.string.sync_unreachable)
    SyncState.SignedOut -> stringResource(R.string.sync_signed_out)
    is SyncState.Failed -> stringResource(R.string.sync_failed, sync.why)
    SyncState.Idle -> if (last == 0L) {
        stringResource(R.string.settings_never_synced)
    } else {
        stringResource(R.string.settings_synced_at, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(last)))
    }
}

/** "app.koofr.net" for a server's address, or the address itself if it has no host. */
fun hostOf(address: String?): String {
    if (address == null) return ""
    return Uri.parse(address).host ?: address
}

/**
 * "Notes" for content://…/tree/primary%3ANotes: what the reader would call it. Another app's
 * folder, a WebDAV mount or a server share, has ids that are no kind of name, so it is asked
 * for the folder's own name.
 */
fun folderName(uri: Uri?, resolver: android.content.ContentResolver): String {
    if (uri == null) return ""
    val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return uri.toString()
    if (uri.authority == "com.android.externalstorage.documents") {
        return id.substringAfter(':').ifEmpty { id }.substringAfterLast('/')
    }
    val named = runCatching {
        resolver.query(
            DocumentsContract.buildDocumentUriUsingTree(uri, id),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()
    return named ?: id
}

@Composable
internal fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        TextMMD(text = label, style = MaterialTheme.typography.bodyLarge)
        if (value.isNotEmpty()) TextMMD(text = value, style = MaterialTheme.typography.labelSmall)
    }
}

/** A row that asks first, in its own face: "<the action> — tap again". */
@Composable
internal fun Leave(label: String, confirm: String, onConfirm: () -> Unit) {
    val armed = rememberArmed()
    TextMMD(
        text = if (armed.value) confirm else label,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = if (armed.value) FontWeight.Bold else null,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (armed.value) onConfirm() else armed.value = true }
            .padding(horizontal = 20.dp, vertical = 16.dp),
    )
}

/**
 * Somewhere else to keep the notes: the same three ways as the first screen, and whether the
 * notes come along. They are copied, so the place they leave keeps them all.
 */
@Composable
private fun KeptDialog(onDone: (to: Keeping, bring: Boolean) -> Unit, onDismiss: () -> Unit) {
    var bring by remember { mutableStateOf(true) }
    EInkDialog(onDismiss = onDismiss) {
        // Pages by whole rows, so on its side, where the dialog is taller than the window, the
        // last choices are a page turn away rather than cut off.
        PagedColumn(modifier = Modifier.fillMaxWidth().heightIn(max = dialogListHeightDp(MAX_KEPT_DP, rememberWindowHeightDp()).dp)) {
            item {
                TextMMD(text = stringResource(R.string.setup_where), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(16.dp))
                ButtonMMD(onClick = { onDone(Keeping.NEXTCLOUD, bring) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    TextMMD(text = stringResource(R.string.setup_nextcloud))
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                OutlinedButtonMMD(onClick = { onDone(Keeping.WEBDAV, bring) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    TextMMD(text = stringResource(R.string.setup_webdav))
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                OutlinedButtonMMD(onClick = { onDone(Keeping.FOLDER, bring) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    TextMMD(text = stringResource(R.string.setup_folder))
                }
                Spacer(Modifier.height(12.dp))
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { bring = !bring }
                        .heightIn(min = 48.dp)
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextMMD(
                        text = stringResource(R.string.settings_bring),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(16.dp))
                    SwitchMMD(checked = bring, onCheckedChange = null)
                }
                TextMMD(text = stringResource(R.string.settings_bring_note), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private const val MAX_KEPT_DP = 400

/**
 * The language voice notes are heard in. A language that needs the download asks first, with
 * its size, since it goes over whatever connection the phone has.
 */
@Composable
private fun SpeechDialog(current: String, onDone: (String?) -> Unit) {
    var asking by remember { mutableStateOf<Speech.Language?>(null) }
    EInkDialog(onDismiss = { onDone(null) }) {
        val ask = asking
        if (ask == null) {
            TextMMD(text = stringResource(R.string.speech_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            TextMMD(text = stringResource(R.string.speech_note), style = MaterialTheme.typography.labelSmall)
            PagedColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                for (language in Speech.languages) {
                    item(key = language.code) {
                        FolderChoice(language.name, language.code == current) {
                            if (language.code == "en" || Speech.downloaded()) onDone(language.code) else asking = language
                        }
                    }
                }
            }
        } else {
            TextMMD(text = stringResource(R.string.speech_ask, ask.name), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            TextMMD(text = stringResource(R.string.speech_note), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(16.dp))
            ButtonMMD(onClick = { onDone(ask.code) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                TextMMD(text = stringResource(R.string.speech_download))
            }
        }
    }
}

/**
 * A folder: the top, one the notes already have, or a new one typed in, which is made with the
 * first note put there. Where new notes go, in settings; where a note moves to, from the note.
 */
@Composable
internal fun FolderDialog(
    heading: String,
    note: String?,
    current: String,
    folders: List<String>,
    useLabel: String,
    onDone: (String?) -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    var refused by remember { mutableStateOf(false) }
    fun useTyped() {
        val clean = cleanFolder(typed)
        if (clean == null) refused = true else onDone(clean)
    }
    EInkDialog(onDismiss = { onDone(null) }) {
        TextMMD(text = heading, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        if (note != null) {
            Spacer(Modifier.height(4.dp))
            TextMMD(text = note, style = MaterialTheme.typography.labelSmall)
        }
        PagedColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
            item { FolderChoice(stringResource(R.string.settings_new_top), current.isEmpty()) { onDone("") } }
            for (folder in folders) {
                item(key = folder) { FolderChoice(folder, folder == current) { onDone(folder) } }
            }
        }
        Spacer(Modifier.height(8.dp))
        TextFieldMMD(
            value = typed,
            onValueChange = {
                typed = it.replace("\n", "")
                refused = false
            },
            modifier = Modifier.fillMaxWidth(),
            label = { TextMMD(text = stringResource(R.string.settings_new_type)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { useTyped() }),
        )
        if (refused) {
            Spacer(Modifier.height(4.dp))
            TextMMD(text = stringResource(R.string.settings_new_bad), style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(12.dp))
        ButtonMMD(onClick = { useTyped() }, enabled = typed.isNotBlank(), modifier = Modifier.fillMaxWidth().height(48.dp)) {
            TextMMD(text = useLabel)
        }
    }
}

@Composable
internal fun FolderChoice(label: String, chosen: Boolean, onClick: () -> Unit) {
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
private fun RemoteFolderDialog(current: String, title: String, note: String, onDone: (String?) -> Unit) {
    var typed by remember { mutableStateOf(current) }
    EInkDialog(onDismiss = { onDone(null) }) {
        TextMMD(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(12.dp))
        TextFieldMMD(
            value = typed,
            onValueChange = { typed = it.replace("\n", "") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone(typed.trim('/', ' ')) }),
        )
        Spacer(Modifier.height(8.dp))
        TextMMD(text = note, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(16.dp))
        ButtonMMD(onClick = { onDone(typed.trim('/', ' ')) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            TextMMD(text = stringResource(R.string.settings_remote_folder_use))
        }
    }
}

/**
 * The first screen, before there is anywhere to keep a note: three ways, one under another, and
 * a line on what each one means.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onNextcloud: () -> Unit, onWebDav: () -> Unit, onFolder: () -> Unit, onAbout: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.app_name)) },
                actions = { BarButton(Icons.Info, stringResource(R.string.cd_about), onAbout) },
            )
        },
    ) { contentPadding ->
        PagedColumn(modifier = Modifier.fillMaxSize().padding(contentPadding), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 12.dp)) {
            item {
                Spacer(Modifier.height(20.dp))
                TextMMD(text = stringResource(R.string.setup_where), style = MaterialTheme.typography.titleMedium)
            }
            item { SetupChoice(stringResource(R.string.setup_nextcloud), stringResource(R.string.setup_nextcloud_note), filled = true, onClick = onNextcloud) }
            item { SetupChoice(stringResource(R.string.setup_webdav), stringResource(R.string.setup_webdav_note), filled = false, onClick = onWebDav) }
            item { SetupChoice(stringResource(R.string.setup_folder), stringResource(R.string.setup_folder_note), filled = false, onClick = onFolder) }
        }
    }
}

/** One way to keep notes on the first screen: its button, and a line on what it means. */
@Composable
private fun SetupChoice(label: String, note: String, filled: Boolean, onClick: () -> Unit) {
    Column {
        Spacer(Modifier.height(20.dp))
        if (filled) {
            ButtonMMD(onClick = onClick, modifier = Modifier.fillMaxWidth().height(56.dp)) { TextMMD(text = label) }
        } else {
            OutlinedButtonMMD(onClick = onClick, modifier = Modifier.fillMaxWidth().height(56.dp)) { TextMMD(text = label) }
        }
        Spacer(Modifier.height(8.dp))
        TextMMD(text = note, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Signing in to a Nextcloud. The first choice is the typed one: the server address, the user
 * name and an app password made in Nextcloud's security settings. Test asks the server and says
 * in plain words what is wrong; Sign in does the same and keeps the account when it is good.
 * The browser route stays below, as the second choice: only the address is typed, the server's
 * own login page opens, and this screen asks every two seconds whether the reader has finished
 * (twenty minutes at most, and leaving the screen stops it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInScreen(onSignedIn: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val known = Notes.preferences.account
    var address by remember { mutableStateOf(known?.server.orEmpty()) }
    var user by remember { mutableStateOf(known?.user.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var waiting by remember { mutableStateOf(false) }
    var loginAddress by remember { mutableStateOf<String?>(null) }

    fun typed(keep: Boolean) {
        busy = true
        loginAddress = null
        status = R.string.signin_opening
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                NextcloudCheck.run(address, user, password, LoginFlow::plainHttpAllowed)
            }
            busy = false
            when (result) {
                is CheckResult.Good -> if (keep) {
                    Notes.preferences.account = result.account
                    Notes.preferences.keeping = Keeping.NEXTCLOUD
                    Notes.syncNow()
                    onSignedIn()
                } else {
                    status = R.string.signin_good
                }
                is CheckResult.Bad -> status = when (result.problem) {
                    Problem.BAD_ADDRESS -> R.string.signin_bad_address
                    Problem.PLAIN_HTTP -> R.string.signin_plain_http
                    Problem.NOT_FOUND -> R.string.signin_not_found
                    Problem.UNREACHABLE -> R.string.signin_unreachable
                    Problem.CERTIFICATE -> R.string.signin_certificate
                    Problem.NOT_NEXTCLOUD -> R.string.signin_not_nextcloud
                    Problem.WRONG_PASSWORD -> R.string.signin_wrong_password
                    Problem.OTHER -> R.string.signin_other
                }
            }
        }
    }

    fun browser() {
        waiting = true
        status = R.string.signin_opening
        scope.launch {
            val started = runCatching { withContext(Dispatchers.IO) { LoginFlow.start(address) } }
            val flow = started.getOrNull()
            if (flow == null) {
                status = when (started.exceptionOrNull()) {
                    is PlainHttpRefused -> R.string.signin_plain_http
                    is Refused -> R.string.signin_not_nextcloud
                    else -> R.string.signin_unreachable
                }
                waiting = false
                return@launch
            }
            if (hasBrowser(context, flow.loginUrl)) {
                openLink(context, flow.loginUrl, context.getString(R.string.about_no_browser_address, flow.loginUrl))
                status = R.string.signin_waiting
            } else {
                // Nextcloud's sign-in finishes on the server, so any device with a browser can do it.
                loginAddress = flow.loginUrl
                status = R.string.signin_no_browser
            }
            val deadline = System.currentTimeMillis() + 20 * 60_000
            while (System.currentTimeMillis() < deadline) {
                delay(2000)
                val account = runCatching { withContext(Dispatchers.IO) { LoginFlow.poll(flow) } }.getOrNull()
                if (account != null) {
                    Notes.preferences.account = account
                    Notes.preferences.keeping = Keeping.NEXTCLOUD
                    Notes.syncNow()
                    onSignedIn()
                    return@launch
                }
            }
            status = R.string.signin_gave_up
            waiting = false
        }
    }

    val working = busy || waiting
    val ready = address.isNotBlank() && user.isNotBlank() && password.isNotEmpty() && !working

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.signin_title)) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onBack) },
            )
        },
    ) { contentPadding ->
        PagedColumn(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 12.dp),
        ) {
            item {
                Column {
                    Spacer(Modifier.height(16.dp))
                    TextFieldMMD(
                        value = address,
                        onValueChange = { address = it.replace("\n", "").trim() },
                        modifier = Modifier.fillMaxWidth(),
                        label = { TextMMD(text = stringResource(R.string.signin_address)) },
                        placeholder = { TextMMD(text = "cloud.example.org") },
                        singleLine = true,
                        enabled = !working,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                    )
                }
            }
            item {
                Column {
                    Spacer(Modifier.height(16.dp))
                    TextFieldMMD(
                        value = user,
                        onValueChange = { user = it.replace("\n", "") },
                        modifier = Modifier.fillMaxWidth(),
                        label = { TextMMD(text = stringResource(R.string.signin_user)) },
                        singleLine = true,
                        enabled = !working,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                    )
                }
            }
            item {
                Column {
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextFieldMMD(
                            value = password,
                            onValueChange = { password = it.replace("\n", "") },
                            modifier = Modifier.weight(1f),
                            label = { TextMMD(text = stringResource(R.string.signin_password)) },
                            singleLine = true,
                            enabled = !working,
                            visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                            keyboardActions = KeyboardActions(onGo = { if (ready) typed(keep = true) }),
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButtonMMD(
                            onClick = { shown = !shown },
                            modifier = Modifier.widthIn(min = 72.dp).height(48.dp),
                        ) {
                            TextMMD(
                                text = stringResource(if (shown) R.string.dav_hide else R.string.dav_show),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    TextMMD(text = stringResource(R.string.signin_password_note), style = MaterialTheme.typography.labelSmall)
                }
            }
            status?.let {
                item {
                    Column {
                        Spacer(Modifier.height(16.dp))
                        TextMMD(text = stringResource(it), style = MaterialTheme.typography.bodyMedium)
                        loginAddress?.let { link ->
                            Spacer(Modifier.height(12.dp))
                            TextMMD(text = link, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            item {
                Column {
                    Spacer(Modifier.height(20.dp))
                    ButtonMMD(
                        onClick = { typed(keep = true) },
                        enabled = ready,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        TextMMD(text = stringResource(R.string.signin_go))
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButtonMMD(
                        onClick = { typed(keep = false) },
                        enabled = ready,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        TextMMD(text = stringResource(R.string.signin_test))
                    }
                }
            }
            item {
                Column {
                    Spacer(Modifier.height(24.dp))
                    OutlinedButtonMMD(
                        onClick = { browser() },
                        enabled = address.isNotBlank() && !working,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        TextMMD(text = stringResource(R.string.signin_browser))
                    }
                    Spacer(Modifier.height(4.dp))
                    TextMMD(text = stringResource(R.string.signin_note), style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}
