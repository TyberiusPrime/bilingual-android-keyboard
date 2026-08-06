package de.coonabibba.bikeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DictionarySuggestionsTest {

    @get:Rule
    val folder = TemporaryFolder()

    /**
     * A toy lexicon padded out to the size of a real corpus, because weights
     * are shares of one: without the filler a handful of words would each carry
     * a tenth of the language and nothing would rank the way it does on the
     * device. The counts are the real ones from the shipped lists.
     */
    private fun lexicon(language: Language, vararg entries: Pair<String, Long>): Lexicon {
        val filler = "zzfiller" to CORPUS_SIZE - entries.sumOf { it.second }
        val sorted = (entries.toList() + filler).sortedBy { Folding.fold(it.first) }
        return Lexicon(language, sorted.map { it.first }, sorted.map { it.second }.toLongArray())
    }

    private val german = lexicon(
        Language.GERMAN,
        "haben" to 742_327, "Haus" to 70_976, "Häuser" to 3_487, "hallo" to 61_000,
        "über" to 196_855,
    )
    private val english = lexicon(
        Language.ENGLISH,
        "have" to 700_000, "hard" to 120_000, "house" to 90_000, "hallway" to 3_000,
        // Reconstructed by the build script from the halves OpenSubtitles left
        // behind (D34); the count is the real one from the shipped list.
        "don't" to 3_244_316,
    )

    private fun source(personal: PersonalStore = PersonalStore(folder.newFile())) =
        DictionarySuggestions(listOf(german, english), personal)

    /** Typed with no near misses recorded, which is the conservative case. */
    private fun texts(word: String) = source().suggest(word, clean(word)).map { it.text }

    private companion object {
        /** About the size of either shipped corpus. */
        const val CORPUS_SIZE = 145_000_000L
    }

    /**
     * The whole point of the project: no current language, both dictionaries
     * live, and candidates from either can win a slot (D1, D2).
     */
    @Test
    fun `both languages compete for the same slots`() {
        val suggestions = texts("ha")
        assertTrue("German is represented: $suggestions", suggestions.any { it in setOf("haben", "Haus", "hallo") })
        assertTrue("English is too: $suggestions", suggestions.any { it in setOf("have", "hard", "hallway") })
    }

    @Test
    fun `the commonest candidates come first`() {
        assertEquals(listOf("haben", "have", "hard"), texts("ha"))
    }

    @Test
    fun `no more candidates than the strip has slots`() {
        assertTrue(texts("ha").size <= SuggestionSlots.CAPACITY)
    }

    /** D5: the umlaut costs a long-press, so typing without it still has to find the word. */
    @Test
    fun `a skipped umlaut still finds the word`() {
        assertTrue("über" in texts("ub"))
        assertTrue("Häuser" in texts("hau"))
    }

    @Test
    fun `the typist decides the first letter's case`() {
        assertTrue("haben" in texts("hab"))
        assertTrue("Haben" in texts("Hab"))
        assertTrue("a capitalised word stays capitalised", "Haus" in texts("hau"))
    }

    @Test
    fun `offering back exactly what was typed would waste a slot`() {
        assertFalse("hallo" in texts("hallo"))
    }

    @Test
    fun `one letter is not enough to suggest from`() {
        assertEquals(emptyList<String>(), texts("h"))
        assertEquals(emptyList<String>(), texts(""))
    }

    @Test
    fun `an unknown prefix suggests nothing`() {
        assertEquals(emptyList<String>(), texts("xyz"))
    }

    /** Confidence is a share of the matching mass, so it behaves like a probability. */
    @Test
    fun `confidences are shares between zero and one`() {
        val suggestions = source().suggest("ha", clean("ha"))
        assertTrue(suggestions.isNotEmpty())
        suggestions.forEach { assertTrue("${it.text}: ${it.confidence}", it.confidence in 0f..1f) }
        assertTrue(
            "the best candidate is the most confident",
            suggestions == suggestions.sortedByDescending { it.confidence },
        )
    }

    @Test
    fun `a personal word outranks ordinary vocabulary`() {
        val personal = PersonalStore(folder.newFile()).apply { add("Hauswurz") }
        val suggestions = DictionarySuggestions(listOf(german, english), personal).suggest("hau", clean("hau"))
        assertEquals("Hauswurz", suggestions.first().text)
    }

    // -- the apostrophe on `v` (D27) -----------------------------------------

    /**
     * The apostrophe is a long-press on `v`, so missing it types `v` — and
     * what follows is nearly always an `s`.
     */
    @Test
    fun `a word ending in vs offers the apostrophe`() {
        assertTrue("have's" in texts("havevs"))
        assertTrue("haben's" in texts("habenvs"))
    }

    @Test
    fun `the apostrophe candidate follows the case that was typed`() {
        assertTrue("Have's" in texts("Havevs"))
    }

    /** Built rather than looked up, since the wordlists carry no contractions. */
    @Test
    fun `the stem has to be a word`() {
        assertFalse(texts("xyzvs").any { it.endsWith("'s") })
    }

    @Test
    fun `a bare vs is not a contraction of anything`() {
        assertFalse(texts("vs").any { it.endsWith("'s") })
    }

    // -- corrections (D23) ----------------------------------------------------

    /** A typo is not a prefix of anything, so completion alone says nothing. */
    @Test
    fun `a word a single edit away is offered`() {
        assertTrue("hallo" in texts("hallp"))
        assertTrue("house" in texts("housr"))
    }

    @Test
    fun `a transposition is one edit, not two`() {
        assertTrue("house" in texts("huose"))
    }

    @Test
    fun `two edits are offered for a longer word`() {
        assertTrue("Häuser" in texts("hauzer"))
    }

    @Test
    fun `a completion still outranks a correction`() {
        // `hous` completes to `house` and is one edit from `haus`; the word
        // that was begun correctly comes first.
        assertEquals("house", texts("hous").first())
    }

    @Test
    fun `a word near nothing suggests nothing`() {
        // Two full-price substitutions is past MAX_SLIP_COST, so a short string
        // that begins like a word still reaches none of them.
        assertEquals(emptyList<String>(), texts("hxu"))
    }

    @Test
    fun `a first letter that is wrong is out of reach without evidence`() {
        // The scan follows the first press's near neighbours (D35), and a clean
        // touch reports none — so `j` for `h` stays a decision, not a slip.
        assertFalse("haben" in texts("jaben"))
    }

    // -- one search behind both answers (D37) ---------------------------------

    /**
     * The bug this closes: the strip could not offer a word the space bar was
     * about to insert. Whatever `correction` says, the strip has to be showing.
     */
    @Test
    fun `whatever space would substitute is on the strip`() {
        listOf("dont", "hallp", "housr", "ahben").forEach { typed ->
            val query = source().candidatesFor(typed, clean(typed))
            val correction = query.correction
            assertTrue("$typed found no correction", correction != null)
            assertTrue(
                "$typed: space gives ${correction!!.text}, strip shows ${query.suggestions.map { it.text }}",
                correction.text in query.suggestions.map { it.text },
            )
        }
    }

    /** The strip reaches exactly as far as the correction does — including the apostrophe. */
    @Test
    fun `the strip offers a contraction for the apostrophe-less spelling`() {
        assertTrue("don't" in texts("dont"))
    }

    /** And exactly as far for a transposed first pair. */
    @Test
    fun `the strip offers a transposed first pair`() {
        assertTrue("haben" in texts("ahben"))
    }

    /**
     * The two views agree by construction, being one search — but the views are
     * what the rest of the keyboard calls, so it is worth saying out loud.
     */
    @Test
    fun `the separate views agree with the combined query`() {
        val touches = clean("hallp")
        val query = source().candidatesFor("hallp", touches)
        assertEquals(query.suggestions, source().suggest("hallp", touches))
        assertEquals(query.correction, source().correct("hallp", touches))
    }

    /**
     * D4's indicator reads this. It is a property of the candidate rather than
     * of the keyboard, which is the whole of D2 in one field.
     */
    @Test
    fun `candidates carry the language they came from`() {
        val suggestions = source().suggest("ha", clean("ha")).associate { it.text to it.language }
        assertEquals(Language.GERMAN, suggestions["haben"])
        assertEquals(Language.ENGLISH, suggestions["have"])
    }

    @Test
    fun `a personal word belongs to no language`() {
        val personal = PersonalStore(folder.newFile()).apply { add("Hauswurz") }
        val suggestions = DictionarySuggestions(listOf(german, english), personal).suggest("hau", clean("hau"))
        assertEquals(null, suggestions.first { it.text == "Hauswurz" }.language)
    }

    @Test
    fun `a word is known when any source has it`() {
        val personal = PersonalStore(folder.newFile()).apply { add("Coonabibba") }
        val source = DictionarySuggestions(listOf(german, english), personal)
        assertTrue(source.knows("Haus"))
        assertTrue(source.knows("house"))
        assertTrue("the user's own words count", source.knows("Coonabibba"))
        assertTrue("case and accents are not new words", source.knows("haus"))
        assertFalse(source.knows("Sonnenblumenweg"))
    }

    // -- auto-correction (D28) ------------------------------------------------

    /** Typed cleanly, with no key nearby: the touches say nothing helpful. */
    private fun clean(word: String) = word.map { TypedTouch(it, emptyMap()) }

    /** Typed with the thumb sitting on the border between two keys. */
    private fun grazing(word: String, index: Int, neighbour: Char) =
        word.mapIndexed { i, char ->
            if (i == index) TypedTouch(char, mapOf(neighbour to 0.1f)) else TypedTouch(char, emptyMap())
        }

    @Test
    fun `an adjacent-key slip on a common word is corrected with confidence`() {
        // `haben` typed with the thumb between `b` and `n`.
        val correction = source().correct("hanen", grazing("hanen", 2, 'b'))
        assertEquals("haben", correction?.text)
        assertTrue("confidence was ${correction?.confidence}", correction!!.confidence > 0.9f)
    }

    /**
     * The same edit, but the thumb was nowhere near: this is a word the typist
     * may well have meant, and the keyboard has no business replacing it.
     */
    @Test
    fun `the same edit without the touch evidence is not confident`() {
        val correction = source().correct("hanen", clean("hanen"))
        assertTrue(
            "confidence was ${correction?.confidence}",
            correction == null || correction.confidence < 0.9f,
        )
    }

    /**
     * The property behind "why does it only *sometimes* correct that?" (D28).
     *
     * Confidence falls smoothly as the thumb moves away from the key the word
     * needed, so the same typo crosses the threshold or does not depending on
     * where in the key it landed. Two keystrokes that look identical in the text
     * are not identical here, and that is the design rather than a bug: without
     * the touches there is no way to tell a slip from a decision.
     *
     * What the test pins is that the curve is *monotonic* and spans the
     * threshold. If it ever stopped doing either, the setting would be
     * meaningless — a slider that changes nothing until it changes everything.
     */
    @Test
    fun `confidence falls as the thumb moves away from the intended key`() {
        fun confidenceAt(cost: Float): Float {
            val touches = "hanen".mapIndexed { index, char ->
                if (index == 2) TypedTouch(char, mapOf('b' to cost)) else TypedTouch(char, emptyMap())
            }
            return source().correct("hanen", touches)?.confidence ?: 0f
        }

        // The last point is a full key width away, which is the most the touch
        // model will say about a neighbour at all (`TypedTouch.FAR`).
        val curve = listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f, 1f).map(::confidenceAt)
        curve.zipWithNext { nearer, further ->
            assertTrue("confidence rose with distance: $curve", nearer > further)
        }
        assertTrue("a graze should be confident: $curve", curve.first() > 0.9f)
        assertTrue("a whole key away should not be: $curve", curve.last() < 0.9f)
    }

    /**
     * D34: a missing apostrophe is a skipped long-press, not a misspelling.
     *
     * `dont` is not a word in either language, the apostrophe costs a hold on
     * `v`, and D6 said from the start that correction was expected to place it
     * unprompted. Confident even with no touch evidence at all, because there
     * is none to have — nothing was typed where the apostrophe goes.
     */
    @Test
    fun `a missing apostrophe is corrected without touch evidence`() {
        val correction = source().correct("dont", clean("dont"))
        assertEquals("don't", correction?.text)
        assertTrue("confidence was ${correction?.confidence}", correction!!.confidence > 0.9f)
    }

    /** The cheap apostrophe must not become a cheap anything-else. */
    @Test
    fun `a missing letter is still a full-price edit`() {
        // `hause` -> `Haus` would be free if insertions were cheap in general.
        val correction = source().correct("hous", clean("hous"))
        assertTrue(
            "confidence was ${correction?.confidence}",
            correction == null || correction.confidence < 0.9f,
        )
    }

    /**
     * The falloff is a setting (D34), and a setting that does nothing is worse
     * than no setting: it has to move the answer in the direction it claims.
     */
    @Test
    fun `a gentler falloff forgives a press the default rejects`() {
        val touches = "hanen".mapIndexed { index, char ->
            if (index == 2) TypedTouch(char, mapOf('b' to 0.9f)) else TypedTouch(char, emptyMap())
        }
        fun confidence(source: DictionarySuggestions): Float =
            source.correct("hanen", touches)?.confidence ?: 0f

        fun sourceWith(decay: Float) =
            DictionarySuggestions(listOf(german, english), PersonalStore(folder.newFile()), decay)

        val gentle = confidence(sourceWith(2f))
        // Constructed without the argument, so this is the shipped default.
        val default = confidence(DictionarySuggestions(listOf(german, english), PersonalStore(folder.newFile())))
        val fussy = confidence(sourceWith(20f))
        assertTrue("$gentle should exceed $default", gentle > default)
        assertTrue("$fussy should fall below $default", fussy < default)
    }

    // -- a first letter that came out wrong (D35) -----------------------------

    /**
     * `hte` for `the`: the intended first letter is the second one typed, so
     * its bucket gets searched too.
     */
    @Test
    fun `a transposed first pair is within reach`() {
        val correction = source().correct("ahben", clean("ahben"))
        assertEquals("haben", correction?.text)
    }

    /**
     * `xontinue` for `continue`: the thumb was between the two keys, and the
     * touch says so, so the neighbour's bucket gets searched.
     */
    @Test
    fun `a neighbouring first letter is within reach when the touch says so`() {
        val touches = "jaben".mapIndexed { index, char ->
            if (index == 0) TypedTouch(char, mapOf('h' to 0.2f)) else TypedTouch(char, emptyMap())
        }
        val correction = source().correct("jaben", touches)
        assertEquals("haben", correction?.text)
        assertTrue("confidence was ${correction?.confidence}", correction!!.confidence > 0.9f)
    }

    /**
     * And not otherwise. A first letter the thumb was nowhere near is a letter
     * the typist chose — scanning its bucket would cost real time to produce a
     * candidate the threshold refuses anyway.
     */
    @Test
    fun `a distant first letter is still out of reach`() {
        assertNull(source().correct("jaben", clean("jaben")))
    }

    /** D2: a word valid in either language is valid, however rare. */
    @Test
    fun `a word spelled exactly right is never corrected`() {
        assertNull(source().correct("hallo", clean("hallo")))
        assertNull(source().correct("house", grazing("house", 0, 'g')))
    }

    /** D5, the cheapest win there is: the umlaut is a long-press worth skipping. */
    @Test
    fun `a missing umlaut is corrected`() {
        val correction = source().correct("uber", clean("uber"))
        assertEquals("über", correction?.text)
        assertTrue("confidence was ${correction?.confidence}", correction!!.confidence > 0.9f)
    }

    @Test
    fun `a word the keyboard has never heard of is left alone`() {
        val correction = source().correct("Coonabibba", clean("Coonabibba"))
        assertTrue(
            "confidence was ${correction?.confidence}",
            correction == null || correction.confidence < 0.9f,
        )
    }

    @Test
    fun `a word the user added is never corrected`() {
        val personal = PersonalStore(folder.newFile()).apply { add("Hauswurz") }
        val source = DictionarySuggestions(listOf(german, english), personal)
        assertNull(source.correct("Hauswurz", clean("Hauswurz")))
    }

    /** Without touches there is no telling a slip from a decision (D28). */
    @Test
    fun `nothing is corrected without touches for every character`() {
        assertNull(source().correct("hanen", emptyList()))
        assertNull(source().correct("hanen", clean("han")))
    }

    @Test
    fun `very short words are left alone`() {
        assertNull(source().correct("ha", grazing("ha", 1, 'b')))
    }

    @Test
    fun `a correction keeps the case that was typed`() {
        val correction = source().correct("Hanen", grazing("Hanen", 2, 'b'))
        assertEquals("Haben", correction?.text)
    }

    @Test
    fun `a correction says what it replaced, so it can be put back`() {
        val correction = source().correct("hanen", grazing("hanen", 2, 'b'))
        assertEquals("hanen", correction?.original)
    }
}
