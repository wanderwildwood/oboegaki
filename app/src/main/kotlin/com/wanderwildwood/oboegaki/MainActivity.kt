package com.wanderwildwood.oboegaki

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mudita.mmd.ThemeMMD
import com.wanderwildwood.oboegaki.notes.Keeping
import com.wanderwildwood.oboegaki.notes.Notes
import com.wanderwildwood.oboegaki.notes.sharedText
import com.wanderwildwood.oboegaki.ui.PicturesDialog
import com.wanderwildwood.oboegaki.ui.PicturesFailedDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wanderwildwood.oboegaki.ui.AboutDialog
import com.wanderwildwood.oboegaki.ui.ListScreen
import com.wanderwildwood.oboegaki.ui.NoteScreen
import com.wanderwildwood.oboegaki.ui.SettingsScreen
import com.wanderwildwood.oboegaki.ui.ShareScreen
import com.wanderwildwood.oboegaki.ui.RecordScreen
import com.wanderwildwood.oboegaki.ui.ScanScreen
import com.wanderwildwood.oboegaki.ui.OutsideScreen
import com.wanderwildwood.oboegaki.hearing.RecordService
import com.wanderwildwood.oboegaki.hearing.Voice
import com.wanderwildwood.oboegaki.ui.SetupScreen
import com.wanderwildwood.oboegaki.ui.SignInScreen
import com.wanderwildwood.oboegaki.ui.monochrome

/** Something to be written down the moment the app opens: from "New note", or shared in. */
data class Capture(
    val title: String,
    val text: String,
    val record: Boolean = false,
    val open: Uri? = null,
    /** Pictures shared in, to become a note that shows them or a scan. */
    val pictures: List<Uri> = emptyList(),
)

private sealed interface Screen {
    data object List : Screen
    data class Note(val path: String, val text: String, val fresh: Boolean, val title: String = "") : Screen
    data class Share(val path: String, val title: String) : Screen
    data object Settings : Screen
    data object SignIn : Screen
    data object Recording : Screen
    /** [pictures]: shared in to be scanned, one page each, rather than photographed. */
    data class Scan(val pictures: kotlin.collections.List<Uri> = emptyList()) : Screen
    data class Outside(val uri: Uri) : Screen
}

/** The actions of the New note and Record shortcuts (res/xml/shortcuts.xml). */
private const val NEW_NOTE = "com.wanderwildwood.oboegaki.NEW_NOTE"
private const val RECORD = "com.wanderwildwood.oboegaki.RECORD"

class MainActivity : ComponentActivity() {

    private val capture: MutableState<Capture?> = mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notes.init(this)
        Voice.init(this)
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
        // Anything left unheard because the app was closed partway is picked up on opening.
        Voice.catchUp(this)
    }

    /**
     * What the app was opened to take down, if anything: the New note or Record shortcut on
     * the icon, text shared from another app, or text selected in one and sent here.
     */
    private fun captureFrom(intent: Intent?): Capture? {
        intent ?: return null
        return when {
            intent.action == NEW_NOTE -> Capture("", "")
            intent.action == RECORD -> Capture("", "", record = true)
            intent.action == Intent.ACTION_VIEW && intent.data != null -> Capture("", "", open = intent.data)
            (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) &&
                intent.type?.startsWith("image/") == true -> {
                val pictures = picturesIn(intent)
                if (pictures.isEmpty()) null else Capture("", "", pictures = pictures)
            }
            intent.action == Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
                // An article or a page shared with its name becomes a Markdown link to it.
                val shared = sharedText(intent.getStringExtra(Intent.EXTRA_SUBJECT), text)
                Capture(shared.title, shared.text)
            }
            intent.action == Intent.ACTION_PROCESS_TEXT -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() ?: return null
                Capture("", text)
            }
            else -> null
        }
    }

    /** The pictures a share carries: one, several, or only in its clip for some senders. */
    private fun picturesIn(intent: Intent): List<Uri> {
        val out = mutableListOf<Uri>()
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { out += it }
        } else {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { out += it }
        }
        if (out.isEmpty()) {
            val clip = intent.clipData
            if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { out += it }
        }
        return out.distinct()
    }
}

@Composable
private fun App(capture: MutableState<Capture?>) {
    val context = LocalContext.current
    val notes by Notes.list.collectAsStateWithLifecycle()
    val sync by Notes.sync.collectAsStateWithLifecycle()
    val shared by Notes.shared.collectAsStateWithLifecycle()
    val pinned by Notes.pins.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf<Screen>(Screen.List) }
    var showing by remember { mutableStateOf(Notes.preferences.showing) }
    var order by remember { mutableStateOf(Notes.preferences.order) }
    var aboutOpen by remember { mutableStateOf(false) }
    // Pictures shared in, waiting to be made a note or a scan.
    var pictures by remember { mutableStateOf<List<Uri>?>(null) }
    var picturesFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var keeping by remember { mutableStateOf(Notes.preferences.keeping) }
    val recordingSeconds by Voice.recording.collectAsStateWithLifecycle()
    val made by Voice.made.collectAsStateWithLifecycle()

    fun record() {
        RecordService.start(context)
        screen = Screen.Recording
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) record()
    }
    fun recordAsking() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            record()
        } else {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // A recording just kept opens as its note.
    LaunchedEffect(made) {
        val path = made ?: return@LaunchedEffect
        Voice.opened()
        screen = Screen.Note(path, Notes.read(path).orEmpty(), fresh = false)
    }

    val chooseFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            Notes.preferences.folder = uri
            Notes.preferences.keeping = Keeping.FOLDER
            keeping = Keeping.FOLDER
            Notes.refresh()
        }
    }

    // A capture waits for somewhere to keep it, and then opens straight into a new note.
    val pending = capture.value
    if (pending?.open != null) {
        // A file to read needs nowhere to keep notes; keeping it does, and is offered only then.
        capture.value = null
        screen = Screen.Outside(pending.open)
    } else if (pending != null && pending.pictures.isNotEmpty() && keeping != Keeping.NOWHERE) {
        capture.value = null
        pictures = pending.pictures
    } else if (pending != null && pending.record && keeping != Keeping.NOWHERE) {
        capture.value = null
        recordAsking()
    } else if (pending != null && keeping != Keeping.NOWHERE) {
        capture.value = null
        screen = Screen.Note(
            path = Notes.newPath("", pending.title),
            text = pending.text,
            fresh = true,
            title = pending.title,
        )
    }

    if (keeping == Keeping.NOWHERE && screen != Screen.SignIn && screen !is Screen.Outside) {
        SetupScreen(
            onNextcloud = { screen = Screen.SignIn },
            onFolder = { chooseFolder.launch(null) },
            onAbout = { aboutOpen = true },
        )
    } else {
        when (val now = screen) {
            Screen.List -> ListScreen(
                notes = notes,
                shared = shared,
                pinned = pinned,
                canShare = keeping == Keeping.NEXTCLOUD,
                sync = sync,
                showing = showing,
                order = order,
                onView = { s, o ->
                    showing = s
                    order = o
                    Notes.preferences.showing = s
                    Notes.preferences.order = o
                },
                onOpen = { note ->
                    screen = Screen.Note(note.path, Notes.read(note.path).orEmpty(), fresh = false)
                },
                onNew = { folder -> screen = Screen.Note(Notes.newPath(folder), "", fresh = true) },
                onRecord = { recordAsking() },
                onScan = { screen = Screen.Scan() },
                onSettings = { screen = Screen.Settings },
                onAbout = { aboutOpen = true },
            )
            // Keyed by path so a second capture while one is open starts a new screen.
            is Screen.Note -> key(now.path) {
                NoteScreen(
                    path = now.path,
                    initialText = now.text,
                    fresh = now.fresh,
                    initialTitle = now.title,
                    onClose = { screen = Screen.List },
                    onShare = { path, title -> screen = Screen.Share(path, title) },
                )
            }
            is Screen.Outside -> OutsideScreen(
                uri = now.uri,
                canKeep = keeping != Keeping.NOWHERE,
                onKeep = { name, text ->
                    val path = Notes.newPath("", name.substringBeforeLast('.').ifEmpty { name })
                    Notes.shelf()?.write(path, text)
                    Notes.afterEdit()
                    screen = Screen.Note(path, text, fresh = false)
                },
                onClose = { screen = Screen.List },
            )
            is Screen.Scan -> ScanScreen(
                pictures = now.pictures,
                onDone = { pdf ->
                    val path = Notes.saveScan(pdf)
                    screen = if (path != null) Screen.Note(path, Notes.read(path).orEmpty(), fresh = false) else Screen.List
                },
                onCancel = { screen = Screen.List },
            )
            Screen.Recording -> RecordScreen(
                seconds = recordingSeconds,
                onStop = {
                    RecordService.stop(context)
                    screen = Screen.List
                },
            )
            is Screen.Share -> ShareScreen(
                path = now.path,
                title = now.title,
                onBack = { screen = Screen.Note(now.path, Notes.read(now.path).orEmpty(), fresh = false) },
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
                        screen = Screen.List
                    },
                    onBack = { screen = if (keeping == Keeping.NOWHERE) Screen.List else Screen.Settings },
                )
            }
        }
    }

    if (aboutOpen) AboutDialog(onDismiss = { aboutOpen = false })

    pictures?.let { shared ->
        PicturesDialog(
            count = shared.size,
            onNote = {
                pictures = null
                scope.launch {
                    val path = withContext(Dispatchers.IO) { Notes.savePictures(shared) }
                    if (path != null) screen = Screen.Note(path, Notes.read(path).orEmpty(), fresh = false) else picturesFailed = true
                }
            },
            onScan = {
                pictures = null
                screen = Screen.Scan(shared)
            },
            onDismiss = { pictures = null },
        )
    }
    if (picturesFailed) PicturesFailedDialog(onDismiss = { picturesFailed = false })
}
