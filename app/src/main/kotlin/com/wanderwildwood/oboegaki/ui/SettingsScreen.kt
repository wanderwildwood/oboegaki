package com.wanderwildwood.oboegaki.ui

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.oboegaki.R
import com.wanderwildwood.oboegaki.notes.Keeping
import com.wanderwildwood.oboegaki.notes.Notes
import com.wanderwildwood.oboegaki.notes.SyncState
import com.wanderwildwood.oboegaki.sync.LoginFlow
import com.wanderwildwood.oboegaki.sync.Refused
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Where the notes are kept, and nothing else yet.
 *
 * Each place has its own short list. Leaving one goes last and asks first, because it is the
 * row that loses something: for Nextcloud, this phone's copy, and for a folder, the grant.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    sync: SyncState,
    onChooseFolder: () -> Unit,
    onSignIn: () -> Unit,
    onBack: () -> Unit,
) {
    val preferences = Notes.preferences
    var renaming by remember { mutableStateOf(false) }

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
            when (preferences.keeping) {
                Keeping.NEXTCLOUD -> {
                    val account = preferences.account
                    item {
                        Row(
                            stringResource(R.string.settings_account),
                            account?.let { stringResource(R.string.settings_account_value, it.user, Uri.parse(it.server).host ?: it.server) }
                                ?: stringResource(R.string.settings_signed_out),
                            onClick = onSignIn,
                        )
                    }
                    item {
                        Row(stringResource(R.string.settings_remote_folder), preferences.remoteFolder.ifEmpty { "/" }) { renaming = true }
                    }
                    item {
                        Row(stringResource(R.string.settings_sync_now), syncValue(sync, preferences.lastSync)) { Notes.syncNow() }
                    }
                    item {
                        Leave(stringResource(R.string.settings_sign_out), stringResource(R.string.settings_sign_out_confirm)) {
                            Notes.signOut()
                            onBack()
                        }
                    }
                }
                Keeping.FOLDER -> {
                    item {
                        Row(stringResource(R.string.settings_folder), folderName(preferences.folder), onClick = onChooseFolder)
                    }
                    item {
                        Leave(stringResource(R.string.settings_leave_folder), stringResource(R.string.settings_leave_folder_confirm)) {
                            preferences.keeping = Keeping.NOWHERE
                            Notes.refresh()
                            onBack()
                        }
                    }
                }
                Keeping.NOWHERE -> Unit
            }
        }
    }

    if (renaming) {
        RemoteFolderDialog(
            current = preferences.remoteFolder,
            onDone = { folder ->
                renaming = false
                if (folder != null && folder != preferences.remoteFolder) Notes.changeRemoteFolder(folder)
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

/** "Notes" for content://…/tree/primary%3ANotes: what the reader would call it. */
fun folderName(uri: Uri?): String {
    if (uri == null) return ""
    val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return uri.toString()
    return id.substringAfter(':').ifEmpty { id }.substringAfterLast('/')
}

@Composable
private fun Row(label: String, value: String, onClick: () -> Unit) {
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

@Composable
private fun RemoteFolderDialog(current: String, onDone: (String?) -> Unit) {
    var typed by remember { mutableStateOf(current) }
    EInkDialog(onDismiss = { onDone(null) }) {
        TextMMD(text = stringResource(R.string.settings_remote_folder), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
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
        TextMMD(text = stringResource(R.string.settings_remote_folder_note), style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(16.dp))
        ButtonMMD(onClick = { onDone(typed.trim('/', ' ')) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            TextMMD(text = stringResource(R.string.settings_remote_folder_use))
        }
    }
}

/**
 * The first screen, before there is anywhere to keep a note: two ways, side by side, and a line
 * on what each one means.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onNextcloud: () -> Unit, onFolder: () -> Unit, onAbout: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.app_name)) },
                actions = { BarButton(Icons.Info, stringResource(R.string.cd_about), onAbout) },
            )
        },
    ) { contentPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(contentPadding).padding(20.dp)) {
            TextMMD(text = stringResource(R.string.setup_where), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(20.dp))
            ButtonMMD(onClick = onNextcloud, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                TextMMD(text = stringResource(R.string.setup_nextcloud))
            }
            Spacer(Modifier.height(8.dp))
            TextMMD(text = stringResource(R.string.setup_nextcloud_note), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(24.dp))
            OutlinedButtonMMD(onClick = onFolder, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                TextMMD(text = stringResource(R.string.setup_folder))
            }
            Spacer(Modifier.height(8.dp))
            TextMMD(text = stringResource(R.string.setup_folder_note), style = MaterialTheme.typography.labelSmall)
        }
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
