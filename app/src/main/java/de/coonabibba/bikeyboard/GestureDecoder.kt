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

    companion object {
        /** A word no finger could have traced: fewer than two keys on the way. */
        const val UNSWIPEABLE = Float.MAX_VALUE

        private const val INITIAL_POLYLINE = 32
    }
}
