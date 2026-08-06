package de.coonabibba.bikeyboard

import kotlin.math.hypot

/**
 * A finger's journey across the keys, reduced to a fixed number of points
 * spaced evenly along it.
 *
 * Resampling is what makes two paths comparable at all. Raw touch points arrive
 * at whatever rate the digitiser reports and bunch up wherever the finger
 * slowed down, so the same word swiped twice produces different numbers of
 * points in different places. Spacing them by distance instead of by time
 * throws the speed away and keeps the shape, which is the part that carries the
 * word (D39).
 *
 * The same class holds the *ideal* path of a candidate word — the polyline
 * through its key centres, resampled the same way — so that scoring is a
 * comparison between two objects of one kind rather than between a path and a
 * list of keys. That distinction matters more than it looks: swiping `hello`
 * crosses `r`, `t`, `y`, `d`, `f`, `g` and `j` on the way, and any scheme that
 * matches touch points against candidate *letters* has to explain away every
 * one of them. Matching path against path does not, because the ideal path
 * travels over those keys too.
 */
class GesturePath private constructor(
    val xs: FloatArray,
    val ys: FloatArray,
    /** Total travel along the original polyline, in pixels. */
    val length: Float,
) {

    val startX: Float get() = xs[0]
    val startY: Float get() = ys[0]
    val endX: Float get() = xs[xs.size - 1]
    val endY: Float get() = ys[ys.size - 1]

    /**
     * Mean distance between corresponding points of this path and [other],
     * divided by [keyWidth] so the answer reads in key widths rather than
     * pixels and survives a change of screen density.
     *
     * Index-wise rather than a best alignment: both paths have already been
     * spaced evenly, so point *i* of each is the same fraction of the way
     * along, and letting them slide would forgive a path that visits the right
     * keys in the right order at wildly the wrong pace.
     */
    fun distanceTo(other: GesturePath, keyWidth: Float): Float {
        if (keyWidth <= 0f) return Float.MAX_VALUE
        var total = 0f
        for (i in xs.indices) {
            total += hypot(xs[i] - other.xs[i], ys[i] - other.ys[i])
        }
        return total / xs.size / keyWidth
    }

    companion object {

        /**
         * How many points a path is reduced to.
         *
         * Both paths in a comparison are resampled the same way, so most of
         * what this number does cancels out — which is why sweeping it from 24
         * to 64 moved top-1 accuracy by nothing at all on perfect, realistic
         * and imprecise traces alike. The one case that improved was the
         * sloppiest, by a point, and it had finished improving by here; past
         * this the only thing that grows is the time.
         *
         * It is worth knowing what it costs even so. A path is resampled by
         * arc length, so samples cut across corners rather than going round
         * them, and an eight-letter word loses about a tenth of its length in
         * the process at this setting. That loss is why the number is not
         * lower: it lands on the corners, and the corners are the letters.
         */
        const val SAMPLES = 32

        /**
         * Builds a path from the first [count] entries of [xs] and [ys].
         *
         * Null when there is nothing to resample: a single point, or a finger
         * that came down and went up again without moving. Neither is a
         * gesture, and both would divide by zero.
         */
        fun of(xs: FloatArray, ys: FloatArray, count: Int = xs.size): GesturePath? {
            if (count < 2) return null

            var length = 0f
            for (i in 1 until count) {
                length += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
            }
            if (length <= 0f) return null

            val outX = FloatArray(SAMPLES)
            val outY = FloatArray(SAMPLES)
            val step = length / (SAMPLES - 1)

            outX[0] = xs[0]
            outY[0] = ys[0]
            var out = 1

            // The cursor walks the polyline, dropping a point every `step` of
            // travel. It does not advance to the next raw point when it drops
            // one — the remainder of that segment still has to be walked, and a
            // slow corner can easily be several samples long.
            var cx = xs[0]
            var cy = ys[0]
            var carried = 0f
            var i = 1
            while (i < count && out < SAMPLES) {
                val d = hypot(xs[i] - cx, ys[i] - cy)
                if (d <= 0f) {
                    i++
                    continue
                }
                if (carried + d >= step) {
                    val t = (step - carried) / d
                    cx += t * (xs[i] - cx)
                    cy += t * (ys[i] - cy)
                    outX[out] = cx
                    outY[out] = cy
                    out++
                    carried = 0f
                } else {
                    carried += d
                    cx = xs[i]
                    cy = ys[i]
                    i++
                }
            }

            // Rounding can leave the last sample or two unfilled. The end of the
            // path is where they belong: the lift point is the one place a
            // swipe is unambiguous, and it must not drift.
            while (out < SAMPLES) {
                outX[out] = xs[count - 1]
                outY[out] = ys[count - 1]
                out++
            }

            return GesturePath(outX, outY, length)
        }
    }
}
