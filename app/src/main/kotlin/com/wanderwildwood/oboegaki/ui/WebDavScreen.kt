package com.wanderwildwood.oboegaki.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.oboegaki.R
import com.wanderwildwood.oboegaki.notes.Keeping
import com.wanderwildwood.oboegaki.notes.Notes
import com.wanderwildwood.oboegaki.sync.DavAccount
import com.wanderwildwood.oboegaki.sync.NotWebDav
import com.wanderwildwood.oboegaki.sync.Refused
import com.wanderwildwood.oboegaki.sync.Unreachable
import com.wanderwildwood.oboegaki.sync.Untrusted
import com.wanderwildwood.oboegaki.sync.WebDavRemote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Any WebDAV server: its address, a username and password, and the folder for the notes.
 *
 * Nothing is kept until the server has been asked and has answered as a WebDAV folder these
 * credentials open, with the notes folder made if it was missing. Each way that can go wrong
 * has its own sentence, because "could not connect" for a typo in the password sends the reader
 * looking in the wrong place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavScreen(onSaved: (DavAccount, String) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val preferences = Notes.preferences
    val known = if (preferences.keeping == Keeping.WEBDAV) preferences.dav else null
    var address by remember { mutableStateOf(known?.address.orEmpty()) }
    var user by remember { mutableStateOf(known?.user.orEmpty()) }
    var password by remember { mutableStateOf(known?.password.orEmpty()) }
    var folder by remember { mutableStateOf(if (known != null) preferences.davFolder else "Notes") }
    var shown by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var askHttp by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    val unreachable = stringResource(R.string.dav_unreachable)
    val badAddress = stringResource(R.string.dav_bad_address)
    val refused = stringResource(R.string.dav_refused)
    val notWebDav = stringResource(R.string.dav_not_webdav)
    val certificate = stringResource(R.string.dav_certificate)
    val failed = stringResource(R.string.dav_failed)

    fun check(httpAccepted: Boolean) {
        val normal = WebDavRemote.normalise(address)
        if (normal == null) {
            problem = badAddress
            return
        }
        if (normal.startsWith("http://") && !httpAccepted) {
            askHttp = true
            return
        }
        val wanted = folder.trim('/', ' ')
        checking = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { WebDavRemote(normal, user.trim(), password, wanted).probe() }
            }
            checking = false
            problem = when (val e = result.exceptionOrNull()) {
                null -> {
                    onSaved(DavAccount(normal, user.trim(), password), wanted)
                    return@launch
                }
                is Untrusted -> certificate
                is Refused -> refused
                is NotWebDav -> notWebDav
                is Unreachable -> unreachable
                else -> failed.format(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.dav_title)) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onBack) },
            )
        },
    ) { contentPadding ->
        LazyColumnMMD(modifier = Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 20.dp)) {
            item {
                Column {
                    Spacer(Modifier.height(16.dp))
                    TextFieldMMD(
                        value = address,
                        onValueChange = { address = it.replace("\n", "").trim() },
                        modifier = Modifier.fillMaxWidth(),
                        label = { TextMMD(text = stringResource(R.string.dav_address)) },
                        placeholder = { TextMMD(text = "https://webdav.example.org/files") },
                        singleLine = true,
                        enabled = !checking,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                    )
                    Spacer(Modifier.height(4.dp))
                    TextMMD(text = stringResource(R.string.dav_address_note), style = MaterialTheme.typography.labelSmall)
                }
            }
            item {
                Column {
                    Spacer(Modifier.height(16.dp))
                    TextFieldMMD(
                        value = user,
                        onValueChange = { user = it.replace("\n", "") },
                        modifier = Modifier.fillMaxWidth(),
                        label = { TextMMD(text = stringResource(R.string.dav_user)) },
                        singleLine = true,
                        enabled = !checking,
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
                            label = { TextMMD(text = stringResource(R.string.dav_password)) },
                            singleLine = true,
                            enabled = !checking,
                            visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButtonMMD(
                            onClick = { shown = !shown },
                            // Wide enough for either word, so the field beside it does not shift,
                            // and the panel redraw, every time it is pressed.
                            modifier = Modifier.widthIn(min = 72.dp).height(48.dp),
                        ) {
                            TextMMD(
                                text = stringResource(if (shown) R.string.dav_hide else R.string.dav_show),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    TextMMD(text = stringResource(R.string.dav_password_note), style = MaterialTheme.typography.labelSmall)
                }
            }
            item {
                Column {
                    Spacer(Modifier.height(16.dp))
                    TextFieldMMD(
                        value = folder,
                        onValueChange = { folder = it.replace("\n", "") },
                        modifier = Modifier.fillMaxWidth(),
                        label = { TextMMD(text = stringResource(R.string.dav_folder)) },
                        singleLine = true,
                        enabled = !checking,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    )
                    Spacer(Modifier.height(4.dp))
                    TextMMD(text = stringResource(R.string.dav_folder_note), style = MaterialTheme.typography.labelSmall)
                }
            }
            item {
                Column {
                    Spacer(Modifier.height(20.dp))
                    ButtonMMD(
                        onClick = { check(httpAccepted = false) },
                        enabled = address.isNotBlank() && user.isNotBlank() && password.isNotEmpty() && !checking,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        TextMMD(text = stringResource(if (checking) R.string.dav_checking else R.string.dav_save))
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }

    if (askHttp) {
        EInkDialog(onDismiss = { askHttp = false }) {
            TextMMD(text = stringResource(R.string.dav_http_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(12.dp))
            TextMMD(text = stringResource(R.string.dav_http), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(18.dp))
            Row {
                OutlinedButtonMMD(onClick = { askHttp = false }, modifier = Modifier.weight(1f).height(48.dp)) {
                    TextMMD(text = stringResource(R.string.dav_http_back))
                }
                Spacer(Modifier.width(8.dp))
                ButtonMMD(
                    onClick = {
                        askHttp = false
                        check(httpAccepted = true)
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    TextMMD(text = stringResource(R.string.dav_http_go))
                }
            }
        }
    }

    problem?.let { said ->
        EInkDialog(onDismiss = { problem = null }) {
            TextMMD(text = said, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(18.dp))
            OutlinedButtonMMD(onClick = { problem = null }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                TextMMD(text = stringResource(R.string.dav_ok))
            }
        }
    }
}
