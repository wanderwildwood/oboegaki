package com.wanderwildwood.oboegaki.ui

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.provider.MediaStore
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.oboegaki.R
import com.wanderwildwood.oboegaki.notes.Notes
import com.wanderwildwood.oboegaki.scan.Photo
import com.wanderwildwood.oboegaki.scan.Point
import com.wanderwildwood.oboegaki.scan.Quad
import com.wanderwildwood.oboegaki.scan.Scanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.hypot

/**
 * Scanning paper into a note.
 *
 * It opens on a choice: take a photo, or use one already taken.
 *
 * A camera app takes the picture, so this app holds no camera permission, and
 * nothing here pretends to be a viewfinder: a live preview on E Ink is a smear. On the still
 * photograph the page's corners are guessed and drawn, and the reader drags any that are
 * wrong. Each kept page is straightened and turned to black ink on white; Done writes them
 * all as one PDF beside a new note that shows them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(onDone: (pdf: ByteArray) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pages = remember { mutableStateListOf<Bitmap>() }
    var photo by remember { mutableStateOf<Photo?>(null) }
    var quad by remember { mutableStateOf<Quad?>(null) }
    var ink by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    val shot = remember { File(context.cacheDir, "scans/shot.jpg").also { it.parentFile?.mkdirs() } }
    val shotUri = remember { FileProvider.getUriForFile(context, "${context.packageName}.files", shot) }

    fun load(uri: Uri) {
        busy = true
        scope.launch {
            val read = withContext(Dispatchers.Default) {
                runCatching { Scanner.read(context, uri).let { it to Scanner.guess(it) } }
            }
            read.onSuccess { (p, q) ->
                photo = p
                quad = q
                problem = null
            }.onFailure { problem = context.getString(R.string.scan_unreadable) }
            busy = false
        }
    }

    val cameras = remember { cameraApps(context) }
    var choosingCamera by remember { mutableStateOf(false) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && shot.length() > 0) load(shotUri)
    }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) load(uri)
    }

    fun shoot(app: CameraApp) {
        shot.delete()
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .setPackage(app.packageName)
            .putExtra(MediaStore.EXTRA_OUTPUT, shotUri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The grant travels with the clip as well as the flag, for camera apps that read it there.
        intent.clipData = ClipData.newRawUri("", shotUri)
        runCatching { camera.launch(intent) }.onFailure { problem = context.getString(R.string.scan_no_camera) }
    }

    fun takePhoto() {
        val remembered = cameras.firstOrNull { it.packageName == Notes.preferences.camera }
        when {
            cameras.isEmpty() -> problem = context.getString(R.string.scan_no_camera)
            remembered != null -> shoot(remembered)
            cameras.size == 1 -> shoot(cameras.single())
            else -> choosingCamera = true
        }
    }

    // The screen opens on its two choices, a new photo or one already taken, and waits. It
    // used to go straight to the camera, which flashed the choices past on the panel and
    // took the other one away.

    BackHandler {
        if (photo != null) {
            photo = null
            quad = null
        } else {
            onCancel()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = {
                    TextMMD(
                        text = if (pages.isEmpty()) stringResource(R.string.scan_title)
                        else context.resources.getQuantityString(R.plurals.scan_pages, pages.size, pages.size),
                    )
                },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onCancel) },
            )
        },
    ) { contentPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(contentPadding).padding(16.dp)) {
            val p = photo
            val q = quad
            if (p != null && q != null) {
                TextMMD(text = stringResource(R.string.scan_corners), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(8.dp))
                Corners(
                    preview = p.preview,
                    quad = q,
                    onQuad = { quad = it },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButtonMMD(onClick = { ink = !ink }, modifier = Modifier.weight(1f).height(52.dp)) {
                        TextMMD(text = stringResource(if (ink) R.string.scan_ink else R.string.scan_grey))
                    }
                    ButtonMMD(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                val page = withContext(Dispatchers.Default) { runCatching { Scanner.page(p, q, ink) }.getOrNull() }
                                if (page != null) pages += page else problem = context.getString(R.string.scan_unreadable)
                                photo = null
                                quad = null
                                busy = false
                            }
                        },
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) { TextMMD(text = stringResource(R.string.scan_keep)) }
                }
            } else {
                if (pages.isNotEmpty()) {
                    Image(
                        bitmap = pages.last().asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                val text = problem ?: if (busy) stringResource(R.string.scan_working) else null
                if (text != null) {
                    TextMMD(text = text, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                }
                ButtonMMD(onClick = { takePhoto() }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    TextMMD(text = stringResource(if (pages.isEmpty()) R.string.scan_take else R.string.scan_another))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButtonMMD(onClick = { choose.launch("image/*") }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    TextMMD(text = stringResource(R.string.scan_choose))
                }
                if (cameras.size > 1) {
                    val name = cameras.firstOrNull { it.packageName == Notes.preferences.camera }?.label
                    TextMMD(
                        text = stringResource(R.string.scan_camera, name ?: stringResource(R.string.scan_camera_unchosen)),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.fillMaxWidth().clickable { choosingCamera = true }.padding(vertical = 12.dp),
                    )
                }
                if (pages.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    ButtonMMD(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                val pdf = withContext(Dispatchers.Default) { Scanner.pdf(pages.toList()) }
                                onDone(pdf)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { TextMMD(text = stringResource(R.string.scan_done)) }
                }
            }
        }
    }

    if (choosingCamera) {
        EInkDialog(onDismiss = { choosingCamera = false }) {
            TextMMD(text = stringResource(R.string.scan_which_camera), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            for (app in cameras) {
                TextMMD(
                    text = app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            Notes.preferences.camera = app.packageName
                            choosingCamera = false
                            shoot(app)
                        }
                        .padding(vertical = 12.dp),
                )
            }
        }
    }
}

/** A camera app that takes a photograph when another app asks it to. */
data class CameraApp(val packageName: String, val label: String)

/**
 * Every installed camera app that takes a photo on request. Asked package by package, because
 * Android 11 answers an unaddressed request only with the camera the phone came with, and the
 * Kompakt's own camera takes none.
 */
fun cameraApps(context: Context): List<CameraApp> {
    val pm = context.packageManager
    @Suppress("DEPRECATION")
    return pm.getInstalledPackages(0)
        .mapNotNull { info ->
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(Intent(MediaStore.ACTION_IMAGE_CAPTURE).setPackage(info.packageName), 0)
                .firstOrNull()
                ?.let { CameraApp(info.packageName, it.loadLabel(pm).toString()) }
        }
        .sortedBy { it.label.lowercase() }
}

/**
 * The photograph with the page's outline over it and a ring at each corner. Dragging anywhere
 * moves the nearest corner, so a thumb need not land exactly on a ring that the photograph
 * may be showing against something dark.
 */
@Composable
private fun Corners(preview: Bitmap, quad: Quad, onQuad: (Quad) -> Unit, modifier: Modifier) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    val ring = with(LocalDensity.current) { 14.dp.toPx() }
    val line = with(LocalDensity.current) { 2.dp.toPx() }
    val image = remember(preview) { preview.asImageBitmap() }
    var current by remember(quad) { mutableStateOf(quad) }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .aspectRatio(preview.width.toFloat() / preview.height)
                .onSizeChanged { box = it }
                .pointerInput(preview) {
                    var which = -1
                    detectDragGestures(
                        onDragStart = { start ->
                            val s = box.width.toFloat() / preview.width
                            which = current.points.withIndex().minBy { (_, p) -> hypot(p.x * s - start.x, p.y * s - start.y) }.index
                        },
                        onDragEnd = { onQuad(current) },
                    ) { change, drag ->
                        change.consume()
                        val s = box.width.toFloat() / preview.width
                        val pts = current.points.toMutableList()
                        val p = pts[which]
                        pts[which] = Point(
                            (p.x + drag.x / s).coerceIn(0f, preview.width.toFloat()),
                            (p.y + drag.y / s).coerceIn(0f, preview.height.toFloat()),
                        )
                        current = Quad(pts[0], pts[1], pts[2], pts[3])
                    }
                },
        ) {
            Image(bitmap = image, contentDescription = null, modifier = Modifier.fillMaxSize())
            Canvas(modifier = Modifier.fillMaxSize()) {
                val s = size.width / preview.width
                val pts = current.points.map { Offset(it.x * s, it.y * s) }
                val path = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    for (i in 1 until 4) lineTo(pts[i].x, pts[i].y)
                    close()
                }
                // White under black, so the outline shows over a dark table and over paper.
                drawPath(path, Color.White, style = Stroke(width = line * 3))
                drawPath(path, Color.Black, style = Stroke(width = line))
                for (pt in pts) {
                    drawCircle(Color.White, radius = ring + line, center = pt)
                    drawCircle(Color.Black, radius = ring, center = pt, style = Stroke(width = line * 1.5f))
                }
            }
        }
    }
}
