package de.coonabibba.bikeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LexiconTest {

    /** Words must arrive in folded order — the same order the assets are written in. */
    private fun lexicon(vararg entries: Pair<String, Long>): Lexicon {
        val sorted = entries.sortedBy { Folding.fold(it.first) }
        return Lexicon(Language.GERMAN, sorted.map { it.first }, sorted.map { it.second }.toLongArray())
    }

    private fun Lexicon.completionsOf(prefix: String): List<String> =
        completions(Folding.fold(prefix)).map { wordAt(it) }

    @Test
    fun `completions are the words carrying the prefix`() {
        val lexicon = lexicon("Haus" to 10, "Häuser" to 5, "haben" to 20, "Zeit" to 7)
        assertEquals(listOf("haben", "Haus", "Häuser"), lexicon.completionsOf("ha"))
        assertEquals(listOf("Haus", "Häuser"), lexicon.completionsOf("hau"))
        assertEquals(emptyList<String>(), lexicon.completionsOf("xy"))
    }

    @Test
    fun `completions ignore case and accents in the prefix`() {
        val lexicon = lexicon("über" to 10, "üblich" to 5)
        // Folded order, so `uber` before `ublich`.
        assertEquals(listOf("über", "üblich"), lexicon.completionsOf("ub"))
        assertEquals(listOf("über", "üblich"), lexicon.completionsOf("Üb"))
    }

    @Test
    fun `an empty prefix matches nothing rather than everything`() {
        val lexicon = lexicon("Haus" to 10)
        assertEquals(emptyList<String>(), lexicon.completionsOf(""))
    }

    /**
     * Weights are shares of the lexicon's own corpus, which is what makes a
     * German candidate comparable with an English one (D1, D2).
     */
    @Test
    fun `weights are shares of the corpus`() {
        val lexicon = lexicon("a" to 30, "b" to 10)
        val weights = (0 until lexicon.size).associate { lexicon.wordAt(it) to lexicon.weightAt(it) }
        assertEquals(0.75f, weights.getValue("a"), 0.0001f)
        assertEquals(0.25f, weights.getValue("b"), 0.0001f)
    }

    @Test
    fun `an empty lexicon answers everything with nothing`() {
        val lexicon = Lexicon(Language.GERMAN, emptyList(), LongArray(0))
        assertEquals(0, lexicon.size)
        assertFalse(lexicon.knows("haus"))
        assertEquals(emptyList<String>(), lexicon.completionsOf("ha"))
    }

    @Test
    fun `knows matches whole words, folded`() {
        val lexicon = lexicon("Haus" to 10, "Straße" to 5)
        assertTrue(lexicon.knows("Haus"))
        assertTrue("case is not a new word", lexicon.knows("haus"))
        assertTrue("nor is an accent someone skipped", lexicon.knows("strasse"))
        assertFalse("a prefix is not the word", lexicon.knows("hau"))
        assertFalse(lexicon.knows("Hausboot"))
        assertFalse(lexicon.knows(""))
    }
}
