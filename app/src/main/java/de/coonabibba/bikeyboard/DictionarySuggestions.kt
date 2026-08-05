package de.coonabibba.bikeyboard

import kotlin.math.abs

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

        /**
         * Below this, a word is too short to correct: nearly every three-letter
         * word is one edit from several others, so the suggestions would be
         * noise and the typing is quicker than reading them.
         */
        const val MIN_CORRECTION_LENGTH = 4

        /** From here up, two edits are allowed. Below it, one. */
        const val LONG_WORD = 6

        /**
         * What a correction is worth against a word that was typed correctly,
         * by distance. Steep, so a completion always wins a slot from a
         * correction, and a near miss always wins from a far one.
         */
        val CORRECTION_PENALTY = floatArrayOf(50f, 2_500f)
    }
}
