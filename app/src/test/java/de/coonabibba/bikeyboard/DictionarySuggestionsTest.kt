package de.coonabibba.bikeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    )

    private fun source(personal: PersonalStore = PersonalStore(folder.newFile())) =
        DictionarySuggestions(listOf(german, english), personal)

    private fun texts(word: String) = source().suggest(word).map { it.text }

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
        val suggestions = source().suggest("ha")
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
        val suggestions = DictionarySuggestions(listOf(german, english), personal).suggest("hau")
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
    fun `short words are left alone`() {
        // Everything three letters long is one edit from half the dictionary.
        assertEquals(emptyList<String>(), texts("hxu"))
    }

    @Test
    fun `a first letter that is wrong is out of reach, and says so`() {
        // Only words sharing the first letter are searched, which is the
        // documented limit of the scan rather than an accident.
        assertFalse("haben" in texts("jaben"))
    }

    /**
     * D4's indicator reads this. It is a property of the candidate rather than
     * of the keyboard, which is the whole of D2 in one field.
     */
    @Test
    fun `candidates carry the language they came from`() {
        val suggestions = source().suggest("ha").associate { it.text to it.language }
        assertEquals(Language.GERMAN, suggestions["haben"])
        assertEquals(Language.ENGLISH, suggestions["have"])
    }

    @Test
    fun `a personal word belongs to no language`() {
        val personal = PersonalStore(folder.newFile()).apply { add("Hauswurz") }
        val suggestions = DictionarySuggestions(listOf(german, english), personal).suggest("hau")
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
}
