package de.coonabibba.bikeyboard

import kotlin.math.exp
import kotlin.math.ln

/**
 * Whether a string *looks like* a word in these two languages, from character
 * trigrams (D43).
 *
 * It exists to answer one question the corrector could not: **how likely is it
 * that what was typed is a real word nobody has told the keyboard about?** D3's
 * confidence is a share of everything on the table, and that table has always
 * included a standing prior for "a name, a codeword, jargon" — the thing that
 * keeps the keyboard off words it has never heard of. But the prior was a flat
 * constant, which says `hsnging` is as plausibly somebody's surname as
 * `Coonabibba` is, and that is plainly false. `hsn` occurs in no German or
 * English word; `oon`, `nab` and `bba` all do.
 *
 * The cost of the constant was measurable and severe. `hanging` was the *only*
 * candidate offered for `hsnging`, and was scored at 0.045 against a threshold
 * of 0.90, because the flat prior outweighed it twenty to one. Nothing was
 * wrong with the correction; the thing it had to beat was wrong.
 *
 * **Built from the wordlists at startup rather than shipped.** They are already
 * being read and parsed, counting trigrams over them costs one more pass, and
 * an asset would need a format, a provenance note and a way to stay in step
 * with the lists it was derived from. Weighted by how common each word is, so
 * this describes the shape of the language as *used* rather than as listed.
 */
class WordShape private constructor(
    private val logProbability: FloatArray,
    private val averagePerCharacter: Float,
    private val maxSwing: Float,
) {

    /**
     * How plausible [word] is as an unknown word, as a multiplier on the
     * standing prior.
     *
     * **At most one, and that asymmetry is deliberate.** A string that is
     * word-shaped keeps exactly the protection it has today and gains none, so
     * nothing this model believes can make the keyboard correct *less* than it
     * already does — every change it can produce is in the direction of
     * correcting an obvious typo that was previously left alone. The reason is
     * that plausible-looking typos are the common kind: `teh` is word-shaped
     * (German `stehen` has it), and rewarding it for that would weaken the most
     * valuable correction in the language.
     */
    fun plausibility(word: CharSequence): Float {
        if (word.isEmpty()) return 1f
        var previous = BOUNDARY
        var current = BOUNDARY
        var total = 0f
        var count = 0

        for (index in 0..word.length) {
            // One past the end is the closing boundary, which is what makes an
            // ending like `-ng` cheap and `-hs` dear.
            val symbol = if (index == word.length) BOUNDARY else symbolOf(word[index])
            total += logProbability[slotOf(previous, current, symbol)]
            previous = current
            current = symbol
            count++
        }

        val relative = total - count * averagePerCharacter
        return exp(relative.coerceIn(-maxSwing, 0f))
    }

    companion object {
        /** 26 letters and a boundary, which stands for both ends and anything odd. */
        private const val SYMBOLS = 27
        private const val BOUNDARY = 0
        private const val TABLE = SYMBOLS * SYMBOLS * SYMBOLS

        /**
         * How far below average a string's plausibility may fall, in nats.
         *
         * A floor on how sure this may ever be. It is a crude model of a
         * language's shape and should be able to argue that a string is
         * unlikely, not to declare it impossible — being wrong in that
         * direction corrects somebody's name away, which under D8 there is no
         * way back from but adding the word by hand.
         *
         * **Seven nats — about eleven hundred to one — because that is where
         * the two curves part**, per `NameProtectionTest`, which scores
         * fifty-odd names and jargon words against a set of real slips in both
         * languages. Deeper floors buy almost no extra typos and lose names
         * fast; shallower ones start dropping corrections:
         *
         * | floor | typos fixed | names lost |
         * |-------|-------------|------------|
         * | 12    | 17          | 4          |
         * | 9     | 17          | 3          |
         * | **7** | **16**      | **1**      |
         * | 6     | 13          | 1          |
         * | 5     | 10          | 1          |
         *
         * Both dials are arguments to [of] rather than bare constants so that
         * the test can re-run the sweep instead of quoting a number nobody can
         * reproduce.
         *
         * The one name still lost at seven is `Jost`, which becomes `Just` — a
         * four-letter name one slip from a word in the commonest hundred, and
         * already at 0.73 before any of this. Short names are exposed and no
         * setting here fixes that; the personal store (D40) does.
         */
        private const val MAX_SWING = 7f

        /**
         * Pretend every trigram was seen this much, as a share of what an
         * average cell of the table holds.
         *
         * Between one and a hundredth the answers barely move — see
         * `WordShapeTest` — because what matters is that an unseen trigram is
         * *rare*, not exactly how rare. Half sits in the middle of that plateau.
         */
        private const val SMOOTHING = 0.5f

        /**
         * How many words it takes before this is worth building at all.
         *
         * The table has nineteen thousand cells. A handful of words populates a
         * handful of them and leaves the rest to the smoothing, and what comes
         * out then describes *that handful* rather than the language: every
         * ordinary word looks implausible next to it, and the prior collapses
         * for strings it has no business doubting. So below this the model
         * declines to have an opinion and everything is average — which is also
         * the right answer if a wordlist asset ever fails to load, where the
         * alternative is a keyboard that rewrites text on the strength of
         * nothing.
         */
        private const val MIN_WORDS = 1_000

        private fun slotOf(previous: Int, current: Int, symbol: Int) =
            (previous * SYMBOLS + current) * SYMBOLS + symbol

        private fun symbolOf(char: Char): Int {
            val folded = Folding.foldChar(char)
            return if (folded in 'a'..'z') folded - 'a' + 1 else BOUNDARY
        }

        /**
         * Counts trigrams across every word in [lexicons], weighted by how
         * common the word is.
         */
        fun of(
            lexicons: List<Lexicon>,
            smoothing: Float = SMOOTHING,
            maxSwing: Float = MAX_SWING,
        ): WordShape {
            if (lexicons.sumOf { it.size } < MIN_WORDS) return FLAT
            val counts = FloatArray(TABLE)
            val contexts = FloatArray(SYMBOLS * SYMBOLS)

            lexicons.forEach { lexicon ->
                for (index in 0 until lexicon.size) {
                    val word = lexicon.wordAt(index)
                    val weight = lexicon.weightAt(index)
                    var previous = BOUNDARY
                    var current = BOUNDARY
                    for (position in 0..word.length) {
                        val symbol =
                            if (position == word.length) BOUNDARY else symbolOf(word[position])
                        counts[slotOf(previous, current, symbol)] += weight
                        contexts[previous * SYMBOLS + current] += weight
                        previous = current
                        current = symbol
                    }
                }
            }

            // The weights are corpus *shares*, so their total is a small number
            // with no natural relation to a count of anything, and a pseudo-count
            // fixed in absolute terms would either vanish or swamp the lot
            // depending on how the wordlists happened to be scaled. Expressed
            // as a share of what an average cell of the table holds, it means
            // the same thing whatever the units.
            var total = 0f
            for (context in contexts) total += context
            val pseudo = smoothing * total / TABLE

            val logProbability = FloatArray(TABLE)
            for (context in 0 until SYMBOLS * SYMBOLS) {
                val seen = contexts[context] + pseudo * SYMBOLS
                for (symbol in 0 until SYMBOLS) {
                    val slot = context * SYMBOLS + symbol
                    logProbability[slot] = ln((counts[slot] + pseudo) / seen)
                }
            }

            // What an average character of an average word costs, so that
            // plausibility can be quoted relative to it rather than in absolute
            // numbers that would drift with the size of the wordlists. This is
            // the corpus average exactly, not an estimate of it: summing over
            // the counts is the same sum as walking every word again, since
            // that is where the counts came from.
            var weighted = 0f
            for (slot in 0 until TABLE) weighted += counts[slot] * logProbability[slot]

            return WordShape(logProbability, if (total > 0f) weighted / total else 0f, maxSwing)
        }

        /** For sources with no lexicons to learn from: everything is average. */
        val FLAT = WordShape(FloatArray(TABLE), 0f, MAX_SWING)
    }
}
