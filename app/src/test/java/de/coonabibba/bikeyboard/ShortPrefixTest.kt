package de.coonabibba.bikeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/**
 * The first two letters, where nothing else helps (D47).
 *
 * Correction needs three characters and completion used to need two, so the
 * opening of every word was the stretch the keyboard had least to say about —
 * and it is the stretch where the word before it says the most. On the real
 * wordlists and the real bigram stores, because the claim is about what two
 * subtitle corpora know.
 */
class ShortPrefixTest {

    private fun asset(name: String) = listOf(
        File("src/main/assets/wordlists/$name"),
        File("app/src/main/assets/wordlists/$name"),
    ).first { it.exists() }

    private val lexicons = SwipeFixtures.lexicons
    private val stores = lexicons.associate { lexicon ->
        val name = if (lexicon.language == Language.GERMAN) "de" else "en"
        lexicon.language to BigramStore.read(
            ByteBuffer.wrap(asset("$name.bigrams").readBytes()),
            lexicon.size,
        )
    }

    private fun source(weight: Float = 0.8f): DictionarySuggestions {
        val personal = PersonalStore(File.createTempFile("shortprefix", ".txt").also { it.delete() })
        personal.load()
        return DictionarySuggestions(
            lexicons = lexicons,
            personal = personal,
            bigrams = stores,
            contextWeight = weight,
        )
    }

    private val keys = SwipeFixtures.geometry

    private fun typed(word: String) = word.map { ch ->
        val e = keys.centreOf(ch) ?: return@map TypedTouch.untouched(ch)
        TypedTouch(ch, keys.alternatives(e.centreX, e.centreY, ch))
    }

    private fun shown(prefix: String, after: String? = null, weight: Float = 0.8f) =
        source(weight).candidatesFor(
            prefix,
            typed(prefix),
            after?.let { Preceding.Word(it) } ?: Preceding.Unknown,
        ).suggestions.map { it.text }

    /** One letter used to produce nothing at all. */
    @Test
    fun `a single letter suggests`() {
        listOf("d", "s", "m", "t", "w").forEach {
            assertTrue("$it offered nothing", shown(it).isNotEmpty())
        }
    }

    /**
     * The point of the exercise: after `vielen`, a `d` is `dank` — which is the
     * 1,300th commonest German word and would never reach the strip on
     * frequency alone.
     */
    @Test
    fun `the word before decides what one letter means`() {
        assertEquals("dank", shown("d", after = "vielen").first().lowercase())
        assertEquals("morgen", shown("m", after = "guten").first().lowercase())
        assertEquals("you", shown("y", after = "thank").first())
        assertEquals("morning", shown("m", after = "good").first())

        // And without the context, none of those is the first thing offered.
        assertTrue("dank" !in shown("d").map { it.lowercase() }.take(1))
        assertTrue("morning" !in shown("m").take(1))
    }

    /** Two letters, the other half of what the correction path cannot reach. */
    @Test
    fun `two letters take the context too`() {
        assertEquals("dank", shown("da", after = "vielen").first().lowercase())
        assertEquals("you", shown("yo", after = "thank").first())
    }

    /**
     * **The interpolation floor.** A word the store has never seen after this
     * context keeps its share of the frequency table, so context can reorder
     * the strip but never empty it.
     */
    @Test
    fun `a word with no context evidence is still offered`() {
        // `xy` follows nothing in particular; the completions must still come.
        assertTrue(shown("sch", after = "vielen").isNotEmpty())
        assertTrue(shown("th", after = "vielen").isNotEmpty())
        // A context in neither corpus falls back to frequency alone.
        assertEquals(shown("d"), shown("d", after = "Tyberius"))
    }

    /** Zero weight is the ranking exactly as it was before D47. */
    @Test
    fun `no context weight means no context`() {
        listOf("d" to "vielen", "m" to "guten", "th" to "of").forEach { (prefix, before) ->
            assertEquals(
                "$prefix after $before",
                shown(prefix, weight = 0f),
                shown(prefix, after = before, weight = 0f),
            )
        }
    }

    /**
     * **Corrections are untouched**, which is the promise this change makes.
     * Ranking the strip and deciding to replace a word are different questions
     * (D37 notwithstanding, they read different numbers), and D43's and D45's
     * measurements were all of the second one.
     */
    @Test
    fun `the correction is the same with or without context`() {
        val source = source()
        listOf("hsnging", "teh", "probelm", "nmae", "dont", "wrold", "Krankenhais")
            .forEach { word ->
                val touches = typed(word)
                val plain = source.candidatesFor(word, touches, Preceding.Unknown).correction
                val context = source.candidatesFor(word, touches, Preceding.Word("die")).correction
                assertEquals("$word text", plain?.text, context?.text)
                assertEquals("$word confidence", plain?.confidence, context?.confidence)
            }
    }

    /** Three slots, however big the bucket behind them is. */
    @Test
    fun `never more than the strip holds`() {
        listOf("s", "a", "e", "sc", "th").forEach {
            assertTrue(it, shown(it).size <= SuggestionSlots.CAPACITY)
            assertTrue(it, shown(it, after = "die").size <= SuggestionSlots.CAPACITY)
        }
    }

    /**
     * The biggest bucket there is — `s` matches 3,662 German words and 4,187
     * English ones — has to stay inside the per-keystroke budget, because it is
     * the *first* keystroke of a word and so the commonest one there is.
     */
    @Test
    fun `a one-letter prefix is still cheap`() {
        val source = source()
        val prefixes = listOf("s", "a", "b", "e")
        val touches = prefixes.associateWith { typed(it) }
        repeat(300) { prefixes.forEach { source.candidatesFor(it, touches.getValue(it), Preceding.Word("die")) } }

        val started = System.nanoTime()
        val runs = 400
        repeat(runs) {
            prefixes.forEach { source.candidatesFor(it, touches.getValue(it), Preceding.Word("die")) }
        }
        val each = (System.nanoTime() - started) / 1e6 / (runs * prefixes.size)
        // Generous against a laptop measured at a third of a millisecond, so
        // this fails on a real regression rather than on a noisy CI runner.
        assertTrue("a one-letter completion took ${"%.3f".format(each)}ms", each < 3.0)
    }
}
