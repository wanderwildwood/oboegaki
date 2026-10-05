package com.wanderwildwood.oboegaki

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mudita.mmd.ThemeMMD
import com.wanderwildwood.oboegaki.notes.Keeping
import com.wanderwildwood.oboegaki.notes.Notes
import com.wanderwildwood.oboegaki.ui.AboutDialog
import com.wanderwildwood.oboegaki.ui.ListScreen
import com.wanderwildwood.oboegaki.ui.NoteScreen
import com.wanderwildwood.oboegaki.ui.SettingsScreen
import com.wanderwildwood.oboegaki.ui.SetupScreen
import com.wanderwildwood.oboegaki.ui.SignInScreen
import com.wanderwildwood.oboegaki.ui.monochrome

/** Something to be written down the moment the app opens: from "New note", or shared in. */
data class Capture(val title: String, val text: String)

private sealed interface Screen {
    data object List : Screen
    data class Note(val path: String, val text: String, val fresh: Boolean, val title: String = "") : Screen
    data object Settings : Screen
    data object SignIn : Screen
}

class MainActivity : ComponentActivity() {

    private val capture: MutableState<Capture?> = mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notes.init(this)
        if (savedInstanceState == null) capture.value = captureFrom(intent)
        setContent {
            ThemeMMD(colorScheme = monochrome) {
                App(capture)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        captureFrom(intent)?.let { capture.value = it }
    }

    override fun onResume() {
        super.onResume()
        Notes.refresh()
        Notes.syncNow()
    }

    /**
     * What the app was opened to take down, if anything: the "New note" entry on the home
     * screen, text shared from another app, or text selected in one and sent here.
     */
    private fun captureFrom(intent: Intent?): Capture? {
        intent ?: return null
        return when {
            intent.component?.className?.endsWith(".NewNote") == true -> Capture("", "")
            intent.action == Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
                Capture(intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty(), text)
            }
            intent.action == Intent.ACTION_PROCESS_TEXT -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() ?: return null
                Capture("", text)
            }
            else -> null
        }
    }
}

@Composable
private fun App(capture: MutableState<Capture?>) {
    val context = LocalContext.current
    val notes by Notes.list.collectAsStateWithLifecycle()
    val sync by Notes.sync.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf<Screen>(Screen.List) }
    var folder by remember { mutableStateOf("") }
    var aboutOpen by remember { mutableStateOf(false) }
    var keeping by remember { mutableStateOf(Notes.preferences.keeping) }

    val chooseFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            Notes.preferences.folder = uri
            Notes.preferences.keeping = Keeping.FOLDER
            keeping = Keeping.FOLDER
            folder = ""
            Notes.refresh()
        }
    }

    // A capture waits for somewhere to keep it, and then opens straight into a new note.
    val pending = capture.value
    if (pending != null && keeping != Keeping.NOWHERE) {
        capture.value = null
        screen = Screen.Note(
            path = Notes.newPath(folder, pending.title),
            text = pending.text,
            fresh = true,
            title = pending.title,
        )
    }

    if (keeping == Keeping.NOWHERE && screen != Screen.SignIn) {
        SetupScreen(
            onNextcloud = { screen = Screen.SignIn },
            onFolder = { chooseFolder.launch(null) },
            onAbout = { aboutOpen = true },
        )
    } else {
        when (val now = screen) {
            Screen.List -> {
                if (folder.isNotEmpty()) BackHandler { folder = folder.substringBeforeLast('/', "") }
                ListScreen(
                    folder = folder,
                    notes = notes,
                    sync = sync,
                    onOpen = { note ->
                        screen = Screen.Note(note.path, Notes.read(note.path).orEmpty(), fresh = false)
                    },
                    onFolder = { folder = it },
                    onUp = { folder = folder.substringBeforeLast('/', "") },
                    onNew = { screen = Screen.Note(Notes.newPath(folder), "", fresh = true) },
                    onSettings = { screen = Screen.Settings },
                    onAbout = { aboutOpen = true },
                )
            }
            is Screen.Note -> NoteScreen(
                // Keyed by path so a second capture while one is open starts a new screen.
                path = now.path,
                initialText = now.text,
                fresh = now.fresh,
                initialTitle = now.title,
                onClose = { screen = Screen.List },
            )
            Screen.Settings -> {
                BackHandler { screen = Screen.List }
                SettingsScreen(
                    sync = sync,
                    onChooseFolder = { chooseFolder.launch(null) },
                    onSignIn = { screen = Screen.SignIn },
                    onBack = {
                        keeping = Notes.preferences.keeping
                        screen = Screen.List
                    },
                )
            }
            Screen.SignIn -> {
                BackHandler { screen = if (keeping == Keeping.NOWHERE) Screen.List else Screen.Settings }
                SignInScreen(
                    onSignedIn = {
                        keeping = Keeping.NEXTCLOUD
                        folder = ""
                        screen = Screen.List
                    },
                    onBack = { screen = if (keeping == Keeping.NOWHERE) Screen.List else Screen.Settings },
                )
            }
        }
    }

    if (aboutOpen) AboutDialog(onDismiss = { aboutOpen = false })
}
