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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
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
 * most notes apps keep them on their maker's server. Privacy, Terms and Licences are rows that
 * open their own page; the pages turn by whole rows like any list.
 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    var page by rememberSaveable { mutableStateOf("") }
    // The words run to more than a dialog holds, so they turn over by whole rows like any list.
    val height = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
    val context = LocalContext.current
    val gpl = remember { Licences.read(context, R.raw.licence_gpl3) }
    val texts = remember {
        Licences.distinctLicences().associateWith { Licences.read(context, Licences.textFor(it)) }
    }
    EInkDialog(onDismiss = { if (page.isEmpty()) onDismiss() else page = "" }) {
        TextMMD(
            text = if (page.isEmpty()) stringResource(R.string.about_title, BuildConfig.VERSION_NAME) else stringResource(
                when (page) {
                    PRIVACY -> R.string.about_privacy_heading
                    TERMS -> R.string.about_terms_heading
                    else -> R.string.about_licences_heading
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        PagedColumn(Modifier.fillMaxWidth().height(height)) {
            when (page) {
                PRIVACY -> {
                    item { Paragraph(R.string.about_privacy) }
                    item { Paragraph(R.string.about_privacy_voice) }
                    item { Paragraph(R.string.about_privacy_desktop) }
                    item { Paragraph(R.string.about_privacy_close) }
                }
                TERMS -> item { Paragraph(R.string.about_terms) }
                LICENCES -> {
                    item { Heading(R.string.licences_source) }
                    item {
                        Link(Licences.SOURCE.removePrefix("https://")) {
                            openLink(context, Licences.SOURCE, context.getString(R.string.about_no_browser_address, Licences.SOURCE))
                        }
                    }
                    item { Heading(R.string.licences_licence) }
                    item { Paragraph(R.string.licences_licence_line) }
                    items(gpl) { Words(it) }
                    item { Heading(R.string.licences_built_with) }
                    item { Paragraph(R.string.licences_built_with_note) }
                    items(Licences.components) { c ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Words(c.name + ", " + c.licence)
                            Link(c.link.removePrefix("https://")) {
                                openLink(context, c.link, context.getString(R.string.about_no_browser_address, c.link))
                            }
                        }
                    }
                    for (licence in Licences.distinctLicences()) {
                        item { TextMMD(text = stringResource(R.string.licences_text_of, licence), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)) }
                        items(texts.getValue(licence)) { Words(it) }
                    }
                }
                else -> {
                    item { Paragraph(R.string.about_blurb) }
                    item { AboutRow(R.string.about_privacy_heading) { page = PRIVACY } }
                    item { AboutRow(R.string.about_terms_heading) { page = TERMS } }
                    item { AboutRow(R.string.about_licences_heading) { page = LICENCES } }
                    item { Llama() }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButtonMMD(
            onClick = { if (page.isEmpty()) onDismiss() else page = "" },
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { TextMMD(text = stringResource(if (page.isEmpty()) R.string.about_close else R.string.about_back), style = MaterialTheme.typography.bodySmall) }
    }
}

private const val PRIVACY = "privacy"
private const val TERMS = "terms"
private const val LICENCES = "licences"

@Composable
private fun AboutRow(text: Int, onClick: () -> Unit) {
    TextMMD(
        text = stringResource(text),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically),
    )
}

@Composable
private fun Link(text: String, onClick: () -> Unit) {
    TextMMD(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically),
    )
}

@Composable
private fun Words(text: String) {
    TextMMD(text = text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun Heading(text: Int) {
    TextMMD(
        text = stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun Paragraph(text: Int) {
    TextMMD(text = stringResource(text), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 4.dp))
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
                .clickable { openLink(context, "https://wanderthe.dev", context.getString(R.string.about_no_browser_address, "https://wanderthe.dev")) }
                .heightIn(min = 48.dp)
                .wrapContentHeight(Alignment.CenterVertically),
        )
        Spacer(Modifier.width(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable { openLink(context, "https://square.link/u/AGu8oT10", context.getString(R.string.about_no_browser_address, "https://square.link/u/AGu8oT10")) }
                .heightIn(min = 48.dp),
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
