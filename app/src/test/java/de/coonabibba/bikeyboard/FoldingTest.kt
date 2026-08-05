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
}
