package com.wanderwildwood.oboegaki.ui

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Long free text in a [PagedColumn], cut into rows of whole lines.
 *
 * A row taller than the page is turned by pixels, a page height at a time, so a page could open
 * or end on half a line (22 Nov 2026: a long recipe step or note). The About pages were fixed by
 * cutting such a paragraph into runs of whole lines, each no taller than the page; this is the
 * same cut for any screen. Text that fits keeps its one row and its key.
 *
 * At a call site:
 *
 *     val fit = rememberWholeLineFit()
 *     val body = MaterialTheme.typography.bodyMedium
 *     PagedColumn(Modifier.fillMaxSize().wholeLinePage(fit), showCutRow = true) {
 *         wholeLineItems("notes", recipe.notes, body, fit) { run, _, textModifier ->
 *             TextMMD(run, style = body, modifier = textModifier)
 *         }
 *     }
 *
 * [wholeLinePage] goes last on the list's modifier, so it hears the page's own height; [less] is
 * any vertical contentPadding the list has. The row puts textModifier on the text itself: that is
 * how the width the lines wrap at is learned, for each [key] on its own. Until both are known (the first frame), the text is
 * one row, as before. The style handed over must be the one the text is drawn in.
 */
class WholeLineFit internal constructor(internal val measurer: TextMeasurer, internal val px: Float) {
    /** The width each text wraps at, by its key: a numbered step wraps narrower than a note. */
    internal val widths = mutableStateMapOf<Any, Int>()
    internal var room by mutableIntStateOf(0)
    private val runs = HashMap<Any, List<String>>()

    internal fun runsOf(key: Any, text: String, style: TextStyle, gap: Float): List<String> {
        val w = widths[key] ?: 0
        val r = room
        if (w <= 0 || r <= 0 || text.isEmpty()) return listOf(text)
        if (runs.size > 256) runs.clear()
        return runs.getOrPut(listOf(text, style, gap, w, r)) {
            val layout = measurer.measure(text, style = style, constraints = Constraints(maxWidth = w))
            wholeLineRuns(text, layout, room = r - 2f, gap = gap)
        }
    }
}

@Composable
fun rememberWholeLineFit(): WholeLineFit {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current.density
    return remember(measurer, density) { WholeLineFit(measurer, density) }
}

/** On the [PagedColumn]'s modifier, last: the page's height, less [less] of vertical padding. */
fun Modifier.wholeLinePage(fit: WholeLineFit, less: Dp = 0.dp): Modifier =
    onSizeChanged { fit.room = (it.height - less.value * fit.px).toInt().coerceAtLeast(0) }

/**
 * [text] as one row, or as several rows of whole lines when it is taller than the page. [gap] is
 * the row's own vertical padding (above and below the text together). [row] draws one run; index
 * 0 is the first, for a padding only the first or last should have.
 */
fun LazyListScope.wholeLineItems(
    key: Any,
    text: String,
    style: TextStyle,
    fit: WholeLineFit,
    gap: Dp = 0.dp,
    contentType: Any? = null,
    row: @Composable (run: String, index: Int, textModifier: Modifier) -> Unit,
) {
    val runs = fit.runsOf(key, text, style, gap.value * fit.px)
    val learn = Modifier.onSizeChanged { if (it.width > 0 && fit.widths[key] != it.width) fit.widths[key] = it.width }
    runs.forEachIndexed { i, run ->
        item(key = if (runs.size == 1) key else "$key#$i", contentType = contentType) { row(run, i, learn) }
    }
}

/**
 * The runs [layout] (of [text]) is cut into: whole lines, each run with [gap] no taller than [room]
 * pixels. Text that fits, or one line, is itself.
 */
fun wholeLineRuns(text: String, layout: TextLayoutResult, room: Float, gap: Float): List<String> {
    if (layout.size.height + gap <= room || layout.lineCount < 2) return listOf(text)
    return wholeLineGroups((0 until layout.lineCount).map { layout.getLineBottom(it) }, gap, room).map { lines ->
        text.substring(layout.getLineStart(lines.first), layout.getLineEnd(lines.last)).trim('\n', ' ')
    }
}

/**
 * Where to cut: runs of lines, each at most [room] pixels tall with [gap] beside it, so every
 * page holds whole lines. [lineBottoms] is each line's bottom, measured from the text's top.
 * Every run holds at least one line. (The About pages' aboutLineGroups, shared-about fefc0c8.)
 */
fun wholeLineGroups(lineBottoms: List<Float>, gap: Float, room: Float): List<IntRange> {
    val groups = mutableListOf<IntRange>()
    var start = 0
    var top = 0f
    while (start < lineBottoms.size) {
        val limit = room - gap
        var end = start
        while (end + 1 < lineBottoms.size && lineBottoms[end + 1] - top <= limit) end++
        groups.add(start..end)
        top = lineBottoms[end]
        start = end + 1
    }
    return groups
}
