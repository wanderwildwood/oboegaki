package com.wanderwildwood.oboegaki.ui

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.oboegaki.R
import com.wanderwildwood.oboegaki.notes.LockedContent
import com.wanderwildwood.oboegaki.notes.LockedNote
import com.wanderwildwood.oboegaki.notes.Locks
import com.wanderwildwood.oboegaki.notes.Note
import com.wanderwildwood.oboegaki.notes.Notes
import com.wanderwildwood.oboegaki.notes.fileNameFor
import com.wanderwildwood.oboegaki.notes.inFolder
import com.wanderwildwood.oboegaki.notes.pinShapeOk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * How a locked note stands in the list: a row of its own, called [title] ("Locked note") and
 * dated, never its own title or words. The path is no path a note can have.
 */
const val LOCKED_ROW = "\u0000locked:"

fun lockedRow(locked: LockedNote, title: String): Note = Note(
    LOCKED_ROW + locked.id,
    title,
    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(locked.modified)),
    locked.modified,
)

/** The locked note a list row stands for, or null for an ordinary note. */
fun lockedId(path: String): String? = path.removePrefix(LOCKED_ROW).takeIf { path.startsWith(LOCKED_ROW) }

/** The window not shown in Recents' picture or in a screenshot while a locked note is open. */
@Composable
private fun SecureWindow() {
    val activity = LocalContext.current as? Activity
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/**
 * A locked note, open. It is written in as any note is, and kept sealed a moment after the typing
 * stops. It closes, back to the list, when it is left by any door: Back, Home, the screen going
 * off. Then the key is forgotten, and the PIN opens it again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockedNoteScreen(
    id: String,
    opened: LockedContent,
    key: ByteArray,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(opened.title) }
    var body by remember { mutableStateOf(TextFieldValue(opened.text, TextRange(opened.text.length))) }
    var kept by remember { mutableStateOf(opened) }
    var gone by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    val armed = rememberArmed()
    SecureWindow()

    fun current(): LockedContent {
        val folder = kept.path.substringBeforeLast('/', "")
        val ext = kept.path.substringAfterLast('.', "md")
        val name = fileNameFor(title).ifEmpty { kept.title }
        return LockedContent(inFolder(folder, "$name.$ext"), body.text)
    }

    fun trySave(): Boolean {
        if (gone) return true
        val now = current()
        if (now == kept) return true
        return runCatching { Locks.store.write(id, key, now) }
            .onSuccess {
                kept = now
                Locks.reload()
            }.isSuccess
    }

    LaunchedEffect(body.text, title) {
        delay(1500)
        if (!trySave()) said = context.getString(R.string.note_not_saved)
    }

    // Left by any door, it closes: what is open stays open only while it is in front.
    val saveNow by rememberUpdatedState(::trySave)
    val closeNow by rememberUpdatedState(onClose)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                saveNow()
                closeNow()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            saveNow()
            key.fill(0)
        }
    }

    val close = {
        if (trySave()) onClose() else said = context.getString(R.string.note_not_saved)
    }
    BackHandler(onBack = close)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = {},
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), close) },
                actions = {
                    if (armed.value) {
                        TextMMD(
                            text = stringResource(R.string.locked_delete_confirm),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable {
                                    gone = true
                                    runCatching { Locks.store.delete(id) }
                                    Locks.reload()
                                    onClose()
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                        )
                    } else {
                        BarButton(Icons.Delete, stringResource(R.string.cd_delete)) { armed.value = true }
                        BarButton(Icons.More, stringResource(R.string.cd_more)) { menuOpen = true }
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp)) {
            TextFieldMMD(
                value = title,
                onValueChange = { title = it.replace("\n", "") },
                modifier = Modifier.fillMaxWidth().textActions(),
                textStyle = MaterialTheme.typography.titleLarge,
                placeholder = { TextMMD(text = stringResource(R.string.note_title_hint), style = MaterialTheme.typography.titleLarge) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
            )
            TextMMD(
                text = stringResource(R.string.locked_open_note),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 2.dp),
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = body,
                onValueChange = { body = it },
                modifier = Modifier.fillMaxSize().textActions(body) { body = it },
                textStyle = MaterialTheme.typography.bodyLarge,
                placeholder = { TextMMD(text = stringResource(R.string.note_body_hint), style = MaterialTheme.typography.bodyLarge) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedIndicatorColor = MaterialTheme.colorScheme.surface,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.surface,
                ),
            )
        }
    }

    if (menuOpen) {
        EInkDialog(onDismiss = { menuOpen = false }) {
            TextMMD(
                text = stringResource(R.string.lock_unlock),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                    menuOpen = false
                    if (!trySave()) {
                        said = context.getString(R.string.note_not_saved)
                    } else {
                        scope.launch {
                            val back = withContext(Dispatchers.IO) { runCatching { Notes.unlock(id, key) }.getOrNull() }
                            if (back != null) {
                                gone = true
                                onClose()
                            } else {
                                said = context.getString(R.string.lock_unlock_failed)
                            }
                        }
                    }
                }.padding(vertical = 12.dp),
            )
            TextMMD(text = stringResource(R.string.lock_unlock_what), style = MaterialTheme.typography.labelSmall)
        }
    }
    said?.let { words -> SaidDialog(words) { said = null } }
}

/** A few words that stay until Close is pressed: a Toast's seconds can pass unread on this panel. */
@Composable
fun SaidDialog(words: String, onDismiss: () -> Unit) {
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = words, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        OutlinedButtonMMD(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            TextMMD(text = stringResource(R.string.about_close), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A PIN field: digits only, shown as dots. */
@Composable
private fun PinField(value: String, onValue: (String) -> Unit, hint: String, modifier: Modifier = Modifier, onDone: (() -> Unit)? = null) {
    TextFieldMMD(
        value = value,
        onValueChange = { typed -> onValue(typed.filter { it in '0'..'9' }.take(com.wanderwildwood.oboegaki.notes.PIN_MAX)) },
        modifier = modifier.fillMaxWidth(),
        placeholder = { TextMMD(text = hint) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next,
            autoCorrectEnabled = false,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
    )
}

/** The first field of a dialog takes the focus and brings the keyboard up as the dialog opens. */
@Composable
private fun rememberFirstField(): FocusRequester {
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { field.requestFocus() }
        keyboard?.show()
    }
    return field
}

/** What a wrong or waiting PIN says. */
@Composable
private fun triedWords(tried: Locks.Tried): String? = when (tried) {
    is Locks.Tried.Right -> null
    Locks.Tried.Wrong -> stringResource(R.string.lock_wrong)
    is Locks.Tried.Wait -> {
        val s = ((tried.ms + 999) / 1000).toInt()
        pluralStringResource(R.plurals.lock_wait, s, s)
    }
    Locks.Tried.Broken -> stringResource(R.string.lock_broken)
}

/**
 * The PIN asked for, to open, lock or unlock: [heading] says which, [action] is the button.
 * [onRight] is given the note key once the PIN is right and returns what to say, or null when it
 * is done and the dialog closes. The key is the caller's to keep or wipe.
 */
@Composable
fun PinDialog(heading: String, action: String, onRight: suspend (ByteArray) -> String?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var words by remember { mutableStateOf<Locks.Tried?>(null) }
    var said by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val field = rememberFirstField()
    fun go() {
        if (working || pin.isEmpty()) return
        working = true
        val typed = pin
        scope.launch {
            val tried = withContext(Dispatchers.Default) { Locks.tryPin(typed) }
            pin = ""
            if (tried is Locks.Tried.Right) {
                words = null
                said = onRight(tried.key)
                if (said == null) onDismiss()
            } else {
                words = tried
            }
            working = false
        }
    }
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = heading, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(12.dp))
        PinField(pin, { pin = it; words = null }, stringResource(R.string.lock_pin_hint), Modifier.focusRequester(field), onDone = ::go)
        val line = said ?: words?.let { triedWords(it) } ?: if (working) stringResource(R.string.lock_checking) else null
        if (line != null) {
            Spacer(Modifier.height(8.dp))
            TextMMD(text = line, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(16.dp))
        ButtonMMD(onClick = ::go, modifier = Modifier.fillMaxWidth().height(48.dp)) { TextMMD(text = action) }
    }
}

/**
 * The first lock: what a PIN means said plainly first, a forgotten PIN above all, and then the
 * PIN typed twice. [onSet] is given the new note key and returns what to say, or null when done.
 */
@Composable
fun SetPinDialog(onSet: suspend (ByteArray) -> String?, onDismiss: () -> Unit) {
    var read by remember { mutableStateOf(false) }
    if (!read) {
        EInkDialog(onDismiss = onDismiss) {
            TextMMD(text = stringResource(R.string.lock_set_heading), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            TextMMD(text = stringResource(R.string.lock_set_what), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
            TextMMD(
                text = stringResource(R.string.lock_set_forgotten),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(16.dp))
            ButtonMMD(onClick = { read = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                TextMMD(text = stringResource(R.string.lock_set_go))
            }
        }
        return
    }
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var said by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val field = rememberFirstField()
    fun go() {
        if (working) return
        said = when {
            !pinShapeOk(pin) -> context.getString(R.string.lock_pin_shape)
            pin != again -> context.getString(R.string.lock_pin_differ)
            else -> null
        }
        if (said != null) return
        working = true
        val typed = pin
        scope.launch {
            val key = withContext(Dispatchers.Default) { Locks.setPin(typed) }
            said = if (key == null) context.getString(R.string.lock_broken) else onSet(key)
            working = false
            if (said == null) onDismiss()
        }
    }
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = stringResource(R.string.lock_set_type), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(12.dp))
        PinField(pin, { pin = it; said = null }, stringResource(R.string.lock_pin_hint), Modifier.focusRequester(field))
        Spacer(Modifier.height(8.dp))
        PinField(again, { again = it; said = null }, stringResource(R.string.lock_pin_again_hint), onDone = ::go)
        val line = said ?: if (working) stringResource(R.string.lock_checking) else null
        if (line != null) {
            Spacer(Modifier.height(8.dp))
            TextMMD(text = line, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(16.dp))
        ButtonMMD(onClick = ::go, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            TextMMD(text = stringResource(R.string.lock_set_and_lock))
        }
    }
}

/** Settings' Change the PIN: the PIN now, then the new one twice. */
@Composable
fun ChangePinDialog(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var old by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var said by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var changed by remember { mutableStateOf(false) }
    val field = rememberFirstField()
    fun go() {
        if (working) return
        said = when {
            !pinShapeOk(pin) -> context.getString(R.string.lock_pin_shape)
            pin != again -> context.getString(R.string.lock_pin_differ)
            else -> null
        }
        if (said != null) return
        working = true
        val (was, now) = old to pin
        scope.launch {
            val tried = withContext(Dispatchers.Default) { Locks.changePin(was, now) }
            when (tried) {
                is Locks.Tried.Right -> changed = true
                Locks.Tried.Wrong -> said = context.getString(R.string.lock_wrong)
                is Locks.Tried.Wait -> {
                    val s = ((tried.ms + 999) / 1000).toInt()
                    said = context.resources.getQuantityString(R.plurals.lock_wait, s, s)
                }
                Locks.Tried.Broken -> said = context.getString(R.string.lock_broken)
            }
            old = ""
            working = false
        }
    }
    if (changed) {
        SaidDialog(stringResource(R.string.lock_change_changed), onDone)
        return
    }
    EInkDialog(onDismiss = onDone) {
        TextMMD(text = stringResource(R.string.lock_change_heading), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(12.dp))
        PinField(old, { old = it; said = null }, stringResource(R.string.lock_change_current), Modifier.focusRequester(field))
        Spacer(Modifier.height(8.dp))
        PinField(pin, { pin = it; said = null }, stringResource(R.string.lock_change_new))
        Spacer(Modifier.height(8.dp))
        PinField(again, { again = it; said = null }, stringResource(R.string.lock_pin_again_hint), onDone = ::go)
        val line = said ?: if (working) stringResource(R.string.lock_checking) else null
        if (line != null) {
            Spacer(Modifier.height(8.dp))
            TextMMD(text = line, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(16.dp))
        ButtonMMD(onClick = ::go, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            TextMMD(text = stringResource(R.string.lock_change_done))
        }
    }
}
