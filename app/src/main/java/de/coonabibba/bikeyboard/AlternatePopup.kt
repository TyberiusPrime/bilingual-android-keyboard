package de.coonabibba.bikeyboard

/**
 * Where the cells of a long-press popup go (D36).
 *
 * Kept out of the view because it is arithmetic with a rule in it, and the rule
 * was wrong for a while in a way nobody could see by reading it.
 *
 * **The first alternate must sit under the finger.** It is the one drawn in the
 * corner of the key, the one a plain hold commits, and under D17 and D32 it is
 * the one the key is understood to carry — the umlaut on `u`, the digit on `e`,
 * the exclamation mark on `n`. The finger is already on the key when the popup
 * opens, so anything that puts a *different* cell under it means the smallest
 * drift silently picks something else.
 *
 * The old rule centred the row on the key, which does exactly that: with four
 * alternates the first one lands a cell and a half to the left of the thumb. It
 * survived as long as every key had one alternate, and broke the moment D32 gave
 * `u` four and `n` two. `a` went on working purely by luck — it sits near the
 * left edge, so clamping the row on screen shoved cell zero back under the
 * finger.
 *
 * So: cell zero is centred on the key, and the rest extend away from it in one
 * direction — towards whichever side has more room, which is left for the keys
 * on the right of the board and right for the ones on the left. A row that
 * still does not fit is shifted bodily rather than re-ordered, because the
 * alternative is the first cell moving out from under the finger again.
 */
object AlternatePopup {

    /**
     * The left edge of each cell, in the same order as the alternates.
     *
     * Index 0 is not necessarily leftmost on screen: when the row extends
     * leftwards it is the rightmost cell. Callers hit-test the rectangles, so
     * the order only has to agree with the labels.
     */
    fun cellLefts(
        count: Int,
        keyCentre: Float,
        cellWidth: Float,
        viewWidth: Float,
        gap: Float,
    ): FloatArray {
        if (count <= 0 || cellWidth <= 0f) return FloatArray(0)

        // Cell zero, centred on the key the finger is holding.
        val anchor = keyCentre - cellWidth / 2f
        // Away from the nearer edge, so the row has somewhere to go.
        val step = if (keyCentre > viewWidth / 2f) -cellWidth else cellWidth

        val lefts = FloatArray(count) { anchor + it * step }
        val rowLeft = if (step < 0f) lefts.last() else lefts.first()
        val rowRight = rowLeft + count * cellWidth

        // Shifted whole, never re-ordered. Pushing left first and right second
        // means that when the row is wider than the screen — more alternates
        // than fit, which the layout tests forbid but arithmetic should survive
        // — the near cells stay reachable and the far ones fall off the end.
        var shift = 0f
        if (rowRight > viewWidth - gap) shift = (viewWidth - gap) - rowRight
        if (rowLeft + shift < gap) shift = gap - rowLeft

        if (shift != 0f) {
            for (index in lefts.indices) lefts[index] += shift
        }
        return lefts
    }
}
