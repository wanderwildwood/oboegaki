package com.wanderwildwood.oboegaki.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.oboegaki.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A scanned PDF in a note: its pages drawn one under another at the width of the screen, and
 * a line that opens it whole in whatever reads PDFs on the phone. Pages are drawn once, when
 * the note opens, at the panel's own width; nothing is kept between openings.
 */
@Composable
fun Pages(uri: Uri?, name: String) {
    val context = LocalContext.current
    var pages by remember(uri) { mutableStateOf<List<Bitmap>?>(null) }
    LaunchedEffect(uri) {
        if (uri == null) return@LaunchedEffect
        pages = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")!!.use { fd ->
                    PdfRenderer(fd).use { pdf ->
                        (0 until minOf(pdf.pageCount, 20)).map { i ->
                            pdf.openPage(i).use { page ->
                                val width = 440
                                val height = width * page.height / page.width
                                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                                    it.eraseColor(Color.WHITE)
                                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                }
                            }
                        }
                    }
                }
            }.getOrNull()
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        val shown = pages
        when {
            uri == null -> TextMMD(text = stringResource(R.string.pages_missing), style = MaterialTheme.typography.bodyMedium)
            shown == null -> TextMMD(text = stringResource(R.string.pages_drawing), style = MaterialTheme.typography.labelSmall)
            else -> shown.forEach { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.onSurface),
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        if (uri != null) {
            TextMMD(
                text = stringResource(R.string.pages_open, name),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .clickable {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(uri, "application/pdf")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            )
                        }
                    }
                    .padding(vertical = 8.dp),
            )
        }
    }
}
