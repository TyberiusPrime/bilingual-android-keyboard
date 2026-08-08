package de.coonabibba.bikeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The trigram prior (D43), and the correction it exists to unblock. */
class WordShapeTest {
    private val shape: WordShape by lazy { WordShape.of(SwipeFixtures.lexicons) }

    private val source: DictionarySuggestions by lazy {
        val store = PersonalStore(File.createTempFile("shape", ".txt").also { it.delete() })
        store.load()
        DictionarySuggestions(SwipeFixtures.lexicons, store)
    }
    private val keys = SwipeFixtures.geometry
    private val threshold = KeyboardPrefs.DEFAULT_AUTO_CORRECT_CONFIDENCE / 100f

    /** Typed dead centre on every key, which is the least helpful case. */
    private fun typed(word: String) = word.map { ch ->
        val e = keys.centreOf(ch) ?: return@map TypedTouch.untouched(ch)
        TypedTouch(ch, keys.alternatives(e.centreX, e.centreY, ch))
    }

    /**
     * The bug this was built for: `hanging` was the *only* candidate for
     * `hsnging` and still scored 0.05, because a flat prior rated a string with
     * no possible German or English trigram in it as plausibly somebody's name.
     */
    @Test
    fun `an impossible string is corrected even with no touch evidence`() {
        val correction = source.candidatesFor("hsnging", "hsnging".map(TypedTouch::untouched))
            .correction
        assertEquals("hanging", correction?.text)
        assertTrue("confidence was ${correction?.confidence}", correction!!.confidence >= threshold)
    }

    /** D8: what the typist actually wrote survives, however odd it looks. */
    @Test
    fun `names are still left alone`() {
        listOf("Coonabibba", "Tyberius", "Xiaomi", "systemd", "Wrzesniewski", "Nkechi")
            .forEach { name ->
                val correction = source.candidatesFor(name, typed(name)).correction
                assertTrue(
                    "$name became ${correction?.text} at ${correction?.confidence}",
                    correction == null || correction.confidence < threshold,
                )
            }
    }

    /**
     * The ceiling, which is what makes this a one-way change: a word-shaped
     * string keeps exactly the protection the flat prior gave it and gains
     * none, so nothing the model believes can make the keyboard correct *less*
     * than it did before.
     */
    @Test
    fun `plausibility never exceeds one`() {
        listOf("the", "und", "hanging", "Schmetterling", "aaaaaa", "x", "", "ß", "…")
            .forEach { assertTrue("$it: ${shape.plausibility(it)}", shape.plausibility(it) <= 1f) }
    }

    /** Ordinary words sit near the ceiling; strings with no possible trigram do not. */
    @Test
    fun `word shape separates words from noise`() {
        listOf("hanging", "people", "Geschichte", "haben").forEach {
            assertTrue("$it: ${shape.plausibility(it)}", shape.plausibility(it) > 0.1f)
        }
        listOf("hsnging", "qwertz", "zomorrow", "xkcdvbn").forEach {
            assertTrue("$it: ${shape.plausibility(it)}", shape.plausibility(it) < 0.01f)
        }
    }

    /**
     * A trigram table has nineteen thousand cells, so a fixture-sized wordlist
     * teaches it nothing but the fixture. It declines rather than guess — the
     * same answer that keeps a failed asset load from rewriting text.
     */
    @Test
    fun `a corpus too small to learn from has no opinion`() {
        val tiny = Lexicon(Language.GERMAN, listOf("haben", "Haus"), longArrayOf(700, 300))
        val shape = WordShape.of(listOf(tiny))
        assertEquals(1f, shape.plausibility("hsnging"), 1e-6f)
        assertEquals(1f, WordShape.of(emptyList()).plausibility("hsnging"), 1e-6f)
    }

    /**
     * How rare an unseen trigram is taken to be barely matters, which is worth
     * pinning: the answers move by less than an order of magnitude across two
     * orders of magnitude of smoothing, so [WordShape]'s default is not a
     * balanced-on-a-knife-edge number.
     */
    @Test
    fun `the smoothing constant sits on a plateau`() {
        val shapes = listOf(1f, 0.3f, 0.1f, 0.03f, 0.01f)
            .map { WordShape.of(SwipeFixtures.lexicons, it) }
        shapes.forEach { assertTrue(it.plausibility("hsnging") < 0.01f) }
        shapes.forEach { assertTrue(it.plausibility("hanging") > 0.1f) }
    }

    /** It is built on the disk thread beside the wordlists, and must stay cheap enough for that. */
    @Test
    fun `building it costs one pass`() {
        WordShape.of(SwipeFixtures.lexicons)
        val start = System.nanoTime()
        WordShape.of(SwipeFixtures.lexicons)
        val millis = (System.nanoTime() - start) / 1e6
        println("  WordShape built in %.0f ms".format(millis))
        assertTrue("took $millis ms", millis < 500)
    }
}
