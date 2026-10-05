package com.wanderwildwood.oboegaki.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.oboegaki.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/** The width a picture in a note is drawn at: the panel's, and no more is decoded. */
private const val WIDTH = 480

/**
 * A picture in a note, at the width of the screen, in greys: the panel shows nothing else,
 * and a colour photograph dithered by the panel is muddier than one turned grey first. A tap
 * opens it whole in whatever shows pictures on the phone.
 */
@Composable
fun Picture(uri: Uri?) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }
    LaunchedEffect(uri) {
        if (uri == null) return@LaunchedEffect
        val read = withContext(Dispatchers.IO) { runCatching { decode(context.contentResolver, uri) }.getOrNull() }
        if (read == null) failed = true else bitmap = read
    }
    val grey = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        val shown = bitmap
        when {
            uri == null -> TextMMD(text = stringResource(R.string.picture_missing), style = MaterialTheme.typography.bodyMedium)
            failed -> TextMMD(text = stringResource(R.string.picture_unreadable), style = MaterialTheme.typography.bodyMedium)
            shown == null -> TextMMD(text = stringResource(R.string.pages_drawing), style = MaterialTheme.typography.labelSmall)
            else -> {
                Image(
                    bitmap = shown.asImageBitmap(),
                    contentDescription = null,
                    colorFilter = grey,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.onSurface)
                        .clickable {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW)
                                        .setDataAndType(uri, "image/*")
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                )
                            }
                        },
                )
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

/** The picture at no more than the panel's width, the right way up. */
private fun decode(resolver: android.content.ContentResolver, uri: Uri): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= WIDTH * 2) sample *= 2
    val raw = resolver.openInputStream(uri)!!.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    val turn = runCatching {
        resolver.openInputStream(uri)!!.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }
    }.getOrDefault(0f)
    val upright = if (turn == 0f) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(turn) }, true)
    if (upright.width <= WIDTH) return upright
    return Bitmap.createScaledBitmap(upright, WIDTH, upright.height * WIDTH / upright.width, true)
}

/**
 * Pictures shared in from another app: a note that shows them, or scanned as paper. Several
 * scanned become the pages of one PDF, in the order they came.
 */
@Composable
fun PicturesDialog(count: Int, onNote: () -> Unit, onScan: () -> Unit, onDismiss: () -> Unit) {
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(
            text = pluralStringResource(R.plurals.pictures_heading, count, count),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        TextMMD(
            text = pluralStringResource(R.plurals.pictures_new_note, count),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onNote).padding(vertical = 12.dp),
        )
        TextMMD(
            text = pluralStringResource(R.plurals.pictures_scan, count),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onScan).padding(vertical = 12.dp),
        )
    }
}

/** None of the pictures shared in could be read, so no note was made. */
@Composable
fun PicturesFailedDialog(onDismiss: () -> Unit) {
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = stringResource(R.string.picture_unreadable), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        OutlinedButtonMMD(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { TextMMD(text = stringResource(R.string.about_close), style = MaterialTheme.typography.bodySmall) }
    }
}
