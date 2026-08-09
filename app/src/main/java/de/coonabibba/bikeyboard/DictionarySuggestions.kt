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
    /**
     * How often one word follows another, per language (D46).
     *
     * Optional, and absent by default: every test fixture and the first moment
     * of a session run without one, and the only thing that changes is that the
     * strip goes back to being empty between words.
     */
    private val bigrams: Map<Language, BigramStore> = emptyMap(),
    /**
     * How much of a candidate's score comes from the word before it (D47).
     *
     * A parameter so the sweep that chose it can be re-run against the real
     * wordlists rather than re-derived by hand; not a setting, because unlike
     * D34's falloff this one is not about how a particular thumb moves.
     */
    private val contextWeight: Float = CONTEXT_WEIGHT,
) : SuggestionSource {

    /**
     * The shape of the two languages, for judging whether an unrecognised
     * string could be a word (D43).
     *
     * Built here rather than passed in, because it is a function of the
     * lexicons and nothing else — anyone holding the one holds the other. Built
     * eagerly, because this class is constructed on the disk thread right after
     * the wordlists are parsed, so the one pass it costs lands where every
     * other startup cost already does, and never on a keystroke.
     */
    private val wordShape = WordShape.of(lexicons)

    /**
     * The chance that [typed] is a real word nobody knows, which is what any
     * correction has to beat (D43).
     *
     * [UNKNOWN_WORD_PRIOR] is the standing figure for an average-looking
     * string; this discounts it for one that does not look like a word of
     * either language. Never raises it — see [WordShape.plausibility].
     */
    private fun priorFor(typed: CharSequence): Float =
        UNKNOWN_WORD_PRIOR * wordShape.plausibility(typed)

    private class Candidate(
        val word: String,
        val weight: Float,
        val language: Language?,
        /** How badly the stroke fitted, for a swipe. Meaningless for a tap. */
        val cost: Float = 0f,
        /**
         * Where the word sits in its lexicon, or -1 for one that has no
         * lexicon — a personal word, or a form built rather than looked up.
         *
         * Carried so that asking the bigram store about it is an array index
         * rather than a search: [Lexicon.indexOf] folds every word it compares,
         * and doing that for two dozen candidates on every keystroke was the
         * whole remaining cost of the context lookup (D47).
         */
        val index: Int = -1,
    )

    /**
     * One word, however many lexicons happen to carry it (D45).
     *
     * Two thousand nine hundred spellings are in both wordlists — a fifth of
     * the typing mass of either language — and until this ran they arrived as
     * two rival candidates. Both went into the confidence divisor and only one
     * could be the numerator, so a word in both languages was measurably harder
     * to correct *to* than a word in one: `hnad` reached `hand` at 0.37 where
     * `wrold` reached `world` at 0.99, the same transposition against the same
     * falloff.
     *
     * Summing is not a thumb on the scale. "The typist meant German `Hand`" and
     * "the typist meant English `hand`" both end with the same letters on the
     * screen, so the chance the replacement is right is the chance of either.
     */
    private fun merge(candidates: List<Candidate>): List<Candidate> {
        if (candidates.size < 2) return candidates
        val merged = LinkedHashMap<String, Candidate>(candidates.size)
        candidates.forEach { candidate ->
            val key = keyOf(candidate.word)
            val existing = merged[key]
            merged[key] = if (existing == null) candidate else join(existing, candidate)
        }
        return if (merged.size == candidates.size) candidates else merged.values.toList()
    }

    /**
     * What counts as the same word for merging: the folded spelling, so that
     * `Hand` and `hand` are one entry.
     *
     * **A single letter is keyed by its exact spelling instead**, because for
     * one letter the casing is not a detail of the word, it is the word: `i`
     * and `I` are German and English respectively and D41 exists to choose
     * between them. Folding them together would leave one candidate and nothing
     * to correct.
     */
    private fun keyOf(word: String): String =
        if (word.length == 1) word else Folding.fold(word)

    /**
     * Two entries for one word, added up.
     *
     * **Which spelling survives** is the only real decision here, and it is
     * D22's rule — the least capitalised — reached from the other side. That
     * rule kept one casing per word *within* a list; it says nothing about a
     * German noun meeting its English twin, which is why `Moment` (commoner in
     * German) was being handed to people writing English. The typist supplies
     * the capital, as they already do for `Zeit`.
     *
     * Only when the two differ by nothing but case. `weiß` against `Weiss` is
     * not one spelling of one word, so that stays settled by weight, as before.
     *
     * **The language goes neutral**, because it is now the honest answer: a
     * word both lists carry is not evidence of either, and D4's indicator
     * exists to explain corrections rather than to pick a side.
     */
    private fun join(a: Candidate, b: Candidate): Candidate {
        val surface = when {
            !a.word.equals(b.word, ignoreCase = true) -> if (b.weight > a.weight) b.word else a.word
            b.word.firstOrNull()?.isLowerCase() == true -> b.word
            else -> a.word
        }
        return Candidate(
            surface,
            a.weight + b.weight,
            if (a.language == b.language) a.language else null,
            minOf(a.cost, b.cost),
        )
    }

    override fun candidatesFor(
        word: CharSequence,
        touches: List<TypedTouch>,
        preceding: Preceding,
    ): Candidates {
        val prefix = Folding.fold(word)
        if (prefix.isEmpty()) return Candidates.NONE

        val typed = word.toString()
        val candidates = mutableListOf<Candidate>()

        // **One letter completes now** (D47). It used to take two, on the
        // grounds that one letter is not evidence of anything and its
        // candidates are most of the alphabet's worth of words. Both halves of
        // that were true and the first has stopped being so: with the word
        // before it to go on, one letter is frequently decisive — after
        // `vielen`, a `d` is `dank` and nothing else. The second half is now a
        // cost to be paid rather than a reason not to, and it is paid in
        // [completionsFor].
        //
        // This is also the stretch of the word where nothing else helps:
        // correction needs three characters (MIN_CORRECTION_LENGTH) and has
        // nothing to say before then.
        val context = contextFor(preceding)
        var dropped = 0f
        if (prefix.length >= MIN_PREFIX) {
            // The user's own words first, and they mostly win: PERSONAL_WEIGHT
            // puts them above everything except the few hundred commonest words
            // of either language. They are here because someone asked for them.
            personal.completions(prefix).forEach {
                candidates += Candidate(it, PERSONAL_WEIGHT, language = null)
            }
            dropped = completionsFor(prefix, context, candidates)
        }

        // A capital in the middle says the typist meant every letter of this
        // (D44). Candidates are still gathered — the strip may as well be
        // useful — but nothing here will be replaced.
        val named = TextEdits.hasInternalCapital(typed)

        val cased = addCasedForms(typed, candidates)
        addApostropheS(typed, candidates)
        val apostrophe = addApostropheForms(typed, candidates)
        val nearby = scanNearby(typed, touches, named = named, into = candidates)
        val correction =
            if (named) null
            else listOfNotNull(cased, apostrophe, nearby).maxByOrNull { it.confidence }

        // A word the keyboard is about to change is worth offering back, but
        // only when the spelling actually is a word in one of the languages.
        // `i` is: German has it and English has `I`, and which one was meant is
        // the typist's business. `teh` is not, and a slot spent offering it
        // back would be a slot wasted.
        val keepTyped = correction != null && candidates.any { it.word == typed }
        return Candidates(rank(typed, candidates, context, dropped, keepTyped), correction)
    }

    /**
     * Every completion of [prefix], scored by frequency and by what the word
     * before it makes likely (D47).
     *
     * **Interpolated, never replaced** — D46's rule, and the reason this cannot
     * make the strip worse than it was:
     *
     *     score(w) = λ · P(w | previous) + (1 − λ) · P(w)
     *
     * A word the bigram store has never seen after this context keeps its full
     * `(1 − λ)` share of the unigram weight, so it can be pushed down the order
     * but never out of existence, and with no context at all the ranking is
     * exactly what it was before. The bigram side carries the same
     * per-language mixture [predict] uses, so which language answers is still
     * decided by the last word rather than by a mode (D2).
     *
     * **Only the top [KEEP] survive**, by score, and the rest have their weight
     * added to the divisor rather than being discarded — the same bargain the
     * swipe path's pruning makes, and with the same guarantee that the effect
     * is to sound less certain rather than more. That is what makes one letter
     * affordable: `s` matches 3,662 German words and 4,187 English ones, and
     * building eight thousand candidates to show three of them is most of a
     * millisecond spent on the first keystroke of every word.
     *
     * Returns the weight of everything left behind.
     */
    private fun completionsFor(
        prefix: String,
        context: Map<Language, ContextRow>,
        into: MutableList<Candidate>,
    ): Float {
        var dropped = 0f
        lexicons.forEach { lexicon ->
            val row = context[lexicon.language]
            val best = TopCandidates(KEEP)
            val range = lexicon.completions(prefix)
            // Every completion of a prefix is one contiguous run of indices,
            // because the wordlist is sorted by folded form — so the context's
            // followers can be merged into it in one walk rather than searched
            // once per candidate. That is the difference between 1.4ms and a
            // rounding error on the first keystroke of a word (D47).
            val cursor = row?.store?.Cursor(row.context, range.first)
            for (index in range) {
                val unigram = lexicon.weightAt(index)
                val score = if (cursor == null) {
                    unigram
                } else {
                    contextWeight * row.vote * cursor.probabilityOf(index) +
                        (1f - contextWeight) * unigram
                }
                dropped += best.offer(index, score)
            }
            // The *unigram* weight goes into the list, not the score selection
            // was made on. Everything is put on the blended scale together in
            // [rank], after D45 has merged the duplicates — interpolating first
            // would count a shared word's context term twice.
            best.forEach { index ->
                into += Candidate(
                    lexicon.wordAt(index),
                    lexicon.weightAt(index),
                    lexicon.language,
                    index = index,
                )
            }
        }
        return dropped
    }

    /**
     * D46's interpolation, applied to one candidate: `λ·P(w | previous) + (1−λ)·P(w)`.
     *
     * **Before merging, not after**, and it makes no difference which — the
     * blend is linear, so adding two copies of a shared word and then blending
     * gives the same number as blending each and adding. Doing it first is
     * simply cheaper, because each copy still knows its own index.
     *
     * A candidate with no lexicon behind it — a personal word, a built
     * apostrophe form — gets no context term and keeps its `(1 − λ)` share.
     * That is a demotion relative to a word the corpus expects here, which is
     * the honest ordering: the store has nothing to say for it either way.
     */
    private fun blend(candidate: Candidate, context: Map<Language, ContextRow>): Candidate {
        val row = context[candidate.language]
        val bigram = if (row == null || candidate.index < 0) {
            0f
        } else {
            row.vote * row.store.probability(row.context, candidate.index)
        }
        return Candidate(
            candidate.word,
            contextWeight * bigram + (1f - contextWeight) * candidate.weight,
            candidate.language,
            candidate.cost,
        )
    }

    /**
     * Which bigram row answers for a language, and how much of the vote it gets.
     *
     * The vote is [predict]'s mixture: `P(language | previous word)`, taken from
     * the unigram weight of the context word in each corpus, so `die` hands
     * German almost everything and `in` splits it.
     */
    private class ContextRow(val store: BigramStore, val context: Int, val vote: Float)

    private fun contextFor(preceding: Preceding): Map<Language, ContextRow> {
        if (bigrams.isEmpty() || contextWeight <= 0f || preceding == Preceding.Unknown) {
            return emptyMap()
        }
        val votes = HashMap<Language, Float>(lexicons.size)
        var total = 0f
        lexicons.forEach { lexicon ->
            val vote = when (preceding) {
                is Preceding.Word -> {
                    val index = lexicon.indexOf(preceding.text)
                    if (index < 0) 0f else lexicon.weightAt(index)
                }
                else -> 1f
            }
            if (vote > 0f) {
                votes[lexicon.language] = vote
                total += vote
            }
        }
        if (total <= 0f) return emptyMap()

        val rows = HashMap<Language, ContextRow>(lexicons.size)
        lexicons.forEach { lexicon ->
            val store = bigrams[lexicon.language] ?: return@forEach
            val vote = votes[lexicon.language] ?: return@forEach
            val context = when (preceding) {
                is Preceding.Word -> lexicon.indexOf(preceding.text)
                else -> store.sentenceStart
            }
            if (context >= 0) rows[lexicon.language] = ContextRow(store, context, vote / total)
        }
        return rows
    }

    /**
     * The best few of a long list, kept without sorting it.
     *
     * Insertion into a handful of slots, because [KEEP] is small enough that a
     * linear shuffle beats a heap and allocates nothing per candidate. What
     * falls out is returned rather than dropped, so the caller can put it in
     * the divisor.
     */
    private class TopCandidates(private val capacity: Int) {
        private val indices = IntArray(capacity)
        private val scores = FloatArray(capacity)
        private var size = 0

        /**
         * Takes [score] into account, returning whatever weight this displaced
         * — which is [score] itself when the candidate is not good enough to
         * get in, and the evicted last place when it is.
         */
        fun offer(index: Int, score: Float): Float {
            val full = size == capacity
            if (full && score <= scores[capacity - 1]) return score
            val evicted = if (full) scores[capacity - 1] else 0f
            var slot = if (full) capacity - 1 else size++
            while (slot > 0 && scores[slot - 1] < score) {
                indices[slot] = indices[slot - 1]
                scores[slot] = scores[slot - 1]
                slot--
            }
            indices[slot] = index
            scores[slot] = score
            return evicted
        }

        fun forEach(action: (index: Int) -> Unit) {
            for (slot in 0 until size) action(indices[slot])
        }
    }

    /**
     * What tends to come next (D9, D46), which is what fills the strip between
     * words.
     *
     * **Both languages answer, and they are weighted by how much each one
     * believes it owns the context.** The two stores hold conditional
     * probabilities within their own corpus, and those are not comparable
     * across languages as they stand: `P_de(next | die)` and `P_en(next | die)`
     * are both perfectly normalised and describe different worlds. What makes
     * them one distribution is the mixture
     *
     *     P(next | previous) = Σ P(language | previous) · P_language(next | previous)
     *
     * with `P(language | previous)` taken from the unigram weights already
     * shipped — the same numbers D22 uses to let the two lists compete on one
     * scale. So `die`, which is 55 times commoner in German, hands German
     * almost the whole vote and the strip fills with `die Frau`, `die Tür`;
     * `in`, which is near-equal, lets both languages answer. That is D2's
     * per-word language inference in the only form the evidence supports — not
     * a guess about what language the sentence is in, but a weighting by what
     * the last word actually was.
     *
     * At a sentence start there is no previous word to weight by, so the two
     * corpora split it evenly, which is what D1 says they are.
     *
     * Candidates are merged across languages by D45's rule, since a word both
     * lists carry is one word here too.
     */
    override fun predict(preceding: Preceding): List<Suggestion> {
        if (bigrams.isEmpty() || preceding == Preceding.Unknown) return emptyList()

        // How much of the vote each language gets, from the context word's own
        // frequency. Nothing to divide at a sentence start, so it is even.
        val votes = HashMap<Language, Float>(lexicons.size)
        var total = 0f
        lexicons.forEach { lexicon ->
            val vote = when (preceding) {
                is Preceding.Word -> {
                    val index = lexicon.indexOf(preceding.text)
                    if (index < 0) 0f else lexicon.weightAt(index)
                }
                else -> 1f
            }
            if (vote > 0f) {
                votes[lexicon.language] = vote
                total += vote
            }
        }
        if (total <= 0f) return emptyList()

        val candidates = mutableListOf<Candidate>()
        lexicons.forEach { lexicon ->
            val store = bigrams[lexicon.language] ?: return@forEach
            val vote = (votes[lexicon.language] ?: return@forEach) / total
            val context = when (preceding) {
                is Preceding.Word -> lexicon.indexOf(preceding.text)
                else -> store.sentenceStart
            }
            if (context < 0) return@forEach
            store.forEachFollower(context) { word, probability ->
                candidates += Candidate(lexicon.wordAt(word), vote * probability, lexicon.language)
            }
        }
        if (candidates.isEmpty()) return emptyList()

        // Already a probability, so unlike everywhere else in this class there
        // is no mass to divide by — the confidences the strip shows here are
        // the model's own and sum to at most one.
        return merge(candidates)
            .sortedByDescending { it.weight }
            .take(SuggestionSlots.CAPACITY)
            .map {
                val text = if (preceding == Preceding.SentenceStart) capitalised(it.word) else it.word
                Suggestion(text, it.weight, it.language)
            }
    }

    /**
     * A sentence opens with a capital, so a word offered as its first is shown
     * with one (D42).
     *
     * The strip has to do this itself rather than leave it to the shift state,
     * because what it shows and what it inserts must be the same string — and
     * `pickSuggestion` applies the pending shift to whatever it is handed,
     * which for an already-capitalised word is a no-op.
     */
    private fun capitalised(word: String): String = word.replaceFirstChar { it.uppercaseChar() }

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
        named: Boolean,
        into: MutableList<Candidate>,
    ): Correction? {
        // D28's hard gate, and the reason the scan can often be skipped
        // outright: a word in either dictionary is a word, however rare. D44's
        // gate is the same shape: a capital in the middle settles it too.
        val correcting = !named && !knowsExactly(typed)
        val filling = into.size < SuggestionSlots.CAPACITY
        if (!correcting && !filling) return null

        // The typed word standing as it is, which is what a correction has to
        // beat rather than merely lead.
        var mass = priorFor(typed)
        // Keyed by folded spelling, so that a word both lists carry arrives
        // once with its weight added up rather than twice as its own rival
        // (D45). The mass is unaffected — the same scores, grouped.
        val nearby = if (correcting) HashMap<String, Candidate>() else null

        forEachNearby(typed, touches) { lexicon, index, cost ->
            val score = lexicon.weightAt(index) * exp(-confidenceDecay * cost)
            if (nearby != null) {
                mass += score
                val found = Candidate(lexicon.wordAt(index), score, lexicon.language)
                val key = keyOf(found.word)
                val existing = nearby[key]
                nearby[key] = if (existing == null) found else join(existing, found)
            }
            // Cost zero is the word itself, which is not a correction. It gets
            // here because folding makes `uber` and `über` one lookup — and
            // `über`, at ACCENT, is a correction worth offering.
            if (filling && cost > 0f) {
                into += Candidate(lexicon.wordAt(index), score, lexicon.language, index = index)
            }
        }

        val winner = nearby?.values?.maxByOrNull { it.weight } ?: return null
        if (winner.word.equals(typed, ignoreCase = true)) return null
        return Correction(
            text = applyTypedCase(winner.word, typed),
            original = typed,
            confidence = winner.weight / mass,
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

        // Sift before scoring. Each survivor of the cheap filters gets a floor
        // under its cost, and a floor under the cost is a *ceiling* on the
        // score — which is enough to tell most candidates apart from the winner
        // without doing either of the expensive terms.
        val shortlist = mutableListOf<Pending>()
        var bestPossible = 0f

        firsts.forEach { first ->
            val bucket = first.toString()

            personal.completions(bucket).forEach { word ->
                boundGesture(decoder, path, word, lasts)?.let { bound ->
                    val pending = Pending(word, PERSONAL_WEIGHT, null, bound)
                    if (pending.ceiling > bestPossible) bestPossible = pending.ceiling
                    shortlist += pending
                }
            }

            lexicons.forEach { lexicon ->
                for (index in lexicon.completions(bucket)) {
                    val word = lexicon.wordAt(index)
                    val bound = boundGesture(decoder, path, word, lasts) ?: continue
                    val pending = Pending(word, lexicon.weightAt(index), lexicon.language, bound)
                    if (pending.ceiling > bestPossible) bestPossible = pending.ceiling
                    shortlist += pending
                }
            }
        }

        val candidates = mutableListOf<Candidate>()
        // What the skipped candidates would have been worth at their most
        // flattering. Added to the divisor rather than dropped, so the pruning
        // can only ever *understate* how sure the keyboard is — an optimisation
        // that quietly inflated confidence would be changing the answer, and
        // D3 rests on that number meaning something.
        var skipped = 0f
        val floor = bestPossible * PRUNE_RATIO

        shortlist.forEach { pending ->
            if (pending.ceiling < floor) {
                skipped += pending.ceiling
                return@forEach
            }
            val cost = decoder.cost(path, pending.word)
            if (cost > GestureDecoder.MAX_COST) return@forEach
            candidates += Candidate(
                pending.word,
                gestureScore(pending.weight, cost),
                pending.language,
                cost,
            )
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
        return Candidates(rankGesture(candidates, prior + skipped), correction = null)
    }

    /**
     * Orders what a stroke could have been: the best guess first, and then the
     * appeal against it.
     *
     * **The two halves are ordered by different questions, and that is the
     * point.** What gets committed is the best guess overall, so it weighs how
     * well the shape fits against how common the word is, and frequency
     * deserves its say there. But the strip is only ever read when that guess
     * was *wrong* — so ranking the rest by frequency again asks the question
     * that has just failed, and answers it the same way.
     *
     * Observed, on a stroke that spelled `swiping`: the four best fits were
     * `swiping`, `sweeping`, `swooping` and `stopping`, between 0.25 and 0.28.
     * The strip offered `song` and `strong`, at 0.51 and 0.56 — twice the
     * misfit — because they are some three hundred times commoner. The word the
     * finger had actually drawn was nowhere, beaten by two that plainly did not
     * match the picture on the screen.
     *
     * So the runners-up are ordered by **how well they fit**, ties going to the
     * commoner word. If the frequency table has already had its turn and lost,
     * what is left to consult is the finger.
     *
     * One more than the strip holds, because the first of these is committed
     * rather than offered (D39) — asking for three left the last slot empty.
     */
    private fun rankGesture(candidates: List<Candidate>, prior: Float): List<Suggestion> {
        if (candidates.isEmpty()) return emptyList()
        var mass = prior
        candidates.forEach { mass += it.weight }
        if (mass <= 0f) return emptyList()

        val byScore = candidates.sortedByDescending { it.weight }
        val ordered = listOf(byScore.first()) +
            byScore.drop(1).sortedWith(compareBy({ it.cost }, { -it.weight }))

        return ordered.asSequence()
            .map { Suggestion(it.word, it.weight / mass, it.language) }
            // A finger cannot express capitals, so `song` and `Song` are one
            // answer to a swipe and spending two slots on them wastes one.
            // D24's re-case gesture is how the other casing is reached.
            .distinctBy { it.text.lowercase() }
            .take(SuggestionSlots.CAPACITY + 1)
            .toList()
    }

    /**
     * A candidate that has passed the cheap filters and been given a floor
     * under its cost, but has not yet been properly scored.
     *
     * [ceiling] is the most it could possibly be worth: the real cost can only
     * be higher than the bound, so the real score can only be lower than this.
     */
    private inner class Pending(
        val word: String,
        val weight: Float,
        val language: Language?,
        bound: Float,
    ) {
        val ceiling: Float = gestureScore(weight, bound)
    }

    /**
     * The least tracing [word] could have cost, or null if it is not worth
     * asking at all.
     *
     * Ordered by price. The last letter is one character comparison and throws
     * away about ninety-five percent of the bucket; the journey length is one
     * pass over the word and throws away most of the rest; and what survives
     * both gets a *floor* under its cost rather than the cost itself, which is
     * a table lookup per letter.
     */
    private fun boundGesture(
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

        val bound = decoder.bound(path, word)
        return if (bound > GestureDecoder.MAX_COST) null else bound
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
        context: Map<Language, ContextRow> = emptyMap(),
        prior: Float = 0f,
        keepTyped: Boolean = false,
    ): List<Suggestion> {
        if (candidates.isEmpty()) return emptyList()

        // A word both lists carry is one word and takes one slot (D45). Before
        // this the strip could spend two of its three saying `baby` and `Baby`.
        // **Merged before the context is applied**, or a shared word would have
        // its `P(word | previous)` counted once per list.
        val merged = merge(
            if (context.isEmpty()) candidates else candidates.map { blend(it, context) },
        )

        // Confidence is the candidate's share of everything that matches: with
        // no context, a unigram P(word | what was typed so far); with one, that
        // interpolated with P(word | previous word) (D46, D47). Crude either
        // way — D3 wants a calibrated number and this is the most a lookup can
        // say — but the ordering is no longer blind to the sentence.
        var mass = prior
        merged.forEach { mass += it.weight }
        if (mass <= 0f) return emptyList()

        return merged
            .sortedByDescending { it.weight }
            .asSequence()
            .map { Suggestion(applyTypedCase(it.word, typed), it.weight / mass, it.language) }
            // Offering back exactly what is already there wastes a slot —
            // unless it is about to be replaced, in which case it is the way to
            // say no.
            .filter { keepTyped || it.text != typed }
            .distinctBy { it.text }
            .take(SuggestionSlots.CAPACITY)
            .toList()
    }

    /**
     * `gehtvs` means `geht's`, for any stem at all (D27).
     *
     * Kept alongside [addApostropheForms], which looks contractions up, because
     * this one is **productive** and a lookup cannot be. Every English noun
     * takes a possessive `'s` and every German verb takes the clipped `es`, so
     * `have's` and `geht's` are real and no wordlist will ever list them all.
     * The seventy-four contractions that did ship are the fixed ones — `don't`,
     * `I'll` — and this is the open class beside them.
     *
     * So the stem is looked up and the apostrophe added to the spelling the
     * dictionary has. A word ending in `vs` is otherwise close to nonexistent,
     * which is what makes that safe to do blindly.
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
     * `letvs` means `let's`, `ivll` means `I'll`, `donvt` means `don't` (D27,
     * D41).
     *
     * The apostrophe is the long-press alternate on `v` (D17), so the way to
     * miss it is to tap the key instead of holding it. The first version of
     * this knew one pattern — a word ending `vs` — and had to *build* the
     * answer, because the corpus the frequencies came from split `don't` into
     * `don` and `t` before counting and the wordlists carried no contractions
     * at all. They do now, seventy-four of them, so the rule collapses into
     * something both simpler and far wider: put an apostrophe where the `v` is
     * and see whether that is a word.
     *
     * Every `v` is tried, not just the last, which is what reaches `ivll` and
     * `ivm` — the ones worth having, since I-forms are four percent of English
     * typing.
     */
    private fun addApostropheForms(typed: String, into: MutableList<Candidate>): Correction? {
        if (typed.length < 2) return null

        var best: Candidate? = null
        var mass = priorFor(typed)

        for (index in typed.indices) {
            if (typed[index].lowercaseChar() != APOSTROPHE_KEY) continue
            val swapped = typed.substring(0, index) + '\'' + typed.substring(index + 1)

            personal.completions(Folding.fold(swapped))
                .firstOrNull { Folding.fold(it) == Folding.fold(swapped) }
                ?.let { into += Candidate(it, PERSONAL_WEIGHT, language = null) }

            lexicons.forEach { lexicon ->
                val found = lexicon.indexOf(swapped)
                if (found < 0) return@forEach
                val word = lexicon.wordAt(found)
                // A known slip with a known shape, so it is discounted far less
                // than an arbitrary substitution would be.
                val score = lexicon.weightAt(found) * exp(-confidenceDecay * APOSTROPHE_SLIP)
                mass += score
                into += Candidate(word, score, lexicon.language, index = found)
                if (score > (best?.weight ?: 0f)) {
                    best = Candidate(applyTypedCase(word, typed), score, lexicon.language)
                }
            }
        }

        val winner = best ?: return null
        return Correction(winner.word, typed, winner.weight / mass, winner.language)
    }

    /**
     * `i` is `I`, and which `i` is a question only a bilingual keyboard has to
     * ask (D41).
     *
     * A single letter never reached the strip at all — [MIN_PREFIX] wants two
     * before it will guess — and it was never corrected either, because
     * [Lexicon.knowsExactly] ignores case on purpose and so judged `i`
     * perfectly well spelled. Between them that left the second commonest word
     * in English with no help of any kind.
     *
     * Not a rule about capitals but about *which casing the dictionaries
     * prefer*, which is the honest question here: English has `I` and no
     * lowercase form, German has a lowercase `i` and no capital, and their
     * corpus shares settle it at better than two hundred to one. The loser goes
     * in the strip, because a keyboard that decides between two real words
     * should show its working.
     *
     * Deliberately only for single letters. The same reasoning would capitalise
     * every German noun on sight — `haus` to `Haus` — which may well be right
     * and is emphatically a separate decision.
     *
     * **The one place a word in both lists is deliberately left doubled** (D45).
     * Everywhere else `i` and `I` are one word arriving twice; here the
     * difference between them is the entire question, and adding them up would
     * leave nothing to correct.
     */
    private fun addCasedForms(typed: String, into: MutableList<Candidate>): Correction? {
        if (typed.length != 1 || !typed[0].isLetter()) return null

        var best: Candidate? = null
        var mass = priorFor(typed)

        lexicons.forEach { lexicon ->
            val index = lexicon.indexOf(typed)
            if (index < 0) return@forEach
            val word = lexicon.wordAt(index)
            val score = lexicon.weightAt(index)
            mass += score
            into += Candidate(word, score, lexicon.language, index = index)
            if (word != typed && score > (best?.weight ?: 0f)) {
                best = Candidate(word, score, lexicon.language)
            }
        }

        val winner = best ?: return null
        return Correction(winner.word, typed, winner.weight / mass, winner.language)
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
     *
     * **Except when the word is being shouted**, where the wordlist's casing
     * has no say at all. `I DONT CARE` came back as `I Don't CARE`, because a
     * rule about the first letter is the wrong rule for a word that is all
     * capitals — and correcting inside a shout is exactly when a stray
     * lowercase run is most obvious.
     */
    private fun applyTypedCase(candidate: String, typed: String): String {
        if (TextEdits.isShouted(typed)) return candidate.uppercase()
        val first = typed.firstOrNull() ?: return candidate
        if (!first.isUpperCase()) return candidate
        return candidate.replaceFirstChar { it.uppercaseChar() }
    }

    private companion object {
        /**
         * One letter completes (D47).
         *
         * It used to take two, because one letter is not evidence of anything.
         * With the word before it to go on, it frequently is: after `vielen`, a
         * `d` is `dank`. And it is the stretch of the word where nothing else
         * helps — [MIN_CORRECTION_LENGTH] means correction has nothing to say
         * until the third character.
         */
        const val MIN_PREFIX = 1

        /**
         * How much of a candidate's score comes from the word before it rather
         * than from how common it is (D46, D47).
         *
         * Zero is the ranking as it was before any of this; one throws the
         * frequency table away and trusts a bigram count that has never seen
         * most of the pairs it will be asked about — and measurably loses for
         * it, which is the clearest evidence that the interpolation is doing
         * work rather than decorating.
         *
         * Swept on 8,000 held-out subtitle pairs; the full table is in D47. The
         * peak is at 0.95 and this is deliberately below it: the curve is flat
         * to within a point and a half from 0.65 up, the held-out text was
         * inside the counts and so flatters the bigram slightly, and a context
         * whose row barely cleared the threshold is estimated from very little.
         * A fifth of the weight left on the frequency table is what that buys.
         */
        const val CONTEXT_WEIGHT = 0.8f

        /**
         * How many completions per language survive the scan.
         *
         * Four times what the strip can show, so that D45's merging and the
         * typed-word filter have something to work with, and small enough that
         * `s` — 3,662 German words and 4,187 English ones — costs an integer
         * comparison each rather than an allocation.
         */
        const val KEEP = 12

        /**
         * What a personal word is assumed to be worth, as a share of a corpus.
         * Roughly the weight of the two-hundredth most common word — enough to
         * beat ordinary vocabulary, not enough to displace `the` or `ich`.
         */
        const val PERSONAL_WEIGHT = 1e-3f

        /** A one-letter stem in front of an apostrophe is a typo, not a word. */
        const val STEM_MIN = 2

        /** The key the apostrophe hides behind, as a long-press (D17). */
        const val APOSTROPHE_KEY = 'v'

        /**
         * What tapping `v` where an apostrophe was meant is judged to cost.
         *
         * Small, because this is not a thumb landing somewhere random — it is
         * one specific slip with one specific cause, and a word that comes back
         * from it is almost certainly the word that was wanted.
         */
        const val APOSTROPHE_SLIP = 0.2f

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
         *
         * The figure for a string that *looks* like a word of either language.
         * One that does not is discounted from here by [WordShape] (D43) — see
         * [priorFor], which is what any correction actually has to beat.
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

        /**
         * How far below the best possible candidate a word may be and still be
         * worth scoring properly.
         *
         * Ten thousand to one. The pruned ones are not discarded silently —
         * their most flattering possible score goes into the confidence
         * divisor — so the effect of being wrong here is that the keyboard
         * sounds very slightly less sure than it might, never more.
         */
        const val PRUNE_RATIO = 1e-4f

        /** A ceiling on the scan, however ambiguous the first touch was. */
        const val MAX_SEARCH_PREFIXES = 4

    }
}
