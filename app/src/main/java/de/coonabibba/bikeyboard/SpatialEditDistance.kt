package de.coonabibba.bikeyboard

import kotlin.math.abs
import kotlin.math.min

/**
 * How far a candidate word is from what was actually typed, in *slips* rather
 * than in edits.
 *
 * [EditDistance] counts one for any substitution, which is the right answer to
 * "are these two words similar" and the wrong answer to "did the typist mean
 * this". Hitting `k` when `l` was meant, with the touch landing on the border
 * between them, is not the same event as typing `z`; a keyboard confident
 * enough to replace a word silently (D3) has to be able to tell them apart.
 *
 * So substitution costs come from the touches ([TypedTouch]), and insertions
 * and deletions keep costing one. The result is a real number, and the
 * threshold that decides an auto-correction is a threshold on the score built
 * from it (D28).
 */
object SpatialEditDistance {

    /**
     * The cost of turning [typed] into [candidate], or [budget] + 1 when it
     * exceeds [budget] — which lets the caller stop paying for words that were
     * never going to win.
     *
     * [candidate] is compared in lower case; the touches carry the characters
     * as they were typed.
     */
    fun between(typed: List<TypedTouch>, candidate: String, budget: Float): Float {
        val tooFar = budget + 1f
        // Each insertion or deletion costs a whole one, so a length difference
        // alone can put a word out of reach.
        if (abs(typed.size - candidate.length) > budget) return tooFar

        var previous = FloatArray(candidate.length + 1) { it.toFloat() }
        var current = FloatArray(candidate.length + 1)
        var beforePrevious = FloatArray(candidate.length + 1)

        for (i in 1..typed.size) {
            current[0] = i.toFloat()
            var best = current[0]
            for (j in 1..candidate.length) {
                val substitution = previous[j - 1] + substitutionCost(typed[i - 1], candidate[j - 1])
                val deletion = previous[j] + 1f
                val insertion = current[j - 1] + insertionCost(candidate[j - 1])
                var cost = min(substitution, min(deletion, insertion))

                // A transposition is one slip, not two: `teh` for `the` is the
                // commonest way a thumb misses.
                if (i > 1 && j > 1 &&
                    matches(typed[i - 1], candidate[j - 2]) &&
                    matches(typed[i - 2], candidate[j - 1])
                ) {
                    cost = min(cost, beforePrevious[j - 2] + TRANSPOSITION)
                }

                current[j] = cost
                if (cost < best) best = cost
            }
            if (best > budget) return tooFar

            val recycled = beforePrevious
            beforePrevious = previous
            previous = current
            current = recycled
        }

        val total = previous[candidate.length]
        return if (total > budget) tooFar else total
    }

    /**
     * What it costs to read [touch] as [candidate].
     *
     * Nothing if they are the same letter. Almost nothing if they differ only
     * by an accent — that is D5's cheap win, since the umlaut costs a
     * long-press and skipping it is a decision rather than a mistake. Otherwise
     * whatever the touch says, which is small for a neighbouring key and full
     * price for anything else.
     */
    private fun substitutionCost(touch: TypedTouch, candidate: Char): Float {
        val lowered = candidate.lowercaseChar()
        val typed = touch.char.lowercaseChar()
        if (typed == lowered) return 0f
        if (Folding.foldChar(typed) == Folding.foldChar(lowered)) return ACCENT
        return touch.costOf(lowered)
    }

    /**
     * What it costs for the candidate to contain a character that was not typed
     * at all.
     *
     * A whole one, except for the apostrophe, which is nearly free (D34). The
     * argument is D5's, applied to the other character this keyboard makes
     * expensive: `'` is a long-press on `v`, so leaving it out of `dont` is a
     * decision about effort rather than a mistake about spelling. D6 said from
     * the start that correction was expected to place apostrophes in
     * contractions unprompted, and this is the line that lets it.
     *
     * Dearer than [ACCENT] on purpose. A skipped umlaut still types a letter,
     * so there is a touch to reason about; a skipped apostrophe leaves no
     * evidence at all, and the only thing arguing for it is the dictionary.
     */
    private fun insertionCost(candidate: Char): Float =
        if (candidate == '\'') APOSTROPHE else 1f

    private fun matches(touch: TypedTouch, candidate: Char): Boolean =
        touch.char.lowercaseChar() == candidate.lowercaseChar()

    /** `über` for `uber`: a skipped long-press, not a mistake (D5). */
    private const val ACCENT = 0.1f

    /** `don't` for `dont`: the other long-press worth skipping (D34). */
    private const val APOSTROPHE = 0.15f

    /** Two letters the right way round in the wrong order. One slip. */
    private const val TRANSPOSITION = 0.7f
}
