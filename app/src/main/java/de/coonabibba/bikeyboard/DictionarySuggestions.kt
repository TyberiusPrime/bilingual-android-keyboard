package de.coonabibba.bikeyboard

import kotlin.math.abs
import kotlin.math.exp

/**
 * Suggestions from the shipped wordlists and the personal store.
 *
 * Roadmap step 3, and deliberately the dumb version: completions of what has
 * been typed, ranked by how common the word is, plus words a couple of edits
 * away when completion finds nothing (D23). No context, no model. What it does
 * have is the property the whole project is about — **both languages are
 * queried on every keystroke and compete on one scale** (D1, D2). There is no
 * current language, and a German word can win a slot from an English one
 * halfway through a sentence without anything switching.
 *
 * Two smaller things fall out of the design decisions:
 *
 * - Matching is folded (D5): typing `uber` finds `über`, so the long-press an
 *   umlaut costs can be skipped and recovered from the strip.
 * - The first letter is put back into the case the typist used, so `Zei`
 *   completes to `Zeit` while `zei` completes to `zeit`. The wordlists store one
 *   casing per word; this is what makes that survivable.
 */
class DictionarySuggestions(
    private val lexicons: List<Lexicon>,
    private val personal: PersonalStore,
) : SuggestionSource {

    private class Candidate(val word: String, val weight: Float, val language: Language?)

    override fun suggest(word: CharSequence): List<Suggestion> {
        val prefix = Folding.fold(word)
        if (prefix.length < MIN_PREFIX) return emptyList()

        val typed = word.toString()
        val candidates = mutableListOf<Candidate>()

        // The user's own words first, and they mostly win: PERSONAL_WEIGHT puts
        // them above everything except the few hundred commonest words of
        // either language. They are here because someone asked for them.
        personal.completions(prefix).forEach {
            candidates += Candidate(it, PERSONAL_WEIGHT, language = null)
        }

        lexicons.forEach { lexicon ->
            for (index in lexicon.completions(prefix)) {
                candidates += Candidate(
                    lexicon.wordAt(index),
                    lexicon.weightAt(index),
                    lexicon.language,
                )
            }
        }

        addApostropheS(typed, candidates)

        // Completions only answer a word that was begun correctly. When there
        // are few or none — a typo, or a whole word the cursor jumped back to
        // (D23) — look for words a small number of edits away instead.
        if (candidates.size < SuggestionSlots.CAPACITY) {
            addCorrections(prefix, candidates)
        }

        if (candidates.isEmpty()) return emptyList()

        // Confidence is the candidate's share of everything that matches: a
        // unigram P(word | what was typed so far). Crude, and honestly crude —
        // D3 wants a calibrated number and this is the most a lookup can say.
        var mass = 0f
        candidates.forEach { mass += it.weight }
        if (mass <= 0f) return emptyList()

        return candidates
            .sortedByDescending { it.weight }
            .asSequence()
            .map { Suggestion(applyTypedCase(it.word, typed), it.weight / mass, it.language) }
            // Offering back exactly what is already there wastes a slot.
            .filter { it.text != typed }
            .distinctBy { it.text }
            .take(SuggestionSlots.CAPACITY)
            .toList()
    }

    /**
     * `letvs` means `let's`, and so does `gehtvs` mean `geht's` (D27).
     *
     * The apostrophe is the long-press alternate on `v` (D17), so the way to
     * miss it is to tap the key instead of holding it — and the letters that
     * follow are almost always `s`. A word ending in `vs` is otherwise close to
     * nonexistent, which is what makes the rule safe enough to apply blindly.
     *
     * It has to *build* the candidate rather than look it up: the wordlists
     * carry no contractions at all, because the corpus their frequencies come
     * from split `don't` into `don` and `t` before counting (see
     * PROVENANCE.md). So the stem is looked up, and the apostrophe is added to
     * the spelling the dictionary has.
     */
    private fun addApostropheS(typed: String, into: MutableList<Candidate>) {
        if (typed.length < STEM_MIN + 2) return
        if (!typed.regionMatches(typed.length - 2, "vs", 0, 2, ignoreCase = true)) return
        val stem = typed.substring(0, typed.length - 2)

        personal.completions(Folding.fold(stem))
            .firstOrNull { Folding.fold(it) == Folding.fold(stem) }
            ?.let { into += Candidate("$it's", PERSONAL_WEIGHT, language = null) }

        lexicons.forEach { lexicon ->
            val index = lexicon.indexOf(stem)
            if (index < 0) return@forEach
            // The stem's own weight: `let's` is about as likely as `let` was,
            // and it should out-rank anything the typo search turns up.
            into += Candidate(
                "${lexicon.wordAt(index)}'s",
                lexicon.weightAt(index),
                lexicon.language,
            )
        }
    }

    /**
     * Words a few edits away from what was typed.
     *
     * Scanned rather than indexed: a deletion index over 70,000 words costs
     * more memory than the wordlists themselves, and two filters make the scan
     * cheap enough without it. Only words sharing the typed first letter are
     * considered — which is the one letter a thumb rarely gets wrong, and is
     * the honest limit of this approach — and within those, only the ones whose
     * length is close enough to be reachable. Folding, the expensive part, then
     * happens for a few hundred words rather than a few thousand.
     */
    private fun addCorrections(folded: String, into: MutableList<Candidate>) {
        if (folded.length < MIN_CORRECTION_LENGTH) return
        val maxDistance = if (folded.length >= LONG_WORD) 2 else 1
        val bucket = folded.substring(0, 1)

        lexicons.forEach { lexicon ->
            for (index in lexicon.completions(bucket)) {
                val word = lexicon.wordAt(index)
                if (abs(word.length - folded.length) > maxDistance) continue
                val distance = EditDistance.between(Folding.fold(word), folded, maxDistance)
                if (distance !in 1..maxDistance) continue
                // A correction is worth less than a word that was typed
                // correctly, and worth less the further away it is. This keeps
                // corrections under completions when both are on offer, and
                // orders them by how common the word is within each distance.
                into += Candidate(
                    word,
                    lexicon.weightAt(index) / CORRECTION_PENALTY[distance - 1],
                    lexicon.language,
                )
            }
        }
    }

    override fun knows(word: CharSequence): Boolean =
        personal.knows(word) || lexicons.any { it.knows(word) }

    /**
     * What to replace a finished word with, and how sure that is (D28).
     *
     * Every candidate is scored as *how likely is it that this word was meant*:
     * how common the word is, discounted by how implausible the slips would
     * have to be. The discount is exponential in the spatial cost
     * ([SpatialEditDistance]), so a letter the thumb was already touching costs
     * almost nothing and a letter on the other side of the keyboard is out of
     * reach however common the word.
     *
     * The confidence is that score as a share of everything on the table,
     * including **the possibility that the word was typed correctly and is
     * simply not in any dictionary** — a name, a codeword, jargon. That prior
     * is what keeps the keyboard off words it has never heard of, and it is why
     * the number can be compared against a threshold at all.
     *
     * Two hard rules sit in front of the arithmetic:
     *
     * - **A word spelled exactly right is never corrected**, even if it is rare
     *   and even if a far more common word is one slip away. Under D2 a word
     *   valid in the other language is valid, full stop; that is the failure
     *   mode most likely to make this feature the thing it was meant to fix.
     * - **No touches, no correction.** Without knowing where the thumb landed
     *   there is no way to tell a slip from a decision, and a word the cursor
     *   jumped back to (D23) was not typed here at all.
     */
    override fun correct(typed: String, touches: List<TypedTouch>): Correction? {
        if (typed.length < MIN_AUTO_CORRECT_LENGTH) return null
        if (touches.size != typed.length) return null
        if (knowsExactly(typed)) return null

        val folded = Folding.fold(typed)
        if (folded.isEmpty()) return null
        val bucket = folded.substring(0, 1)

        var best: Candidate? = null
        var bestScore = 0f
        // The typed word standing as it is, which is what the correction has to
        // beat rather than merely lead.
        var mass = UNKNOWN_WORD_PRIOR

        lexicons.forEach { lexicon ->
            for (index in lexicon.completions(bucket)) {
                val word = lexicon.wordAt(index)
                if (abs(word.length - typed.length) > MAX_SLIP_COST) continue
                val cost = SpatialEditDistance.between(touches, word, MAX_SLIP_COST)
                if (cost > MAX_SLIP_COST) continue
                val score = lexicon.weightAt(index) * exp(-CONFIDENCE_DECAY * cost)
                mass += score
                if (score > bestScore) {
                    bestScore = score
                    best = Candidate(word, score, lexicon.language)
                }
            }
        }

        val winner = best ?: return null
        if (winner.word.equals(typed, ignoreCase = true)) return null
        return Correction(
            text = applyTypedCase(winner.word, typed),
            original = typed,
            confidence = bestScore / mass,
            language = winner.language,
        )
    }

    /** Whether any source has this exact spelling — see [Lexicon.knowsExactly]. */
    private fun knowsExactly(word: String): Boolean =
        personal.knowsExactly(word) || lexicons.any { it.knowsExactly(word) }

    /**
     * The typist decides the first letter's case; the wordlist decides the rest.
     *
     * Only the first letter, and only upwards: a word stored capitalised stays
     * capitalised, because German nouns are not optional.
     */
    private fun applyTypedCase(candidate: String, typed: String): String {
        val first = typed.firstOrNull() ?: return candidate
        if (!first.isUpperCase()) return candidate
        return candidate.replaceFirstChar { it.uppercaseChar() }
    }

    private companion object {
        /**
         * One letter is not evidence of anything; two is enough to be
         * completing rather than guessing.
         */
        const val MIN_PREFIX = 2

        /**
         * What a personal word is assumed to be worth, as a share of a corpus.
         * Roughly the weight of the two-hundredth most common word — enough to
         * beat ordinary vocabulary, not enough to displace `the` or `ich`.
         */
        const val PERSONAL_WEIGHT = 1e-3f

        /** A one-letter stem in front of an apostrophe is a typo, not a word. */
        const val STEM_MIN = 2

        /**
         * Below this, a word is too short to correct: nearly every three-letter
         * word is one edit from several others, so the suggestions would be
         * noise and the typing is quicker than reading them.
         */
        const val MIN_CORRECTION_LENGTH = 4

        /** From here up, two edits are allowed. Below it, one. */
        const val LONG_WORD = 6

        /**
         * Shorter than this and the keyboard has no business replacing
         * anything: half the two- and three-letter strings are one slip from
         * several words, and the typing is over before the reading would be.
         */
        const val MIN_AUTO_CORRECT_LENGTH = 3

        /**
         * The most implausible a set of slips may be and still be considered.
         * A little over one full-price substitution, so two cheap ones — a
         * neighbouring key and a skipped umlaut — stay in reach.
         */
        const val MAX_SLIP_COST = 1.6f

        /**
         * How fast confidence falls away with the cost of the slips. Tuned so
         * that a neighbouring-key slip on a common word clears a 90% threshold
         * and a full-price substitution does not.
         */
        const val CONFIDENCE_DECAY = 7f

        /**
         * The standing chance that a word nobody knows was meant exactly as
         * typed: a name, a codeword, a piece of jargon. Everything the
         * threshold does, it does relative to this number, so it is the most
         * load-bearing guess in the file — and under D8, which forbids learning
         * from anything but an explicit add, being wrong about it is expensive.
         */
        const val UNKNOWN_WORD_PRIOR = 1e-6f

        /**
         * What a correction is worth against a word that was typed correctly,
         * by distance. Steep, so a completion always wins a slot from a
         * correction, and a near miss always wins from a far one.
         */
        val CORRECTION_PENALTY = floatArrayOf(50f, 2_500f)
    }
}
