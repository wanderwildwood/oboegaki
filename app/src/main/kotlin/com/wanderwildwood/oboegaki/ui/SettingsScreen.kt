package com.wanderwildwood.oboegaki.ui

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.switcher.SwitchMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.oboegaki.R
import com.wanderwildwood.oboegaki.remind.Reminders
import com.wanderwildwood.oboegaki.notes.Keeping
import com.wanderwildwood.oboegaki.notes.Notes
import com.wanderwildwood.oboegaki.notes.SyncApps
import com.wanderwildwood.oboegaki.notes.SyncState
import com.wanderwildwood.oboegaki.notes.cleanFolder
import com.wanderwildwood.oboegaki.notes.folders
import com.wanderwildwood.oboegaki.glance.GlanceProvider
import com.wanderwildwood.oboegaki.hearing.Speech
import com.wanderwildwood.oboegaki.sync.LoginFlow
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
    onBack: () -> Unit,
) {
    val preferences = Notes.preferences
    val context = LocalContext.current
    var renaming by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var choosingFolder by remember { mutableStateOf(false) }
    var choosingSpeech by remember { mutableStateOf(false) }
    val speech by Speech.state.collectAsState()
    val spoken by Speech.chosen.collectAsState()
    var newFolder by remember { mutableStateOf(preferences.newFolder) }
    var duraSpeedDone by remember { mutableStateOf(preferences.duraSpeedDone) }
    val carriers = remember { SyncApps.carriers(context) }
    val hasDuraSpeed = remember { SyncApps.hasDuraSpeed(context) }
    val hasReminders = remember { Reminders.load(context).isNotEmpty() }
    val keptFolder = remember(preferences.folder) { folderName(preferences.folder, context.contentResolver) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.settings_title)) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onBack) },
            )
        },
    ) { contentPadding ->
        LazyColumnMMD(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
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
                    TextMMD(
                        text = stringResource(R.string.settings_lock_screen),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    SwitchMMD(checked = on, onCheckedChange = null)
                }
                HorizontalDividerMMD()
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
                                SwitchMMD(checked = on, onCheckedChange = null)
                            }
                            HorizontalDividerMMD()
                        }
                    }
                }
                Keeping.NOWHERE -> Unit
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
        NewFolderDialog(
            current = newFolder,
            folders = folders(Notes.list.value),
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
private fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        TextMMD(text = label, style = MaterialTheme.typography.bodyLarge)
        if (value.isNotEmpty()) TextMMD(text = value, style = MaterialTheme.typography.labelSmall)
    }
    HorizontalDividerMMD()
}

/** A row that asks first, in its own face: "<the action> — tap again". */
@Composable
private fun Leave(label: String, confirm: String, onConfirm: () -> Unit) {
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
        TextMMD(text = stringResource(R.string.setup_where), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(16.dp))
        ButtonMMD(onClick = { onDone(Keeping.NEXTCLOUD, bring) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            TextMMD(text = stringResource(R.string.setup_nextcloud))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButtonMMD(onClick = { onDone(Keeping.WEBDAV, bring) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            TextMMD(text = stringResource(R.string.setup_webdav))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButtonMMD(onClick = { onDone(Keeping.FOLDER, bring) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            TextMMD(text = stringResource(R.string.setup_folder))
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { bring = !bring }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextMMD(
                text = stringResource(R.string.settings_bring),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            SwitchMMD(checked = bring, onCheckedChange = null)
        }
        TextMMD(text = stringResource(R.string.settings_bring_note), style = MaterialTheme.typography.labelSmall)
    }
}

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
            LazyColumnMMD(modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
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
 * Where new notes go: the top, a folder the notes already have, or a new one typed in, which is
 * made with the first note put there.
 */
@Composable
private fun NewFolderDialog(current: String, folders: List<String>, onDone: (String?) -> Unit) {
    var typed by remember { mutableStateOf("") }
    var refused by remember { mutableStateOf(false) }
    fun useTyped() {
        val clean = cleanFolder(typed)
        if (clean == null) refused = true else onDone(clean)
    }
    EInkDialog(onDismiss = { onDone(null) }) {
        TextMMD(text = stringResource(R.string.settings_new_in), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        TextMMD(text = stringResource(R.string.settings_new_note), style = MaterialTheme.typography.labelSmall)
        LazyColumnMMD(modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
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
            TextMMD(text = stringResource(R.string.settings_new_use))
        }
    }
}

@Composable
private fun FolderChoice(label: String, chosen: Boolean, onClick: () -> Unit) {
    TextMMD(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = if (chosen) FontWeight.Bold else null,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
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
        LazyColumnMMD(modifier = Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 20.dp)) {
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
 * Signing in to a Nextcloud through its own login page.
 *
 * The address is the only thing typed here. The browser does the rest, and this screen waits,
 * asking the server every two seconds whether the reader has finished. Twenty minutes is long
 * enough to find a password and short enough that a forgotten screen stops asking; leaving the
 * screen stops it sooner, since the asking belongs to the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInScreen(onSignedIn: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(Notes.preferences.account?.server.orEmpty()) }
    var status by remember { mutableStateOf<Int?>(null) }
    var waiting by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.signin_title)) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onBack) },
            )
        },
    ) { contentPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(contentPadding).padding(20.dp)) {
            TextFieldMMD(
                value = address,
                onValueChange = { address = it.replace("\n", "").trim() },
                modifier = Modifier.fillMaxWidth(),
                label = { TextMMD(text = stringResource(R.string.signin_address)) },
                placeholder = { TextMMD(text = "cloud.example.org") },
                singleLine = true,
                enabled = !waiting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            )
            Spacer(Modifier.height(20.dp))
            ButtonMMD(
                onClick = {
                    waiting = true
                    status = R.string.signin_opening
                    scope.launch {
                        val started = runCatching { withContext(Dispatchers.IO) { LoginFlow.start(address) } }
                        val flow = started.getOrNull()
                        if (flow == null) {
                            status = if (started.exceptionOrNull() is Refused) R.string.signin_not_nextcloud else R.string.signin_unreachable
                            waiting = false
                            return@launch
                        }
                        val opened = runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(flow.loginUrl)))
                        }.isSuccess
                        if (!opened) {
                            status = R.string.about_no_browser
                            waiting = false
                            return@launch
                        }
                        status = R.string.signin_waiting
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
                },
                enabled = address.isNotBlank() && !waiting,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                TextMMD(text = stringResource(R.string.signin_go))
            }
            status?.let {
                Spacer(Modifier.height(16.dp))
                TextMMD(text = stringResource(it), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(24.dp))
            TextMMD(text = stringResource(R.string.signin_note), style = MaterialTheme.typography.labelSmall)
        }
    }
}
