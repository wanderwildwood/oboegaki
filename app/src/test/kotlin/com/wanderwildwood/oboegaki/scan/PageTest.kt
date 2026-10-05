package com.wanderwildwood.oboegaki.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class PageTest {

    /** Whether [p] is inside the convex quad, by the sign of each edge's cross product. */
    private fun inside(q: Quad, x: Float, y: Float): Boolean {
        val pts = q.points
        var sign = 0
        for (i in 0 until 4) {
            val a = pts[i]
            val b = pts[(i + 1) % 4]
            val cross = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s != 0) {
                if (sign == 0) sign = s else if (s != sign) return false
            }
        }
        return true
    }

    /** A tilted page on a dark table, with two dark lines of "writing" on it. */
    private fun photo(w: Int, h: Int, page: Quad): IntArray {
        val out = IntArray(w * h) { 45 }
        for (y in 0 until h) for (x in 0 until w) {
            if (inside(page, x.toFloat(), y.toFloat())) out[y * w + x] = 225
        }
        return out
    }

    private fun near(a: Point, b: Point, within: Float) =
        assertTrue("$a not within $within of $b", hypot(a.x - b.x, a.y - b.y) <= within)

    @Test
    fun findsATiltedPageOnADarkTable() {
        val page = Quad(Point(70f, 40f), Point(320f, 60f), Point(300f, 270f), Point(50f, 250f))
        val found = findPage(photo(400, 300, page), 400, 300)
        near(found.topLeft, page.topLeft, 6f)
        near(found.topRight, page.topRight, 6f)
        near(found.bottomRight, page.bottomRight, 6f)
        near(found.bottomLeft, page.bottomLeft, 6f)
    }

    @Test
    fun aPictureWithNoPageGivesTheFrameWithAMargin() {
        val flat = IntArray(200 * 100) { 128 }
        assertEquals(inset(200, 100), findPage(flat, 200, 100))
    }

    @Test
    fun theHomographyTakesCornersToCorners() {
        val from = listOf(Point(0f, 0f), Point(99f, 0f), Point(99f, 199f), Point(0f, 199f))
        val to = listOf(Point(10f, 20f), Point(110f, 30f), Point(100f, 220f), Point(5f, 210f))
        val h = homography(from, to)
        for (i in 0 until 4) {
            val x = from[i].x.toDouble(); val y = from[i].y.toDouble()
            val d = h[6] * x + h[7] * y + 1
            assertEquals(to[i].x.toDouble(), (h[0] * x + h[1] * y + h[2]) / d, 1e-6)
            assertEquals(to[i].y.toDouble(), (h[3] * x + h[4] * y + h[5]) / d, 1e-6)
        }
    }

    @Test
    fun straighteningAPageGivesBackThePage() {
        val page = Quad(Point(60f, 40f), Point(330f, 55f), Point(310f, 260f), Point(45f, 250f))
        val flat = straighten(photo(400, 300, page), 400, 300, page, 200, 150)
        // Away from the very edges, everything should be paper.
        var paper = 0
        for (y in 5 until 145) for (x in 5 until 195) if (flat[y * 200 + x] > 200) paper++
        assertTrue("only $paper of ${190 * 140} are paper", paper > 190 * 140 * 0.98)
    }

    @Test
    fun inkStaysInkUnderAShadow() {
        val w = 300; val h = 100
        // Paper that darkens from left to right, as under a hand's shadow, with a line of ink
        // across the middle that is darker than the paper wherever it is.
        val grey = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val paper = 240 - x * 140 / w
            if (y in 48..52) paper - 70 else paper
        }
        val bw = inkOnPaper(grey, w, h)
        for (x in 20 until w - 20) {
            assertEquals("ink at x=$x", 0, bw[50 * w + x])
            assertEquals("paper at x=$x", 255, bw[20 * w + x])
        }
    }

    @Test
    fun otsuSplitsTwoGroups() {
        val grey = IntArray(1000) { if (it < 500) 40 else 210 }
        val cut = otsu(grey)
        assertTrue(cut in 40 until 210)
    }

    @Test
    fun aQuadsSizeIsItsLongerEdges() {
        val q = Quad(Point(0f, 0f), Point(100f, 0f), Point(110f, 200f), Point(0f, 190f))
        val (w, h) = q.size()
        assertTrue(abs(w - 110) <= 1)
        assertTrue(abs(h - 200) <= 1)
    }

    @Test
    fun aPageCutAHairInsideHasNoTableAtItsEdges() {
        val page = Quad(Point(60f, 40f), Point(330f, 55f), Point(310f, 260f), Point(45f, 250f))
        // Corners dragged a little outside the paper, as a thumb does.
        val loose = Quad(Point(57f, 37f), Point(333f, 52f), Point(313f, 263f), Point(42f, 253f))
        val flat = straighten(photo(400, 300, page), 400, 300, loose.shrunk(0.035f), 200, 150)
        val bw = clearEdges(inkOnPaper(flat, 200, 150), 200, 150, 2)
        // Not just the outermost row: two in from each edge must be paper too.
        for (i in 0 until 200) for (row in listOf(2, 147)) assertEquals("table at x=$i row $row", 255, bw[row * 200 + i])
        for (i in 0 until 150) for (col in listOf(2, 197)) assertEquals("table at y=$i col $col", 255, bw[i * 200 + col])
    }

    @Test
    fun turningMovesEveryPixelAQuarterClockwise() {
        // 3 wide, 2 high:  1 2 3 / 4 5 6  becomes 2 wide, 3 high:  4 1 / 5 2 / 6 3
        val turned = turnClockwise(intArrayOf(1, 2, 3, 4, 5, 6), 3, 2)
        assertEquals(listOf(4, 1, 5, 2, 6, 3), turned.toList())
        // Four turns are no turn at all.
        var g = intArrayOf(1, 2, 3, 4, 5, 6); var w = 3; var h = 2
        repeat(4) { g = turnClockwise(g, w, h); val t = w; w = h; h = t }
        assertEquals(listOf(1, 2, 3, 4, 5, 6), g.toList())
    }

    @Test
    fun turnedCornersStillStartAtTheTopLeft() {
        // A page lying sideways in a 400x300 picture, its top edge down the right side.
        val q = Quad(Point(300f, 50f), Point(300f, 250f), Point(100f, 250f), Point(100f, 50f))
        val t = q.turnedClockwise(300)
        // In the turned picture (300 wide) its top left must be the corner nearest the origin.
        val nearest = t.points.minBy { it.x + it.y }
        assertEquals(t.topLeft, nearest)
        val farthest = t.points.maxBy { it.x + it.y }
        assertEquals(t.bottomRight, farthest)
    }

    @Test
    fun colourSurvivesStraightening() {
        // A red page on a blue table.
        val w = 400; val h = 300
        val page = Quad(Point(60f, 40f), Point(330f, 55f), Point(310f, 260f), Point(45f, 250f))
        val red = (0xff shl 24) or (220 shl 16) or (30 shl 8) or 30
        val blue = (0xff shl 24) or (20 shl 16) or (30 shl 8) or 160
        val argb = IntArray(w * h) { i -> if (inside(page, (i % w).toFloat(), (i / w).toFloat())) red else blue }
        val flat = straightenColour(argb, w, h, page.shrunk(0.035f), 100, 80)
        val middle = flat[40 * 100 + 50]
        assertEquals(220, (middle shr 16) and 0xff)
        assertEquals(30, (middle shr 8) and 0xff)
        assertEquals(30, middle and 0xff)
    }
}
