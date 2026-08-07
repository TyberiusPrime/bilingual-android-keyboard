package de.coonabibba.bikeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `i` into `I`, and `ivll` into `I'll` (D41), against the wordlists that ship.
 *
 * On the real lists rather than a fixture, because the whole question is what
 * the two corpora say about casing and which of them wins — a made-up lexicon
 * would just be asserting the numbers I chose for it.
 */
class CapitalFormsTest {

    private val source: DictionarySuggestions by lazy {
        val store = PersonalStore(File.createTempFile("caps", ".txt").also { it.delete() })
        store.load()
        DictionarySuggestions(SwipeFixtures.lexicons, store)
    }

    private fun untouched(word: String) = word.map(TypedTouch::untouched)
    private fun correct(word: String) = source.candidatesFor(word, untouched(word)).correction
    private fun strip(word: String) =
        source.candidatesFor(word, untouched(word)).suggestions.map { it.text }

    /**
     * The second commonest word in English, which used to get no help at all:
     * one letter never reached the strip, and case is ignored when judging
     * whether a spelling is right, so it was never corrected either.
     */
    @Test
    fun `a lone i becomes I`() {
        val fix = correct("i")
        assertEquals("I", fix?.text)
        assertTrue("only ${fix?.confidence} sure", (fix?.confidence ?: 0f) > 0.9f)
    }

    /**
     * And the German `i` is still there to be chosen, because this is a
     * decision between two real words rather than a rule about capitals.
     */
    @Test
    fun `the lowercase form is still offered`() {
        assertTrue("i" in strip("i"))
        assertTrue("I" in strip("i"))
    }

    /** A word that is already right is left alone, one letter or not. */
    @Test
    fun `a lone a is not touched`() {
        assertNull(correct("a"))
        // `s` is lowercase in both languages, so there is no other casing.
        assertNull(correct("s"))
    }

    /**
     * The apostrophe is a long-press on `v` (D17), so a tap where a hold was
     * meant leaves a `v` behind. Every position is tried, not just the last,
     * which is what reaches the I-forms — four percent of English typing.
     */
    @Test
    fun `a v where an apostrophe was meant is put right`() {
        mapOf(
            "ivll" to "I'll",
            "ivm" to "I'm",
            "ivve" to "I've",
            "ivd" to "I'd",
            "donvt" to "don't",
            "youvre" to "you're",
            "wevre" to "we're",
            "letvs" to "let's",
        ).forEach { (typed, expected) ->
            val fix = correct(typed)
            assertEquals(typed, expected, fix?.text)
            assertTrue(
                "$typed -> $expected only ${fix?.confidence} sure",
                (fix?.confidence ?: 0f) > 0.9f,
            )
        }
    }

    /**
     * The productive rule survives beside the lookup: any stem may take `'s`,
     * and no wordlist can list them all.
     */
    @Test
    fun `any stem can still take an apostrophe s`() {
        assertTrue("have's" in strip("havevs"))
        assertTrue("geht's" in strip("gehtvs"))
    }

    /** A `v` that is just a `v` is left alone. */
    @Test
    fun `an ordinary word with a v is not mangled`() {
        listOf("over", "haven", "leben", "voll").forEach {
            val fix = correct(it)
            assertTrue("$it was changed to ${fix?.text}", fix == null || !fix.text.contains('\''))
        }
    }
}
