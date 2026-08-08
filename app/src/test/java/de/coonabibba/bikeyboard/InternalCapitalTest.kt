package de.coonabibba.bikeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A capital inside a word means a name, and names are not corrected (D44).
 */
class InternalCapitalTest {
    private val source: DictionarySuggestions by lazy {
        val store = PersonalStore(File.createTempFile("caps", ".txt").also { it.delete() })
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

    /** Brands, products and identifiers, which is what a middle capital means. */
    private val named = listOf(
        "iPhone", "eBay", "McDonald", "JavaScript", "PostgreSQL", "TyberiusPrime",
        "GmbH", "NextCloud", "OpenStreetMap", "LaTeX", "iOS", "macOS", "YouTube",
        "PayPal", "DeepL", "WhatsApp", "SoundCloud", "AGit", "iTunes",
    )

    @Test
    fun `a capital in the middle turns correction off`() {
        named.forEach { word ->
            val fix = correctionFor(word)
            assertEquals("$word became ${fix?.text} at ${fix?.confidence}", null, fix)
        }
    }

    /**
     * `iOS` was replaced by `is` at 0.91 — the one that actually fired, with
     * `DeepL` to `Deep` and `AGit` to `Gait` a slider's width behind it.
     */
    @Test
    fun `iOS survives`() {
        assertEquals(null, correctionFor("iOS"))
        assertEquals(null, correctionFor("DeepL"))
    }

    /**
     * Shouting is a styling choice, not a claim about the word, so a word in
     * capitals throughout is corrected like any other.
     */
    @Test
    fun `shouting is still corrected`() {
        listOf("TEH" to "THE", "UDN" to "UND", "ADN" to "AND", "DONT" to "DON'T")
            .forEach { (shout, wanted) ->
                val fix = correctionFor(shout)
                assertEquals("$shout -> ${fix?.text}", wanted, fix?.text)
                assertTrue("$shout at ${fix?.confidence}", fix!!.confidence >= threshold)
            }
    }

    /** `I DONT CARE` used to come back as `I Don't CARE`. */
    @Test
    fun `a correction inside a shout stays shouted`() {
        assertEquals("DON'T", correctionFor("DONT")?.text)
        assertEquals("HELLO", correctionFor("HELOL")?.text)
    }

    @Test
    fun `ordinary words are unaffected`() {
        listOf("hsnging", "teh", "dont", "Krankenhais", "Gebauede").forEach {
            assertTrue("$it lost its correction", correctionFor(it) != null)
        }
    }

    @Test
    fun `what counts as an internal capital`() {
        listOf("iPhone", "eBay", "McDonald", "GmbH", "LaTeX", "iOS", "aB")
            .forEach { assertTrue(it, TextEdits.hasInternalCapital(it)) }
        listOf("Hello", "hello", "HELLO", "USA", "I", "A", "", "don't", "Don't", "MP3")
            .forEach { assertFalse(it, TextEdits.hasInternalCapital(it)) }
    }
}
