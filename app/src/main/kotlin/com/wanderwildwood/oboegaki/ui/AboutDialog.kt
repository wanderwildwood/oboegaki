package com.wanderwildwood.oboegaki.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.oboegaki.BuildConfig
import com.wanderwildwood.oboegaki.R

/**
 * What this is, where the notes go, and where the source lives.
 *
 * The line about where notes go is here because a stranger cannot safely assume the answer:
 * most notes apps keep them on their maker's server.
 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(
            text = stringResource(R.string.about_title, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )

        Spacer(Modifier.height(14.dp))
        TextMMD(text = stringResource(R.string.about_privacy), style = MaterialTheme.typography.labelSmall)

        Spacer(Modifier.height(14.dp))
        TextMMD(text = stringResource(R.string.about_licence), style = MaterialTheme.typography.labelSmall)
        TextMMD(text = stringResource(R.string.about_okhttp), style = MaterialTheme.typography.labelSmall)
        TextMMD(text = stringResource(R.string.about_icons), style = MaterialTheme.typography.labelSmall)

        Spacer(Modifier.height(14.dp))
        TextMMD(text = "github.com/wanderwildwood/oboegaki", style = MaterialTheme.typography.labelSmall)

        Spacer(Modifier.height(14.dp))
        Llama()

        Spacer(Modifier.height(18.dp))
        OutlinedButtonMMD(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { TextMMD(text = stringResource(R.string.about_close), style = MaterialTheme.typography.bodySmall) }
    }
}

/**
 * A llama at the foot of the About, which opens the page a donation goes to. The site's
 * address sits at the start of the same line and opens the site; the llama and its words
 * open the page.
 *
 * Straight to the checkout: the Donate button on the site only leads there anyway. The short
 * square.link form, which is what the site itself links to, so a regenerated checkout follows
 * it. A phone with nothing that opens a web address says so rather than doing nothing.
 */
@Composable
private fun Llama() {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        TextMMD(
            text = "wanderthe.dev",
            style = MaterialTheme.typography.labelSmall,
            // The site's address opens the site, the way the llama beside it opens its page.
            modifier = Modifier
                .clickable {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://wanderthe.dev")),
                        )
                    }.onFailure {
                        Toast.makeText(context, context.getString(R.string.about_no_browser), Toast.LENGTH_SHORT).show()
                    }
                }
                .padding(vertical = 4.dp),
        )
        Spacer(Modifier.width(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://square.link/u/AGu8oT10")),
                        )
                    }.onFailure {
                        Toast.makeText(context, context.getString(R.string.about_no_browser), Toast.LENGTH_SHORT).show()
                    }
                }
                .padding(vertical = 4.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.llama),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(6.dp))
            TextMMD(text = stringResource(R.string.about_feed_the_llamas), style = MaterialTheme.typography.labelSmall)
        }
    }
}
