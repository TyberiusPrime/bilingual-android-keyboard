package de.coonabibba.bikeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A word both wordlists carry is one word (D45).
 *
 * Two thousand nine hundred spellings are in both, carrying about a fifth of
 * the typing mass of either language, so this is not a corner of the corpus —
 * it is `in`, `was`, `hand`, `name`, `problem`, `moment`.
 */
class SharedWordsTest {
    private val source: DictionarySuggestions by lazy {
        val store = PersonalStore(File.createTempFile("shared", ".txt").also { it.delete() })
        store.load()
        DictionarySuggestions(SwipeFixtures.lexicons, store)
    }
    private val keys = SwipeFixtures.geometry
    private val threshold = KeyboardPrefs.DEFAULT_AUTO_CORRECT_CONFIDENCE / 100f

    private fun typed(word: String) = word.map { ch ->
        val e = keys.centreOf(ch) ?: return@map TypedTouch.untouched(ch)
        TypedTouch(ch, keys.alternatives(e.centreX, e.centreY, ch))
    }

    private fun correctionFor(word: String) = source.candidatesFor(word, typed(word)).correction

    private fun stripFor(word: String) = source.candidatesFor(word, typed(word)).suggestions

    /**
     * The measurement that started this. Every one of these was under the
     * threshold — `hnad` reached `hand` at 0.37 — purely because the target
     * lived in two lists and so stood in its own divisor.
     */
    @Test
    fun `a shared word can be corrected to`() {
        listOf(
            "nmae" to "name", "momnet" to "moment", "probelm" to "problem",
            "bayb" to "baby", "wasd" to "was", "kidn" to "kind",
        ).forEach { (typo, wanted) ->
            val fix = correctionFor(typo)
            assertEquals("$typo -> ${fix?.text}", wanted, fix?.text)
            assertTrue("$typo at ${fix?.confidence}", fix!!.confidence >= threshold)
        }
    }

    /**
     * The casing half of the same bug. The surviving copy used to be whichever
     * corpus weighed the word more, so anyone writing English got `Moment` and
     * `Problem` — German spellings of English words.
     */
    @Test
    fun `the least capitalised spelling wins`() {
        assertEquals("moment", correctionFor("momnet")?.text)
        assertEquals("problem", correctionFor("probelm")?.text)
        // And the typist still supplies the capital, exactly as D22 has it.
        assertEquals("Moment", correctionFor("Momnet")?.text)
        assertEquals("Problem", correctionFor("Probelm")?.text)
    }

    /**
     * One word, one slot. `bab` used to spend two of the strip's three on
     * `baby` and `Baby`.
     */
    @Test
    fun `a shared word takes one slot`() {
        listOf("bab", "prob", "mom", "nam").forEach { prefix ->
            val folded = stripFor(prefix).map { Folding.fold(it.text) }
            assertEquals("$prefix offered ${stripFor(prefix).map { it.text }}", folded.distinct(), folded)
        }
    }

    /**
     * A word both languages own is not evidence of either, so the D4 tint goes
     * neutral rather than picking the corpus that happened to weigh it more.
     */
    @Test
    fun `a shared word claims no language`() {
        assertEquals(null, correctionFor("momnet")?.language)
        assertEquals(null, stripFor("bab").first { it.text == "baby" }.language)
        // A word only one list carries still says so.
        assertEquals(Language.ENGLISH, correctionFor("wrold")?.language)
        assertEquals(Language.GERMAN, correctionFor("arbiet")?.language)
    }

    /**
     * D41's `i` to `I` runs on the difference between the two lists' casings
     * and is the one place the doubling is deliberate.
     */
    @Test
    fun `single letters are still two words`() {
        val fix = correctionFor("i")
        assertEquals("I", fix?.text)
        assertTrue("i at ${fix?.confidence}", fix!!.confidence >= threshold)
        assertTrue("the loser should still be offered", stripFor("i").any { it.text == "i" })
    }
}
