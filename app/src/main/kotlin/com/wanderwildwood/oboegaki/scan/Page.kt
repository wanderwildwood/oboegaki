package com.wanderwildwood.oboegaki.scan

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The arithmetic of a scan, on plain arrays of grey (0 black to 255 white), kept free of
 * Android so it can be tested on a computer: finding the page in a photograph, straightening
 * it, and turning it into black ink on white paper.
 *
 * Nothing here is clever. A page is the largest bright thing in the picture, its corners are
 * the points of it furthest toward each corner of the frame, and the reader drags them right
 * where that guess is wrong. That is enough for paper on a table, which is what gets scanned.
 */

data class Point(val x: Float, val y: Float)

/** Four corners, clockwise from the top left. */
data class Quad(val topLeft: Point, val topRight: Point, val bottomRight: Point, val bottomLeft: Point) {
    val points get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    fun scaled(by: Float) = Quad(
        Point(topLeft.x * by, topLeft.y * by), Point(topRight.x * by, topRight.y * by),
        Point(bottomRight.x * by, bottomRight.y * by), Point(bottomLeft.x * by, bottomLeft.y * by),
    )

    /**
     * Each corner drawn [by] of the way toward the middle. A corner placed a hair outside the
     * paper takes a sliver of table with it, which comes out as a black bar down the edge of
     * an otherwise clean page; a hair inside loses nothing anyone wrote.
     */
    fun shrunk(by: Float): Quad {
        val cx = points.map { it.x }.average().toFloat()
        val cy = points.map { it.y }.average().toFloat()
        fun pull(p: Point) = Point(p.x + (cx - p.x) * by, p.y + (cy - p.y) * by)
        return Quad(pull(topLeft), pull(topRight), pull(bottomRight), pull(bottomLeft))
    }

    /** Width and height of the page this quad holds, as the longer of each pair of edges. */
    fun size(): Pair<Int, Int> {
        val w = max(dist(topLeft, topRight), dist(bottomLeft, bottomRight))
        val h = max(dist(topLeft, bottomLeft), dist(topRight, bottomRight))
        return w.roundToInt().coerceAtLeast(1) to h.roundToInt().coerceAtLeast(1)
    }
}

private fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

/**
 * Where the page is in [grey], a [width] by [height] picture, best guess. Falls back to the
 * frame with a small margin when nothing page-like stands out, so the reader always has four
 * corners to drag.
 */
fun findPage(grey: IntArray, width: Int, height: Int): Quad {
    val fallback = inset(width, height)
    if (width < 8 || height < 8) return fallback
    val smooth = boxBlur(grey, width, height, 2)
    val cut = otsu(smooth)

    // The largest connected bright region is taken to be the paper.
    val seen = BooleanArray(width * height)
    var best: IntArray? = null
    var bestSize = 0
    val stack = IntArray(width * height)
    for (start in 0 until width * height) {
        if (seen[start] || smooth[start] <= cut) continue
        var top = 0
        var count = 0
        val members = ArrayList<Int>()
        stack[top++] = start
        seen[start] = true
        while (top > 0) {
            val i = stack[--top]
            members += i
            count++
            val x = i % width
            val y = i / width
            if (x > 0) push(i - 1, smooth, cut, seen, stack, top).let { if (it) top++ }
            if (x < width - 1) push(i + 1, smooth, cut, seen, stack, top).let { if (it) top++ }
            if (y > 0) push(i - width, smooth, cut, seen, stack, top).let { if (it) top++ }
            if (y < height - 1) push(i + width, smooth, cut, seen, stack, top).let { if (it) top++ }
        }
        if (count > bestSize) {
            bestSize = count
            best = members.toIntArray()
        }
    }
    val region = best ?: return fallback
    // A region that is most of the frame is the frame, not a page on something darker; one
    // that is a sliver is not a page at all.
    val share = bestSize.toFloat() / (width * height)
    if (share < 0.12f || share > 0.97f) return fallback

    var tl = region[0]; var tr = region[0]; var br = region[0]; var bl = region[0]
    fun sum(i: Int) = i % width + i / width
    fun diff(i: Int) = i % width - i / width
    for (i in region) {
        if (sum(i) < sum(tl)) tl = i
        if (sum(i) > sum(br)) br = i
        if (diff(i) > diff(tr)) tr = i
        if (diff(i) < diff(bl)) bl = i
    }
    fun p(i: Int) = Point((i % width).toFloat(), (i / width).toFloat())
    return Quad(p(tl), p(tr), p(br), p(bl))
}

private fun push(i: Int, grey: IntArray, cut: Int, seen: BooleanArray, stack: IntArray, top: Int): Boolean {
    if (seen[i] || grey[i] <= cut) return false
    seen[i] = true
    stack[top] = i
    return true
}

fun inset(width: Int, height: Int): Quad {
    val mx = width * 0.06f
    val my = height * 0.06f
    return Quad(Point(mx, my), Point(width - mx, my), Point(width - mx, height - my), Point(mx, height - my))
}

/**
 * The page in [quad] of [grey] drawn flat into a [outWidth] by [outHeight] picture: for every
 * pixel of the flat page, where it came from in the photograph, read between pixels.
 */
fun straighten(grey: IntArray, width: Int, height: Int, quad: Quad, outWidth: Int, outHeight: Int): IntArray {
    val h = homography(
        listOf(Point(0f, 0f), Point(outWidth - 1f, 0f), Point(outWidth - 1f, outHeight - 1f), Point(0f, outHeight - 1f)),
        quad.points,
    )
    val out = IntArray(outWidth * outHeight)
    for (y in 0 until outHeight) {
        for (x in 0 until outWidth) {
            val d = h[6] * x + h[7] * y + 1.0
            val sx = (h[0] * x + h[1] * y + h[2]) / d
            val sy = (h[3] * x + h[4] * y + h[5]) / d
            out[y * outWidth + x] = sample(grey, width, height, sx, sy)
        }
    }
    return out
}

private fun sample(grey: IntArray, width: Int, height: Int, x: Double, y: Double): Int {
    if (x < 0 || y < 0 || x > width - 1 || y > height - 1) return 255
    val x0 = x.toInt().coerceAtMost(width - 2)
    val y0 = y.toInt().coerceAtMost(height - 2)
    val fx = x - x0
    val fy = y - y0
    val a = grey[y0 * width + x0]
    val b = grey[y0 * width + x0 + 1]
    val c = grey[(y0 + 1) * width + x0]
    val d = grey[(y0 + 1) * width + x0 + 1]
    val top = a + (b - a) * fx
    val bottom = c + (d - c) * fx
    return (top + (bottom - top) * fy).roundToInt().coerceIn(0, 255)
}

/**
 * The projective map taking each of [from] to the matching point of [to], as the eight
 * numbers of a 3x3 matrix whose last entry is 1.
 */
fun homography(from: List<Point>, to: List<Point>): DoubleArray {
    val a = Array(8) { DoubleArray(9) }
    for (i in 0 until 4) {
        val (x, y) = from[i].x.toDouble() to from[i].y.toDouble()
        val (u, v) = to[i].x.toDouble() to to[i].y.toDouble()
        a[i * 2] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
        a[i * 2 + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
    }
    // Gaussian elimination with partial pivoting.
    for (col in 0 until 8) {
        var pivot = col
        for (r in col + 1 until 8) if (kotlin.math.abs(a[r][col]) > kotlin.math.abs(a[pivot][col])) pivot = r
        val t = a[col]; a[col] = a[pivot]; a[pivot] = t
        val p = a[col][col]
        if (kotlin.math.abs(p) < 1e-12) continue
        for (c in col until 9) a[col][c] /= p
        for (r in 0 until 8) {
            if (r == col) continue
            val f = a[r][col]
            if (f == 0.0) continue
            for (c in col until 9) a[r][c] -= f * a[col][c]
        }
    }
    return DoubleArray(8) { a[it][8] }
}

/**
 * Ink and paper: each pixel black where it is clearly darker than the paper around it,
 * white otherwise. Judged against the neighbourhood rather than one level for the page, so a
 * shadow across half the sheet does not swallow the writing in it.
 */
fun inkOnPaper(grey: IntArray, width: Int, height: Int): IntArray {
    val radius = max(8, min(width, height) / 40)
    val mean = boxBlur(grey, width, height, radius)
    return IntArray(grey.size) { i -> if (grey[i] < mean[i] * 0.86) 0 else 255 }
}

/** [grey], a [width] by [height] picture, turned a quarter clockwise: [height] wide now. */
fun turnClockwise(grey: IntArray, width: Int, height: Int): IntArray {
    val out = IntArray(grey.size)
    for (y in 0 until height) for (x in 0 until width) {
        // (x, y) lands at (height - 1 - y, x) in a picture [height] wide.
        out[x * height + (height - 1 - y)] = grey[y * width + x]
    }
    return out
}

/**
 * The same corners on a picture turned a quarter clockwise, named afresh by where they now
 * sit, because the corner called top left is the one the flat page is drawn from: carried
 * over from before the turn, a page straightened on screen would come out on its side.
 */
fun Quad.turnedClockwise(height: Int): Quad =
    byPosition(points.map { Point(height - 1 - it.y, it.x) })

/** Four points named by position: the top left nearest the origin, and so round. */
fun byPosition(points: List<Point>): Quad {
    val tl = points.minBy { it.x + it.y }
    val br = points.maxBy { it.x + it.y }
    val rest = points.filter { it !== tl && it !== br }.ifEmpty { points }
    val tr = rest.maxBy { it.x - it.y }
    val bl = rest.minBy { it.x - it.y }
    return Quad(tl, tr, br, bl)
}

/**
 * White along every edge, [band] pixels deep. Whatever is that close to the edge of a page
 * after straightening is the table it was lying on, not anything written on it.
 */
fun clearEdges(bw: IntArray, width: Int, height: Int, band: Int): IntArray {
    val out = bw.copyOf()
    for (y in 0 until height) for (x in 0 until width) {
        if (x < band || y < band || x >= width - band || y >= height - band) out[y * width + x] = 255
    }
    return out
}

/** A box blur of the given radius, through a summed-area table so its cost does not grow with it. */
fun boxBlur(grey: IntArray, width: Int, height: Int, radius: Int): IntArray {
    val sums = LongArray((width + 1) * (height + 1))
    for (y in 0 until height) {
        var row = 0L
        for (x in 0 until width) {
            row += grey[y * width + x]
            sums[(y + 1) * (width + 1) + x + 1] = sums[y * (width + 1) + x + 1] + row
        }
    }
    val out = IntArray(grey.size)
    for (y in 0 until height) {
        val y0 = max(0, y - radius); val y1 = min(height, y + radius + 1)
        for (x in 0 until width) {
            val x0 = max(0, x - radius); val x1 = min(width, x + radius + 1)
            val total = sums[y1 * (width + 1) + x1] - sums[y0 * (width + 1) + x1] -
                sums[y1 * (width + 1) + x0] + sums[y0 * (width + 1) + x0]
            out[y * width + x] = (total / ((x1 - x0) * (y1 - y0))).toInt()
        }
    }
    return out
}

/** The grey level that best splits [grey] into two groups, by Otsu's method. */
fun otsu(grey: IntArray): Int {
    val hist = IntArray(256)
    for (g in grey) hist[g.coerceIn(0, 255)]++
    val total = grey.size.toDouble()
    var sumAll = 0.0
    for (i in 0..255) sumAll += i * hist[i].toDouble()
    var sumBack = 0.0
    var weightBack = 0.0
    var best = 0.0
    var cut = 127
    for (t in 0..255) {
        weightBack += hist[t]
        if (weightBack == 0.0) continue
        val weightFore = total - weightBack
        if (weightFore == 0.0) break
        sumBack += t * hist[t].toDouble()
        val meanBack = sumBack / weightBack
        val meanFore = (sumAll - sumBack) / weightFore
        val between = weightBack * weightFore * (meanBack - meanFore) * (meanBack - meanFore)
        if (between > best) {
            best = between
            cut = t
        }
    }
    return cut
}
