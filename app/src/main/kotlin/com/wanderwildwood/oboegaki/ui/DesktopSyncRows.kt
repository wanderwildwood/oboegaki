package com.wanderwildwood.oboegaki.ui

import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import com.wanderwildwood.oboegaki.desktop.Pairing
import com.wanderwildwood.oboegaki.desktop.countdownText
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.switcher.SwitchMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.oboegaki.R
import com.wanderwildwood.oboegaki.desktop.DesktopSync
import com.wanderwildwood.oboegaki.desktop.Network
import com.wanderwildwood.oboegaki.notes.Notes

/**
 * Desktop Sync's rows in Settings. Off until the switch is turned, and the switch is the only
 * thing that starts it. Nothing here counts down or redraws by itself: the code counts down only while its dialog is open,
 * with how long it lasts, so the screen stays still.
 */
fun LazyListScope.desktopSyncRows() {
    item {
        val context = LocalContext.current
        val running by DesktopSync.running.collectAsState()
        var showing by remember { mutableStateOf(false) }
        Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { if (running) DesktopSync.stop(context) else DesktopSync.start(context) }
                .heightIn(min = 48.dp)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                TextMMD(text = stringResource(R.string.desktop_title), style = MaterialTheme.typography.bodyLarge)
                TextMMD(
                    text = stringResource(if (running) R.string.desktop_what_on else R.string.desktop_what),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.width(16.dp))
            SwitchMMD(checked = running, onCheckedChange = null)
        }
        if (running) SettingRow(stringResource(R.string.desktop_show), stringResource(R.string.desktop_show_what)) { showing = true }
        }
        if (showing && running) ShowLinkDialog(onDismiss = { showing = false })
    }
    item {
        var only by remember { mutableStateOf(Notes.preferences.desktopVpnOnly) }
        val context = LocalContext.current
        val running by DesktopSync.running.collectAsState()
        // Read when the screen is drawn, which is when the reader looks: no timer polls for it.
        val vpnUp = remember(running, only) { Network.reachable(context).any { it.viaVpn } }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    only = !only
                    Notes.preferences.desktopVpnOnly = only
                }
                .heightIn(min = 48.dp)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                TextMMD(text = stringResource(R.string.desktop_vpn_only), style = MaterialTheme.typography.bodyLarge)
                TextMMD(
                    text = stringResource(if (only) R.string.desktop_vpn_only_on else R.string.desktop_vpn_only_off),
                    style = MaterialTheme.typography.labelSmall,
                )
                if (running && only && !vpnUp) {
                    TextMMD(
                        text = stringResource(R.string.desktop_no_vpn),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            SwitchMMD(checked = only, onCheckedChange = null)
        }
    }
    item {
        val context = LocalContext.current
        Leave(stringResource(R.string.desktop_reset), stringResource(R.string.desktop_reset_confirm)) {
            DesktopSync.resetLink(context)
        }
    }
}

/** The address, the six-digit code, and the whole link. A new code each time it is opened. */
@Composable
private fun ShowLinkDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    // A new code each time it opens, and a new one whenever it runs out or is used, for as long
    // as the dialog is up. The one-second tick lives only while this is on screen.
    var shown by remember { mutableStateOf<Pairing.Shown?>(DesktopSync.pairing.issue().let { DesktopSync.pairing.show() }) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            shown = DesktopSync.pairing.show()
        }
    }
    val where = remember { Network.reachable(context) }
    val token = Notes.preferences.desktopToken
    val first = where.firstOrNull()
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = stringResource(R.string.desktop_show), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(12.dp))
        if (first == null) {
            TextMMD(text = stringResource(R.string.desktop_no_address), style = MaterialTheme.typography.bodyMedium)
        } else {
            TextMMD(text = stringResource(R.string.desktop_open), style = MaterialTheme.typography.bodyMedium)
            for (w in where) {
                TextMMD(
                    text = "${w.label}  http://${w.address}:${DesktopSync.PORT}",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 12.dp),
                )
            }
            TextMMD(text = stringResource(R.string.desktop_code), style = MaterialTheme.typography.bodyMedium)
            val now = shown
            if (now == null) {
                TextMMD(text = stringResource(R.string.desktop_code_locked), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
            } else {
                TextMMD(
                    text = now.code.take(3) + " " + now.code.drop(3),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                TextMMD(text = stringResource(R.string.desktop_code_lasts, countdownText(now.leftMs)), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(12.dp))
            TextMMD(text = stringResource(R.string.desktop_link), style = MaterialTheme.typography.bodyMedium)
            TextMMD(
                text = DesktopSync.link(first.address, token),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            TextMMD(
                text = stringResource(R.string.desktop_copy),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { copy(context, DesktopSync.link(first.address, token)) }
                    .heightIn(min = 48.dp)
                    .padding(vertical = 12.dp),
            )
        }
        TextMMD(
            text = stringResource(R.string.desktop_done),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onDismiss).heightIn(min = 48.dp).padding(vertical = 12.dp),
        )
    }
}

/**
 * Puts the link on the clipboard, marked sensitive so Android hides it from previews, and takes it
 * off again after a minute if nothing else has been copied since (the label is unique to this copy).
 */
private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val label = "Desktop Sync link " + java.util.UUID.randomUUID()
    val clip = ClipData.newPlainText(label, text)
    if (Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    clipboard.setPrimaryClip(clip)
    Handler(Looper.getMainLooper()).postDelayed({
        runCatching {
            if (clipboard.primaryClipDescription?.label == label) {
                if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
    }, 60_000L)
}
