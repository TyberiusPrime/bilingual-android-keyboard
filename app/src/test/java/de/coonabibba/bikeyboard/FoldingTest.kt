package de.coonabibba.bikeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldingTest {

    @Test
    fun `case is folded away`() {
        assertEquals("haus", Folding.fold("Haus"))
        assertEquals("haus", Folding.fold("HAUS"))
    }

    /** The cheap half of D5: an umlaut costs a long-press, so skipping it must work. */
    @Test
    fun `umlauts fold to their bare letters`() {
        assertEquals("uber", Folding.fold("über"))
        assertEquals("hauser", Folding.fold("Häuser"))
        assertEquals("schon", Folding.fold("schön"))
    }

    @Test
    fun `sharp s folds to ss`() {
        assertEquals("strasse", Folding.fold("Straße"))
        assertEquals(Folding.fold("strasse"), Folding.fold("Straße"))
    }

    @Test
    fun `other accents fold too`() {
        assertEquals("cafe", Folding.fold("café"))
        assertEquals("naive", Folding.fold("naïve"))
    }

    @Test
    fun `apostrophes and hyphens survive`() {
        assertEquals("don't", Folding.fold("don't"))
        assertEquals("e-mail", Folding.fold("E-Mail"))
    }

    @Test
    fun `folding is idempotent`() {
        listOf("Über", "Straße", "café", "hello").forEach {
            assertEquals(Folding.fold(it), Folding.fold(Folding.fold(it)))
        }
    }

    @Test
    fun `prefix matching ignores case and accents`() {
        assertTrue(Folding.startsWith("über", "ub"))
        assertTrue(Folding.startsWith("Straße", "stras"))
        assertFalse(Folding.startsWith("über", "be"))
    }

    // -- what replaced the Unicode normaliser --------------------------------

    /**
     * The fifteen non-ASCII characters the two wordlists contain between them,
     * which is the entire alphabet this has to handle. Checked here so that
     * adding a sixteenth to the data without adding it to the table shows up as
     * a failing test rather than as a word nobody can find.
     */
    @Test
    fun `every accent the wordlists use folds to its bare letter`() {
        mapOf(
            'ä' to 'a', 'Ä' to 'a', 'à' to 'a', 'á' to 'a', 'â' to 'a',
            'ö' to 'o', 'Ö' to 'o', 'ó' to 'o',
            'ü' to 'u', 'Ü' to 'u',
            'é' to 'e', 'è' to 'e', 'ê' to 'e',
            'ñ' to 'n',
        ).forEach { (accented, bare) ->
            assertEquals("$accented", bare, Folding.foldChar(accented))
        }
        assertEquals("ss", Folding.fold("ß"))
    }

    /**
     * Text read back out of a field (D23) can arrive decomposed even though
     * nothing here produces it that way.
     */
    @Test
    fun `an accent that arrives as a separate character is dropped`() {
        assertEquals("uber", Folding.fold("u\u0308ber"))
        assertEquals(Folding.fold("über"), Folding.fold("u\u0308ber"))
    }

    /**
     * Everything the long-press popups can type folds (D32), including the
     * accents no German or English word contains — so a name added to the
     * personal store with its accents is still found when typed without them.
     */
    @Test
    fun `an accent the keyboard can type always folds away`() {
        assertEquals("zolw", Folding.fold("Żółw"))
        assertEquals("skoda", Folding.fold("Škoda"))
        assertEquals("lodz", Folding.fold("Łódź"))
        assertEquals("garcon", Folding.fold("garçon"))
        assertEquals("smorrebrod", Folding.fold("smørrebrød"))
    }

    /**
     * The accepted cost of dropping the normaliser: an accent from outside the
     * table stays put, so the word matches nothing — which is the right answer,
     * since it is not a word in either of these languages.
     */
    @Test
    fun `an accent from another alphabet is left alone`() {
        assertEquals("ελλάδα", Folding.fold("Ελλάδα"))
    }
}
