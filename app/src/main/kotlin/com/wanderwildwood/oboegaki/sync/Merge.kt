package com.wanderwildwood.oboegaki.sync

/**
 * Two people's edits to one note, put together line by line.
 *
 * [base] is the note as both sides last agreed on it, [ours] and [theirs] what each side made of
 * it since. Where they changed different lines, both changes are kept. Where they changed the
 * same lines differently, there is no right answer to pick, and this returns null: the caller
 * keeps both whole rather than guessing which half of somebody's sentence to throw away.
 *
 * Two additions at the same place are not a disagreement. On a shopping list that is the usual
 * case, one person adding eggs at the bottom while the other adds bread, and both lines are kept,
 * ours first. The same line added on both sides is kept once.
 *
 * Kept free of anything Android so the rule can be tested, because it is the rule that decides
 * whether somebody's edit survives.
 */
fun merge(base: String, ours: String, theirs: String): String? {
    if (ours == theirs) return ours
    if (ours == base) return theirs
    if (theirs == base) return ours

    val b = base.split('\n')
    val o = ours.split('\n')
    val t = theirs.split('\n')

    // The matching is quadratic. A note is a page, not a book; past this the merge gives up
    // and both copies are kept, which is slower to sort out but loses nothing.
    if (b.size.toLong() * maxOf(o.size, t.size) > LIMIT) return null

    val hunks = (changes(b, o, Side.OURS) + changes(b, t, Side.THEIRS))
        .sortedWith(compareBy<Hunk> { it.baseStart }.thenBy { it.baseEnd }.thenBy { it.side })

    val out = ArrayList<String>(maxOf(o.size, t.size))
    var at = 0
    var i = 0
    var lastInsertAt = -1
    var lastInsert: List<String>? = null
    while (i < hunks.size) {
        // A region is every hunk that overlaps the first; two additions at one point do not
        // overlap each other, and come out one after the other.
        val start = hunks[i].baseStart
        var end = hunks[i].baseEnd
        val region = mutableListOf(hunks[i])
        var j = i + 1
        while (j < hunks.size && hunks[j].baseStart < end) {
            region += hunks[j]
            end = maxOf(end, hunks[j].baseEnd)
            j++
        }

        while (at < start) out += b[at++]

        val mine = region.filter { it.side == Side.OURS }
        val yours = region.filter { it.side == Side.THEIRS }
        val chosen: List<String> = when {
            yours.isEmpty() -> span(b, o, mine, start, end)
            mine.isEmpty() -> span(b, t, yours, start, end)
            else -> {
                val a = span(b, o, mine, start, end)
                val c = span(b, t, yours, start, end)
                if (a == c) a else return null
            }
        }

        // The same line added in the same place on both sides is one line, not two.
        val isInsert = start == end
        if (!(isInsert && start == lastInsertAt && chosen == lastInsert)) out += chosen
        if (isInsert) {
            lastInsertAt = start
            lastInsert = chosen
        }

        at = end
        i = j
    }
    while (at < b.size) out += b[at++]
    return out.joinToString("\n")
}

private const val LIMIT = 4_000_000L

private enum class Side { OURS, THEIRS }

/** Base lines [baseStart, baseEnd) became side lines [sideStart, sideEnd). */
private data class Hunk(
    val side: Side,
    val baseStart: Int,
    val baseEnd: Int,
    val sideStart: Int,
    val sideEnd: Int,
)

/**
 * What one side wrote in place of base lines [start, end), given the hunks of that side that
 * fall in it. Lines of the region outside those hunks are unchanged, so they line up one for
 * one, and the edges of the region can be found from the first and last hunk.
 */
private fun span(base: List<String>, side: List<String>, hunks: List<Hunk>, start: Int, end: Int): List<String> {
    val first = hunks.first()
    val last = hunks.last()
    val from = first.sideStart - (first.baseStart - start)
    val to = last.sideEnd + (end - last.baseEnd)
    return side.subList(from, to)
}

/**
 * Where [side] departs from [base], as runs of base lines replaced by runs of side lines.
 * Found from a longest common subsequence: what both share stays put, and the gaps between
 * shared lines are the changes.
 */
private fun changes(base: List<String>, side: List<String>, tag: Side): List<Hunk> {
    val pairs = common(base, side)
    val hunks = mutableListOf<Hunk>()
    var pb = -1
    var ps = -1
    for ((nb, ns) in pairs + (base.size to side.size)) {
        if (nb - pb > 1 || ns - ps > 1) {
            hunks += Hunk(tag, pb + 1, nb, ps + 1, ns)
        }
        pb = nb
        ps = ns
    }
    return hunks
}

/** The lines [a] and [b] share, in order, as index pairs. */
private fun common(a: List<String>, b: List<String>): List<Pair<Int, Int>> {
    val n = a.size
    val m = b.size
    // lengths[i][j] is the longest common subsequence of a[i..] and b[j..].
    val lengths = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            lengths[i][j] = if (a[i] == b[j]) {
                lengths[i + 1][j + 1] + 1
            } else {
                maxOf(lengths[i + 1][j], lengths[i][j + 1])
            }
        }
    }
    val pairs = mutableListOf<Pair<Int, Int>>()
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            a[i] == b[j] -> { pairs += i to j; i++; j++ }
            lengths[i + 1][j] >= lengths[i][j + 1] -> i++
            else -> j++
        }
    }
    return pairs
}
