package de.coonabibba.bikeyboard

/**
 * Suggestions from the shipped wordlists and the personal store.
 *
 * Roadmap step 3, and deliberately the dumb version: completions of what has
 * been typed, ranked by how common the word is. No edit distance, no context,
 * no model. What it does have is the property the whole project is about —
 * **both languages are queried on every keystroke and compete on one scale**
 * (D1, D2). There is no current language, and a German word can win a slot from
 * an English one halfway through a sentence without anything switching.
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

    private class Candidate(val word: String, val weight: Float)

    override fun suggest(word: CharSequence): List<Suggestion> {
        val prefix = Folding.fold(word)
        if (prefix.length < MIN_PREFIX) return emptyList()

        val typed = word.toString()
        val candidates = mutableListOf<Candidate>()

        // The user's own words first, and they mostly win: PERSONAL_WEIGHT puts
        // them above everything except the few hundred commonest words of
        // either language. They are here because someone asked for them.
        personal.completions(prefix).forEach { candidates += Candidate(it, PERSONAL_WEIGHT) }

        lexicons.forEach { lexicon ->
            for (index in lexicon.completions(prefix)) {
                candidates += Candidate(lexicon.wordAt(index), lexicon.weightAt(index))
            }
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
            .map { Suggestion(applyTypedCase(it.word, typed), it.weight / mass) }
            // Offering back exactly what is already there wastes a slot.
            .filter { it.text != typed }
            .distinctBy { it.text }
            .take(SuggestionSlots.CAPACITY)
            .toList()
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
    }
}
