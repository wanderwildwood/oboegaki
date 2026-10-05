package com.wanderwildwood.oboegaki.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A photograph, read for scanning: its colour and its grey at working size, and a small copy
 * to show.
 */
class Photo(val colour: IntArray, val grey: IntArray, val width: Int, val height: Int, val preview: Bitmap)

/** How a kept page is drawn. */
enum class Look { INK, GREYS, COLOUR }

/**
 * The Android half of scanning: reading the camera's photograph, and writing pages as a PDF.
 * The arithmetic is in Page.kt.
 */
object Scanner {

    /** The long side a photograph is worked at. Enough for print to stay sharp on a page. */
    private const val WORKING = 2400

    /** The long side of the copy the corners are dragged on, and the page is found in. */
    private const val PREVIEW = 800

    fun read(context: Context, uri: Uri): Photo {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= WORKING) sample *= 2
        val raw = resolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("not a picture")
        // Cameras write the picture as the sensor saw it and say which way up it was held.
        val turn = runCatching {
            resolver.openInputStream(uri)!!.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        }.getOrDefault(0)
        val upright = if (turn == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(turn.toFloat()) }, true)
        val scale = WORKING.toFloat() / max(upright.width, upright.height)
        val work = if (scale < 1f) {
            Bitmap.createScaledBitmap(upright, (upright.width * scale).roundToInt(), (upright.height * scale).roundToInt(), true)
        } else {
            upright
        }
        val pixels = IntArray(work.width * work.height)
        work.getPixels(pixels, 0, work.width, 0, 0, work.width, work.height)
        val grey = IntArray(pixels.size) { i ->
            val c = pixels[i]
            (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        }
        val p = PREVIEW.toFloat() / max(work.width, work.height)
        val preview = Bitmap.createScaledBitmap(work, (work.width * p).roundToInt(), (work.height * p).roundToInt(), true)
        return Photo(pixels, grey, work.width, work.height, preview)
    }

    /** The photograph a quarter turn clockwise, and the corners on it with it. */
    fun turned(photo: Photo, quad: Quad): Pair<Photo, Quad> {
        val grey = turnClockwise(photo.grey, photo.width, photo.height)
        val colour = turnClockwise(photo.colour, photo.width, photo.height)
        val preview = Bitmap.createBitmap(
            photo.preview, 0, 0, photo.preview.width, photo.preview.height,
            Matrix().apply { postRotate(90f) }, true,
        )
        return Photo(colour, grey, photo.height, photo.width, preview) to quad.turnedClockwise(photo.preview.height)
    }

    /** Where the page is, in the preview's coordinates. */
    fun guess(photo: Photo): Quad {
        val w = photo.preview.width
        val h = photo.preview.height
        val px = IntArray(w * h)
        photo.preview.getPixels(px, 0, w, 0, 0, w, h)
        val grey = IntArray(px.size) { i ->
            val c = px[i]
            (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        }
        // Found at a smaller size still: a page is a big shape, and this is the slow part.
        val small = 400f / max(w, h)
        if (small >= 1f) return findPage(grey, w, h)
        val sw = (w * small).roundToInt()
        val sh = (h * small).roundToInt()
        val shrunk = Bitmap.createScaledBitmap(photo.preview, sw, sh, true)
        val sp = IntArray(sw * sh)
        shrunk.getPixels(sp, 0, sw, 0, 0, sw, sh)
        val sg = IntArray(sp.size) { i ->
            val c = sp[i]
            (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        }
        return findPage(sg, sw, sh).scaled(1f / small)
    }

    /**
     * The page flat, from [quad] in the preview's coordinates: black ink on white for writing,
     * greys for a drawing or a photograph, or its own colours for anything that will be looked
     * at on a screen in colour later.
     */
    fun page(photo: Photo, quad: Quad, look: Look): Bitmap {
        val toWork = photo.width.toFloat() / photo.preview.width
        val q = quad.scaled(toWork).shrunk(0.035f)
        val (w, h) = q.size()
        val pixels = when (look) {
            Look.COLOUR -> straightenColour(photo.colour, photo.width, photo.height, q, w, h)
            else -> {
                val flat = straighten(photo.grey, photo.width, photo.height, q, w, h)
                val out = if (look == Look.INK) clearEdges(inkOnPaper(flat, w, h), w, h, max(2, minOf(w, h) / 100)) else flat
                IntArray(out.size) { i -> val g = out[i]; Color.rgb(g, g, g) }
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Pages as a PDF, one picture per page, each page the shape of its picture at the width of
     * a sheet of US Letter, so a printer and a reader on a computer both know what size it is.
     */
    fun pdf(pages: List<Bitmap>): ByteArray {
        val doc = PdfDocument()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        pages.forEachIndexed { i, bitmap ->
            val width = 612
            val height = (width * bitmap.height.toFloat() / bitmap.width).roundToInt()
            val page = doc.startPage(PdfDocument.PageInfo.Builder(width, height, i + 1).create())
            val scale = width.toFloat() / bitmap.width
            page.canvas.save()
            page.canvas.scale(scale, scale)
            page.canvas.drawBitmap(bitmap, 0f, 0f, paint)
            page.canvas.restore()
            doc.finishPage(page)
        }
        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }
}
