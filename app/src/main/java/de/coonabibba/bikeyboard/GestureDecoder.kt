package de.coonabibba.bikeyboard

import kotlin.math.hypot

/**
 * Turns a word into the path it would have been swiped along, and says how far
 * an actual swipe was from it (D39).
 *
 * This is the whole of the gesture model: no learning, no per-user calibration,
 * no smoothing beyond the resampling. What makes it work is not the matching
 * but what sits behind it — the same two lexicons, weighted by how common each
 * word is, so that a shape which fits `hello` and `helo` equally well resolves
 * to the one people actually write. Swipe typing is mostly a frequency problem
 * wearing a geometry problem's clothes, and the geometry only has to be good
 * enough to narrow the field.
 *
 * Characters with no key of their own are **skipped rather than refused**, which
 * is what lets `don't` be swiped as `dont`: the apostrophe lives on a long-press
 * (D17), so no finger ever travels to it, and a candidate that insisted on one
 * could never be reached.
 */
class GestureDecoder(private val keys: KeyGeometry) {

    /** Scratch for the candidate polyline, reused across a single decode pass. */
    private var polyX = FloatArray(INITIAL_POLYLINE)
    private var polyY = FloatArray(INITIAL_POLYLINE)

    /**
     * How far the finger would travel to swipe [word], in pixels, or
     * [UNSWIPEABLE] if the word has fewer than two distinct keys.
     *
     * Deliberately cheap — no allocation, one pass — because it is the filter
     * that keeps the expensive comparison off most of the dictionary. A word
     * whose journey is half or twice the length of the one actually made is not
     * a near miss, it is a different word.
     */
    fun idealLength(word: CharSequence): Float {
        var length = 0f
        var lastX = 0f
        var lastY = 0f
        var seen = 0
        for (index in word.indices) {
            val entry = keys.centreOf(word[index]) ?: continue
            if (seen > 0) {
                // A doubled letter is one place on the keyboard, not two. The
                // finger does not move for the second `l` of `hello`, so the
                // ideal path must not either.
                if (entry.centreX == lastX && entry.centreY == lastY) continue
                length += hypot(entry.centreX - lastX, entry.centreY - lastY)
            }
            lastX = entry.centreX
            lastY = entry.centreY
            seen++
        }
        return if (seen < 2) UNSWIPEABLE else length
    }

    /** The path [word] would have been swiped along, or null if it has none. */
    fun idealPath(word: CharSequence): GesturePath? {
        if (polyX.size < word.length) {
            polyX = FloatArray(word.length)
            polyY = FloatArray(word.length)
        }
        var count = 0
        for (index in word.indices) {
            val entry = keys.centreOf(word[index]) ?: continue
            if (count > 0 && entry.centreX == polyX[count - 1] && entry.centreY == polyY[count - 1]) {
                continue
            }
            polyX[count] = entry.centreX
            polyY[count] = entry.centreY
            count++
        }
        if (count < 2) return null
        return GesturePath.of(polyX, polyY, count)
    }

    /**
     * How far [path] is from the way [word] should have been swiped, in key
     * widths, or [UNSWIPEABLE] if the word cannot be swiped at all.
     *
     * The number is on the same footing as a spatial edit distance
     * ([SpatialEditDistance]) on purpose: both say "how implausible is it that
     * this finger meant this word", both are fed to the same exponential, and
     * both therefore land on the one confidence scale the strip's purple and
     * the auto-replace threshold read (D33, D37).
     */
    fun cost(path: GesturePath, word: CharSequence): Float {
        val ideal = idealPath(word) ?: return UNSWIPEABLE
        return path.distanceTo(ideal, keys.keyWidth)
    }

    /**
     * What a stroke is allowed to be and still be considered.
     *
     * These live with the decoder rather than with the search that applies
     * them, because every one of them is a statement about fingers rather than
     * about dictionaries — and because a gate that silently rejects the right
     * word is the hardest kind of failure to see from outside. `GestureGateTest`
     * reads them to report which one is doing the rejecting, which it cannot do
     * if they are shut away.
     */
    companion object {
        /** A word no finger could have traced: fewer than two keys on the way. */
        const val UNSWIPEABLE = Float.MAX_VALUE

        /**
         * How far off a key the finger may come down or lift and still have
         * that key considered, in key widths.
         *
         * Tighter than tapping's `FIRST_LETTER_REACH`, and for a reason about
         * cost rather than accuracy: the endpoints multiply. Three plausible
         * first letters and three plausible last ones is nine slices of
         * dictionary, not three — generosity here is quadratic where everywhere
         * else in the search it is linear.
         */
        const val ENDPOINT_REACH = 0.35f

        /** A hard ceiling on that multiplication, per endpoint. */
        const val MAX_ENDPOINT_LETTERS = 3

        /**
         * How far the journey's length may differ from the candidate's.
         *
         * Wide, and widened again after measuring. People cut corners hard, so
         * a real path runs *shorter* than the ideal one — on a long word with
         * several reversals it can be less than half of it — while a wobbling
         * thumb makes it longer. Both were rejecting correct words outright at
         * the first setting, which is the worst way to fail: the word is gone
         * before anything scores it, so it cannot even appear in the strip as a
         * runner-up. What this gate is really for is throwing out `an` when the
         * finger crossed the whole keyboard.
         */
        const val MIN_LENGTH_RATIO = 0.3f
        const val MAX_LENGTH_RATIO = 3.2f

        /**
         * The worst average deviation, in key widths, still worth ranking.
         *
         * Measured against real-shaped strokes rather than picked: a hurried
         * one — corners cut to nothing, thumb wandering half a key, sampled
         * once a frame — runs to about 1.2, and a ceiling of 1.0 was cutting
         * those off. The scorer already discounts a poor fit exponentially, so
         * a loose gate costs a little time and no accuracy, while a tight one
         * costs the word.
         */
        const val MAX_COST = 1.8f

        private const val INITIAL_POLYLINE = 32
    }
}
