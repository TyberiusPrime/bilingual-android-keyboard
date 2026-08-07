package de.coonabibba.bikeyboard

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

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
    /**
     * How sharply confidence falls away with the cost of the slips (D34).
     *
     * A setting, so `var` — the dictionaries are read once per service and it
     * would be absurd to reload thirty-five thousand words because a slider
     * moved. Volatile because the settings screen writes it and the typing
     * thread reads it.
     */
    @Volatile var confidenceDecay: Float = DEFAULT_CONFIDENCE_DECAY,
) : SuggestionSource {

    private class Candidate(val word: String, val weight: Float, val language: Language?)

    override fun candidatesFor(word: CharSequence, touches: List<TypedTouch>): Candidates {
        val prefix = Folding.fold(word)
        if (prefix.length < MIN_PREFIX) return Candidates.NONE

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

        val correction = scanNearby(typed, touches, into = candidates)
        return Candidates(rank(typed, candidates), correction)
    }

    /**
     * The one search, and both things the keyboard does with it (D37).
     *
     * A word within a slip or two is two answers at once: a candidate for the
     * strip, and — if it is the best of them — what the space bar would
     * substitute. Producing them separately meant two scans per keystroke with
     * different reach, so the strip could not offer `don't` for `dont` even as
     * the space bar was about to insert it.
     *
     * The scan is skipped when there is nothing to learn: a word already spelled
     * exactly right is never replaced (D28), and once there are three
     * completions the strip has no room for a correction anyway.
     */
    private fun scanNearby(
        typed: String,
        touches: List<TypedTouch>,
        into: MutableList<Candidate>,
    ): Correction? {
        // D28's hard gate, and the reason the scan can often be skipped
        // outright: a word in either dictionary is a word, however rare.
        val correcting = !knowsExactly(typed)
        val filling = into.size < SuggestionSlots.CAPACITY
        if (!correcting && !filling) return null

        var best: Candidate? = null
        var bestScore = 0f
        // The typed word standing as it is, which is what a correction has to
        // beat rather than merely lead.
        var mass = UNKNOWN_WORD_PRIOR

        forEachNearby(typed, touches) { lexicon, index, cost ->
            val score = lexicon.weightAt(index) * exp(-confidenceDecay * cost)
            if (correcting) {
                mass += score
                if (score > bestScore) {
                    bestScore = score
                    best = Candidate(lexicon.wordAt(index), score, lexicon.language)
                }
            }
            // Cost zero is the word itself, which is not a correction. It gets
            // here because folding makes `uber` and `über` one lookup — and
            // `über`, at ACCENT, is a correction worth offering.
            if (filling && cost > 0f) {
                into += Candidate(lexicon.wordAt(index), score, lexicon.language)
            }
        }

        val winner = best ?: return null
        if (!correcting || winner.word.equals(typed, ignoreCase = true)) return null
        return Correction(
            text = applyTypedCase(winner.word, typed),
            original = typed,
            confidence = bestScore / mass,
            language = winner.language,
        )
    }

    /**
     * Every word that could have been traced by [path] (D39).
     *
     * The search is bounded by the two things a swipe says clearly. A finger
     * comes down deliberately and lifts deliberately, so the **first and last
     * letters** are near certain — and a first letter plus a last letter is a
     * slice of about three hundred words across both dictionaries, measured,
     * out of seventy thousand. Everything in between is a shape, and the shape
     * is only asked about once the slice is that small.
     *
     * Both endpoints admit their close neighbours as well, since a thumb that
     * starts a stroke is not always precise about where; that widens the slice
     * by roughly the square of the number admitted, which is why the count is
     * capped rather than merely thresholded.
     */
    override fun candidatesForGesture(path: GesturePath, keys: KeyGeometry): Candidates {
        if (keys.isEmpty) return Candidates.NONE
        val firsts = endpointLetters(path.startX, path.startY, keys)
        val lasts = endpointLetters(path.endX, path.endY, keys)
        if (firsts.isEmpty() || lasts.isEmpty()) return Candidates.NONE

        val decoder = GestureDecoder(keys)
        val candidates = mutableListOf<Candidate>()

        firsts.forEach { first ->
            val bucket = first.toString()

            personal.completions(bucket).forEach { word ->
                scoreGesture(decoder, path, word, lasts)?.let { cost ->
                    candidates += Candidate(word, gestureScore(PERSONAL_WEIGHT, cost), null)
                }
            }

            lexicons.forEach { lexicon ->
                for (index in lexicon.completions(bucket)) {
                    val word = lexicon.wordAt(index)
                    val cost = scoreGesture(decoder, path, word, lasts) ?: continue
                    candidates += Candidate(
                        word,
                        gestureScore(lexicon.weightAt(index), cost),
                        lexicon.language,
                    )
                }
            }
        }

        // No correction: a swipe has no original spelling to be corrected away
        // from, so there is nothing for D28's "never replace a word that is
        // already right" to protect. What to commit is the first suggestion,
        // and how sure that is, is its confidence — which is why the prior
        // matters here as much as it does for a typo. Without it a single
        // hopeless candidate would be the only thing on the table and would
        // therefore be certain.
        // The prior has to be on the same scale as the scores, or it stops
        // meaning anything: the weights are raised to a power here, so it is
        // too. Left as it is, it would be swamped and every stroke would come
        // back certain.
        val prior = exp(GESTURE_FREQUENCY_POWER * ln(UNKNOWN_WORD_PRIOR))
        return Candidates(rank("", candidates, prior = prior), correction = null)
    }

    /**
     * What tracing [word] would have cost, or null if it is not worth asking.
     *
     * Ordered by price. The last letter is one character comparison and throws
     * away about ninety-five percent of the bucket; the journey length is one
     * pass over the word and throws away most of the rest; only what survives
     * both gets the full path comparison.
     */
    private fun scoreGesture(
        decoder: GestureDecoder,
        path: GesturePath,
        word: String,
        lasts: Set<Char>,
    ): Float? {
        val last = word.lastOrNull() ?: return null
        if (Folding.foldChar(last) !in lasts) return null

        val ideal = decoder.idealLength(word)
        if (ideal == GestureDecoder.UNSWIPEABLE) return null
        if (ideal < path.length * GestureDecoder.MIN_LENGTH_RATIO) return null
        if (ideal > path.length * GestureDecoder.MAX_LENGTH_RATIO) return null

        val cost = decoder.cost(path, word)
        return if (cost > GestureDecoder.MAX_COST) null else cost
    }

    /**
     * The same shape as a typo's score — how common the word is, discounted
     * exponentially by how implausible the finger's route was — so that a swipe
     * and a tapped correction produce comparable confidences and one threshold
     * governs both (D33).
     */
    /**
     * How likely this word is, given the stroke.
     *
     * The same shape as a typo's score — how common the word is, discounted
     * exponentially by how implausible the finger's route was — with one
     * deliberate difference: **rarity counts for less.** A swipe is a whole
     * word's worth of geometric evidence, where a typo correction is working
     * from one or two characters, so the shape has earned the right to overrule
     * the frequency table further than it may there.
     *
     * That is not a thumb on the scale, it is a better fit: taking the square
     * root of the weight raised top-1 accuracy across the whole corpus, from
     * 95% to 96% on realistic traces and 93% to 95% on sloppy ones. It also
     * rescues words the corpus barely contains. `swiping` occurs 236 times in
     * 675 million words of film subtitles — its share is *smaller than
     * [UNKNOWN_WORD_PRIOR]*, so the keyboard rated "a word I have never heard
     * of" as likelier than the word itself, and no quality of trace could bring
     * it back.
     */
    private fun gestureScore(weight: Float, cost: Float): Float =
        exp(GESTURE_FREQUENCY_POWER * ln(weight) - confidenceDecay * cost)

    /**
     * The letters a stroke may have started or finished on: the nearest, plus
     * any close enough to be a genuine near miss.
     */
    private fun endpointLetters(x: Float, y: Float, keys: KeyGeometry): Set<Char> {
        val nearest = keys.nearestLetter(x, y) ?: return emptySet()
        val letters = LinkedHashSet<Char>()
        letters += Folding.foldChar(nearest)
        keys.alternatives(x, y, nearest).entries
            .filter { it.value <= GestureDecoder.ENDPOINT_REACH }
            .sortedBy { it.value }
            .forEach { (char, _) ->
                if (letters.size < GestureDecoder.MAX_ENDPOINT_LETTERS) letters += Folding.foldChar(char)
            }
        return letters
    }

    /**
     * Ranks the candidates and trims them to what the strip can show.
     *
     * [prior] is the standing chance that none of them is right, added to the
     * divisor only. A candidate set that is uniformly bad should not produce a
     * confident answer merely because it is the only set there is.
     */
    private fun rank(
        typed: String,
        candidates: List<Candidate>,
        prior: Float = 0f,
    ): List<Suggestion> {
        if (candidates.isEmpty()) return emptyList()

        // Confidence is the candidate's share of everything that matches: a
        // unigram P(word | what was typed so far). Crude, and honestly crude —
        // D3 wants a calibrated number and this is the most a lookup can say.
        var mass = prior
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
     * Every dictionary word within a slip or two of [typed], with what the slip
     * costs.
     *
     * [touches] must be one per character of [typed]. Where the keyboard has no
     * record of where the thumb was — a word the cursor jumped back to (D23) —
     * the caller supplies untouched ones and every substitution is full price.
     * That is the right answer rather than a degraded one: without the touches
     * there is nothing to say a slip was likelier than a decision.
     */
    private inline fun forEachNearby(
        typed: String,
        touches: List<TypedTouch>,
        onMatch: (lexicon: Lexicon, index: Int, cost: Float) -> Unit,
    ) {
        if (typed.length < MIN_CORRECTION_LENGTH) return
        if (touches.size != typed.length) return
        val folded = Folding.fold(typed)
        if (folded.isEmpty()) return

        searchPrefixes(folded, touches.first()).forEach { bucket ->
            lexicons.forEach { lexicon ->
                for (index in lexicon.completions(bucket)) {
                    val word = lexicon.wordAt(index)
                    // A length gap alone can put a word out of reach, and
                    // checking it is far cheaper than the distance itself.
                    if (abs(word.length - typed.length) > MAX_SLIP_COST) continue
                    val cost = SpatialEditDistance.between(touches, word, MAX_SLIP_COST)
                    if (cost > MAX_SLIP_COST) continue
                    onMatch(lexicon, index, cost)
                }
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
    /**
     * Which slices of the dictionary to search for a correction (D35, D37).
     *
     * The scan is bucketed by folded prefix, which for a long time meant a
     * mistyped first letter was simply out of reach: `xontinue` found nothing at
     * all, because nothing starting with `x` is within a slip of it. Scanning
     * the whole dictionary instead is the obvious fix and the wrong one — it is
     * twenty-five times the work, on every keystroke (D33).
     *
     * Three slices, and the last two are **two characters wide**, which is what
     * makes them nearly free:
     *
     * - **What was typed**, one character, because a slip anywhere after the
     *   first letter leaves that letter standing.
     * - **The first two letters swapped.** `hte` is not a mistyped `t` but a
     *   transposed one, and a transposed pair means the word begins `th` — so
     *   there is no reason to walk every word beginning with `t`. That
     *   distinction is worth a great deal in German, where `e` is the second
     *   letter of half the language: as a whole bucket it cost 53ms, as a
     *   two-character prefix it is a rounding error.
     * - **A near neighbour of the first press, then the second letter typed.**
     *   `x` and `c` are adjacent, so the touch on `xontinue` already carries `c`
     *   as a near miss, and the word it wants begins `co`. Only the closest
     *   neighbours are followed: a first letter a long way off scores so badly
     *   it could never clear the threshold, so its slice is work spent to
     *   produce a candidate that will be refused.
     *
     * The narrower slices assume the *other* of the first two letters came out
     * right, which is the difference between chasing one slip and chasing two.
     */
    private fun searchPrefixes(folded: String, first: TypedTouch): Set<String> {
        val prefixes = LinkedHashSet<String>()
        prefixes += folded.substring(0, 1)
        if (folded.length < 2) return prefixes

        val second = folded[1]
        prefixes += "$second${folded[0]}"

        first.alternatives.entries
            .filter { it.value <= FIRST_LETTER_REACH }
            .sortedBy { it.value }
            .forEach { (char, _) ->
                if (prefixes.size >= MAX_SEARCH_PREFIXES) return@forEach
                val letter = Folding.foldChar(char)
                if (letter.isLetter()) prefixes += "$letter$second"
            }
        return prefixes
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
         * Shorter than this and the keyboard has no business looking: half the
         * two- and three-letter strings are one slip from several words, so
         * both the offer and the replacement would be noise, and the typing is
         * over before the reading would be.
         *
         * One floor, where there were two — the strip refused under four and
         * the correction under three (D37). Nothing justified the gap; they
         * were written months apart.
         */
        const val MIN_CORRECTION_LENGTH = 3

        /**
         * The most implausible a set of slips may be and still be considered.
         * A little over one full-price substitution, so two cheap ones — a
         * neighbouring key and a skipped umlaut — stay in reach.
         */
        const val MAX_SLIP_COST = 1.6f

        /**
         * How fast confidence falls away with the cost of the slips, when
         * nobody has said otherwise. Tuned so that a neighbouring-key slip on a
         * common word clears a 90% threshold and a full-price substitution does
         * not — but see [confidenceDecay]: it is a slider now, because where
         * exactly that boundary should sit is a matter of how one thumb moves.
         */
        const val DEFAULT_CONFIDENCE_DECAY = 7f

        /**
         * The standing chance that a word nobody knows was meant exactly as
         * typed: a name, a codeword, a piece of jargon. Everything the
         * threshold does, it does relative to this number, so it is the most
         * load-bearing guess in the file — and under D8, which forbids learning
         * from anything but an explicit add, being wrong about it is expensive.
         */
        const val UNKNOWN_WORD_PRIOR = 1e-6f

        /**
         * How near a neighbour of the first press has to be for its bucket to
         * be worth scanning (D35).
         *
         * Half a key width. Beyond that the substitution is dear enough that
         * the candidate loses to the unknown-word prior anyway — measured:
         * `zomorrow` reaches `tomorrow` and is scored at 0.40, well under any
         * threshold worth having. Refusing to look is the same answer for a
         * fraction of the work.
         */
        const val FIRST_LETTER_REACH = 0.5f

        /**
         * How much a word's rarity counts against it when swiping. One would
         * be the plain corpus share, as tapping uses; a half is its square
         * root. Measured, not picked — see [gestureScore].
         */
        const val GESTURE_FREQUENCY_POWER = 0.5f

        /** A ceiling on the scan, however ambiguous the first touch was. */
        const val MAX_SEARCH_PREFIXES = 4

    }
}
