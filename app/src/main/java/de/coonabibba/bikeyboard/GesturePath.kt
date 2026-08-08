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
 * Scoring no longer compares two of these point for point. It did at first,
 * and that turned out to be the decoder's central mistake: matching by position
 * along the stroke means a loop, or any other stretch of extra travel, shifts
 * every later sample against its counterpart and wrecks the alignment of the
 * *correct* word while barely touching a wrong one that was misaligned anyway.
 * See [GestureDecoder] for what replaced it. What this class still provides is
 * the even spacing both halves of that replacement depend on.
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
        const val SAMPLES = 48

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
