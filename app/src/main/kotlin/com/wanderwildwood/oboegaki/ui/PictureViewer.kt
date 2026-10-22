package com.wanderwildwood.oboegaki.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.oboegaki.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private sealed interface Shown {
    data object Decoding : Shown
    data object Failed : Shown
    class Ready(val bitmap: Bitmap, val original: IntSize) : Shown
}

/**
 * A picture of a note, full screen: fitted to the panel, pinched in with two fingers, moved with
 * one once enlarged, a double tap to one picture pixel per screen pixel and again back to fitted.
 * Back closes it. In greys, as the note shows it.
 *
 * The screen-sized bitmap is decoded once; past what it holds, the part of the original in
 * view is decoded at full detail ([region]) so 1:1 shows the photograph's own pixels.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PictureViewer(uri: Uri, name: String, onOpenElsewhere: () -> Unit, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true),
    ) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                window.setDimAmount(0f)
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.setLayout(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        }
        val context = LocalContext.current
        val metrics = context.resources.displayMetrics
        val longSide = max(metrics.widthPixels, metrics.heightPixels) * 2
        var shown by remember(uri) { mutableStateOf<Shown>(Shown.Decoding) }
        LaunchedEffect(uri) {
            shown = withContext(Dispatchers.IO) {
                runCatching {
                    var size = IntSize(1, 1)
                    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                        val w = info.size.width
                        val h = info.size.height
                        size = IntSize(w, h)
                        val longest = max(w, h)
                        if (longest > longSide) {
                            val k = longSide.toFloat() / longest
                            decoder.setTargetSize((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1))
                        }
                        // Software: a hardware bitmap cannot be read back, and the panel has no GPU to gain.
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                    Shown.Ready(bitmap, size)
                }.getOrElse { Shown.Failed }
            }
        }
        val grey = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }

        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            TopAppBarMMD(
                title = { TextMMD(text = name.substringBeforeLast('.').ifEmpty { name }, maxLines = 1) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onClose) },
                actions = { BarButton(Icons.Share, stringResource(R.string.picture_open_elsewhere), onOpenElsewhere) },
            )
            Box(Modifier.weight(1f).fillMaxSize().clipToBounds()) {
                when (val s = shown) {
                    Shown.Decoding -> Unit
                    Shown.Failed -> TextMMD(
                        text = stringResource(R.string.picture_unreadable),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                    is Shown.Ready -> Zoomable(
                        bitmap = s.bitmap,
                        key = uri,
                        original = s.original,
                        detail = { area, sc -> region(context.contentResolver, uri, area, sc) },
                        doubleTap = true,
                        onTap = { _, _ -> },
                        onSwipe = { },
                        grey = grey,
                    )
                }
            }
        }
    }
}

/**
 * Part of the original at full detail, for a picture enlarged beyond what the screen-sized
 * bitmap holds. [area] is in the original's pixels; [scale] is 1 for every pixel, less to thin
 * them out. Only the window is decoded, so a fifty-megapixel photo costs the window's memory.
 */
private suspend fun region(resolver: android.content.ContentResolver, uri: Uri, area: Rect, scale: Float): Bitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val crop = Rect(area)
                if (scale < 1f) {
                    val w = (info.size.width * scale).toInt().coerceAtLeast(1)
                    val h = (info.size.height * scale).toInt().coerceAtLeast(1)
                    decoder.setTargetSize(w, h)
                    crop.set(
                        (area.left * scale).toInt().coerceIn(0, w - 1),
                        (area.top * scale).toInt().coerceIn(0, h - 1),
                        (area.right * scale).toInt().coerceIn(1, w),
                        (area.bottom * scale).toInt().coerceIn(1, h),
                    )
                }
                decoder.setCrop(crop)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull()
    }

/**
 * A picture fitted to the panel, with pinch-to-zoom and a one-finger pan once enlarged.
 *
 * Written out by hand rather than with `detectTransformGestures`, which takes every one-finger
 * drag as a pan and leaves nothing for the swipe that turns the page. Here one finger is a pan
 * only while the picture is enlarged; otherwise it is a tap or a swipe, decided when it lifts.
 * The pan follows the finger and stops when the finger does — no fling, nothing coasting.
 *
 * A double tap jumps to one picture pixel per screen pixel, or back to fitted. The bitmap is only
 * screen-sized, so past that the part of the original in view is decoded at full detail
 * ([detail]) and drawn over it, a moment after the finger settles.
 */
@Composable
internal fun Zoomable(
    bitmap: Bitmap,
    key: Any,
    /** The original's size in pixels, when it is more than [bitmap] holds; null when [bitmap] is all there is. */
    original: IntSize?,
    /** The part of the original at [Rect] (its pixels) at the given scale, or null. */
    detail: (suspend (Rect, Float) -> Bitmap?)?,
    /** False for a video's still, where a tap is only a tap. */
    doubleTap: Boolean,
    /** Where across the panel the tap landed, 0 to 1, and whether the picture is enlarged. */
    onTap: (fraction: Float, zoomed: Boolean) -> Unit,
    /** +1 for on, -1 for back. */
    onSwipe: (Int) -> Unit,
    grey: ColorFilter? = null,
) {
    var scale by remember(key) { mutableFloatStateOf(1f) }
    var offset by remember(key) { mutableStateOf(Offset.Zero) }
    var sharp by remember(key) { mutableStateOf<Sharp?>(null) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val slop = LocalViewConfiguration.current.touchSlop
    val doubleTapMs = LocalViewConfiguration.current.doubleTapTimeoutMillis
    val swipe = with(LocalDensity.current) { 48.dp.toPx() }
    val near = with(LocalDensity.current) { 96.dp.toPx() }
    val fullW = original?.width ?: bitmap.width
    val fullH = original?.height ?: bitmap.height

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        // The size the picture is drawn at before any zoom: fitted inside the panel.
        val fit = min(boxW / bitmap.width, boxH / bitmap.height)
        val fitW = bitmap.width * fit
        val fitH = bitmap.height * fit
        // Screen pixels per original pixel when fitted.
        val fitFull = min(boxW / fullW, boxH / fullH)
        val maxZoom = max(MAX_ZOOM, oneToOneScale(boxW, boxH, fullW, fullH))

        fun clamp(o: Offset, s: Float): Offset = clampOffset(o, s, fitW, fitH, boxW, boxH)

        // Full detail for what is in view, once the picture has been enlarged past what the
        // screen-sized bitmap holds. Waits for the picture to hold still, and takes a window
        // half a screen bigger on every side so a small pan stays covered.
        LaunchedEffect(key, boxW, boxH, fullW, fullH) {
            if (detail == null) return@LaunchedEffect
            snapshotFlow { ZoomState(scale, offset) }.collectLatest { z ->
                val perPixel = z.scale * fitFull
                if (z.scale <= 1.02f || perPixel <= bitmap.width.toFloat() / fullW) {
                    sharp = null
                    return@collectLatest
                }
                val visW = boxW / perPixel
                val visH = boxH / perPixel
                val left = fullW / 2f + (-boxW / 2 - z.offset.x) / perPixel
                val top = fullH / 2f + (-boxH / 2 - z.offset.y) / perPixel
                val want = min(1f, perPixel)
                val have = sharp
                if (have != null && have.scale >= want * 0.99f &&
                    have.area.left <= left && have.area.top <= top &&
                    have.area.right >= left + visW && have.area.bottom >= top + visH
                ) return@collectLatest
                delay(150)
                val area = Rect(
                    (left - visW / 2).toInt().coerceAtLeast(0),
                    (top - visH / 2).toInt().coerceAtLeast(0),
                    (left + visW * 1.5f).toInt().coerceAtMost(fullW),
                    (top + visH * 1.5f).toInt().coerceAtMost(fullH),
                )
                if (area.width() < 1 || area.height() < 1) return@collectLatest
                detail(area, want)?.let { sharp = Sharp(it.asImageBitmap(), area, want) }
            }
        }

        // The touches are read on this unscaled box, not on the picture: a layer that has been
        // scaled reports positions in its own scaled space, and every pan would run at the
        // wrong speed.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(key, boxW, boxH, fullW, fullH, doubleTap) {
                    val centre = Offset(boxW / 2, boxH / 2)
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var travelled = Offset.Zero
                        var pinched = false
                        var pressed = true
                        while (pressed) {
                            val event = awaitPointerEvent()
                            val fingers = event.changes.count { it.pressed }
                            if (fingers >= 2) {
                                pinched = true
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val focus = event.calculateCentroid(useCurrent = true) - centre
                                val newScale = (scale * zoom).coerceIn(1f, maxZoom)
                                // Keep the point between the fingers where it is while the scale changes.
                                val moved = (offset - focus) * (newScale / scale) + focus + pan
                                scale = newScale
                                offset = clamp(moved, newScale)
                                event.changes.forEach { it.consume() }
                            } else if (!pinched && fingers == 1) {
                                val change = event.changes.first { it.pressed }
                                val delta = change.positionChange()
                                travelled += delta
                                if (scale > 1f) {
                                    offset = clamp(offset + delta, scale)
                                    change.consume()
                                }
                            }
                            pressed = event.changes.any { it.pressed }
                        }

                        if (scale < 1.02f) {
                            scale = 1f
                            offset = Offset.Zero
                        }
                        if (pinched) return@awaitEachGesture

                        val still = travelled.getDistance() < slop
                        if (still && doubleTap) {
                            // Whether this was one tap or the first of two is known only when
                            // the time for a second has gone by; the single-tap action waits for it.
                            val second = withTimeoutOrNull(doubleTapMs) {
                                awaitFirstDown(requireUnconsumed = false)
                            }
                            if (second == null || (second.position - down.position).getDistance() > near) {
                                onTap(down.position.x / boxW, scale > 1f)
                                // A far-off second touch is a gesture of its own; its pointer
                                // is simply let go of here.
                                return@awaitEachGesture
                            }
                            var travelled2 = Offset.Zero
                            var fingersSeen = 1
                            var pressed2 = true
                            while (pressed2) {
                                val event = awaitPointerEvent()
                                fingersSeen = max(fingersSeen, event.changes.count { it.pressed })
                                val change = event.changes.firstOrNull { it.pressed }
                                if (change != null && fingersSeen == 1) {
                                    val delta = change.positionChange()
                                    travelled2 += delta
                                    if (scale > 1f) {
                                        offset = clamp(offset + delta, scale)
                                        change.consume()
                                    }
                                }
                                pressed2 = event.changes.any { it.pressed }
                            }
                            if (fingersSeen == 1 && travelled2.getDistance() < slop) {
                                val z = doubleTapZoom(
                                    ZoomState(scale, offset),
                                    second.position - centre,
                                    boxW, boxH, fullW, fullH,
                                )
                                scale = z.scale
                                offset = z.offset
                            } else {
                                onTap(down.position.x / boxW, scale > 1f)
                                if (scale <= 1f && abs(travelled2.x) > swipe && abs(travelled2.x) > abs(travelled2.y)) {
                                    onSwipe(if (travelled2.x < 0) +1 else -1)
                                }
                            }
                            return@awaitEachGesture
                        }
                        when {
                            still -> onTap(down.position.x / boxW, scale > 1f)
                            scale > 1f -> Unit
                            abs(travelled.x) > swipe && abs(travelled.x) > abs(travelled.y) ->
                                onSwipe(if (travelled.x < 0) +1 else -1)
                        }
                    }
                },
        ) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                colorFilter = grey,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
            // The sharp window, placed on the panel by the same arithmetic, in whole pixels so
            // that at 1:1 each picture pixel lands on one screen pixel.
            val s = sharp
            if (s != null && scale > 1f) {
                Canvas(Modifier.fillMaxSize()) {
                    val per = scale * fitFull
                    val x = boxW / 2 + offset.x + (s.area.left - fullW / 2f) * per
                    val y = boxH / 2 + offset.y + (s.area.top - fullH / 2f) * per
                    val x2 = boxW / 2 + offset.x + (s.area.right - fullW / 2f) * per
                    val y2 = boxH / 2 + offset.y + (s.area.bottom - fullH / 2f) * per
                    drawImage(
                        s.image,
                        dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
                        dstSize = IntSize((x2.roundToInt() - x.roundToInt()).coerceAtLeast(1), (y2.roundToInt() - y.roundToInt()).coerceAtLeast(1)),
                        filterQuality = FilterQuality.Low,
                        colorFilter = grey,
                    )
                }
            }
        }
    }
}

/** A window of the original at full detail: [area] in its pixels, decoded at [scale] of them. */
private class Sharp(val image: ImageBitmap, val area: Rect, val scale: Float)

/** Pinching goes to four times fitted at least; a double tap may go further, to 1:1. */
private const val MAX_ZOOM = 4f
