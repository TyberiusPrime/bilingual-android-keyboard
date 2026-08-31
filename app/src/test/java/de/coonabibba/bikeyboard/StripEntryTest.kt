package de.coonabibba.bikeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D33: the strip's purple means "pressing space substitutes this", and nothing
 * looser. These are the cases where the old "scores well" rule and the new one
 * give different answers.
 */
class StripEntryTest {

    private fun suggestion(text: String, confidence: Float = 0.5f) =
        Suggestion(text, confidence, Language.ENGLISH)

    private fun correction(text: String, original: String = "teh", confidence: Float = 0.97f) =
        Correction(text, original, confidence, Language.ENGLISH)

    @Test
    fun `with no correction pending nothing is marked`() {
        val entries = StripEntry.mark(
            listOf(suggestion("hello", 0.99f), suggestion("help")),
            correction = null,
        )
        assertEquals(2, entries.size)
        assertTrue(entries.none { it.replaces })
    }

    /**
     * The case the old rule got wrong in the common direction: a completion can
     * hold almost all the mass — `hel` is going to be `hello` — but space does
     * not turn `hel` into `hello`, it just ends the word.
     */
    @Test
    fun `a confident completion is not marked when nothing would be replaced`() {
        val entries = StripEntry.mark(listOf(suggestion("hello", 0.99f)), correction = null)
        assertFalse(entries.single().replaces)
    }

    @Test
    fun `the candidate space would substitute is the one marked`() {
        val entries = StripEntry.mark(
            listOf(suggestion("ten"), suggestion("the"), suggestion("tea")),
            correction("the"),
        )
        assertEquals(listOf(false, true, false), entries.map { it.replaces })
    }

    /**
     * Ranking and correcting are separate questions, so the corrector can name a
     * word the ranker did not offer. A warning that is sometimes invisible is
     * not a warning.
     */
    @Test
    fun `a correction missing from the candidates is added in front`() {
        val entries = StripEntry.mark(
            listOf(suggestion("ten"), suggestion("tea")),
            correction("the"),
        )
        assertEquals(listOf("the", "ten", "tea"), entries.map { it.label })
        assertEquals(listOf(true, false, false), entries.map { it.replaces })
    }

    @Test
    fun `a correction already present is not added twice`() {
        val entries = StripEntry.mark(listOf(suggestion("the")), correction("the"))
        assertEquals(listOf("the"), entries.map { it.label })
    }

    @Test
    fun `an inserted correction keeps its own confidence and language`() {
        val added = StripEntry.mark(
            emptyList(),
            Correction("Straße", original = "strasse", confidence = 0.94f, language = Language.GERMAN),
        ).single()
        assertEquals(0.94f, added.suggestion.confidence, 1e-6f)
        assertEquals(Language.GERMAN, added.suggestion.language)
    }

    // -- what a slot may lose when it does not fit (D50) ----------------------

    /**
     * A candidate is nothing but its word, so the whole of it may go if the
     * slot is too narrow — from the front, which is the part already typed.
     */
    @Test
    fun `a candidate is all elidable`() {
        val entry = StripEntry.Word(suggestion("Geschwindigkeitsbegrenzung"))
        assertEquals("", entry.marker)
        assertEquals("Geschwindigkeitsbegrenzung", entry.body)
        assertEquals(entry.body, entry.label)
    }

    /**
     * The add-word offer is the one entry with something in front of the word
     * that is not the word. Eliding from the front would eat the plus first,
     * and the plus is the whole reason the slot is there — the long entries
     * are exactly the ones this happens to, since addresses and paths are what
     * people put in the store (D40).
     */
    @Test
    fun `the plus is not part of what an add-word offer may lose`() {
        val entry = StripEntry.AddWord("john@coonabibba.de")
        assertEquals("+ ", entry.marker)
        assertEquals("john@coonabibba.de", entry.body)
        assertEquals("+ john@coonabibba.de", entry.label)
    }

    /** Exactly one word gets substituted, so exactly one may be purple. */
    @Test
    fun `only the first match is marked`() {
        val entries = StripEntry.mark(
            listOf(suggestion("the"), suggestion("the"), suggestion("ten")),
            correction("the"),
        )
        assertEquals(listOf(true, false, false), entries.map { it.replaces })
    }
}
