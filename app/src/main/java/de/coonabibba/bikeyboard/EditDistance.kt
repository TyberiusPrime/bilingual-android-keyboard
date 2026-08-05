package de.coonabibba.bikeyboard

import kotlin.math.abs
import kotlin.math.min

/**
 * How far apart two words are, counting insertions, deletions, substitutions
 * and **transpositions** — the last because `teh` for `the` is the single most
 * common way a thumb misses, and calling it two edits rather than one puts it
 * out of reach of a distance-1 search.
 *
 * Bounded: the caller says how far it cares, and anything further away is
 * reported as [max] + 1 without being computed exactly. That is what makes it
 * cheap enough to run against a few thousand words per keystroke.
 */
object EditDistance {

    /**
     * The distance between [a] and [b], or [max] + 1 if it exceeds [max].
     *
     * Optimal string alignment: a transposition costs one, and unlike full
     * Damerau-Levenshtein the same pair of letters is not transposed twice.
     * That distinction only shows up in words no keyboard is going to correct.
     */
    fun between(a: CharSequence, b: CharSequence, max: Int): Int {
        val tooFar = max + 1
        if (abs(a.length - b.length) > max) return tooFar
        if (a.length > b.length) return between(b, a, max)

        // Three rows: the one being filled, and the two behind it that a
        // transposition needs.
        var twoBack = IntArray(a.length + 1)
        var oneBack = IntArray(a.length + 1) { it }
        var current = IntArray(a.length + 1)

        for (j in 1..b.length) {
            current[0] = j
            var best = current[0]
            for (i in 1..a.length) {
                val substitution = if (a[i - 1] == b[j - 1]) 0 else 1
                var cost = min(
                    oneBack[i] + 1, // delete
                    min(current[i - 1] + 1, oneBack[i - 1] + substitution),
                )
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    cost = min(cost, twoBack[i - 2] + 1)
                }
                current[i] = cost
                if (cost < best) best = cost
            }
            // Every remaining row can only add to the best score in this one,
            // so once the whole row is out of range the answer is too.
            if (best > max) return tooFar

            val recycled = twoBack
            twoBack = oneBack
            oneBack = current
            current = recycled
        }

        val distance = oneBack[a.length]
        return if (distance > max) tooFar else distance
    }
}
