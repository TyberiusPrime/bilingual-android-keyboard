package de.coonabibba.bikeyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class TextCaseTest {

    /** Three states, in a cycle: what you typed, capitalised, shouted. */
    @Test
    fun `the cycle goes lower, capitalised, upper, lower`() {
        assertEquals("Zeit", TextCase.cycle("zeit"))
        assertEquals("ZEIT", TextCase.cycle("Zeit"))
        assertEquals("zeit", TextCase.cycle("ZEIT"))
    }

    @Test
    fun `three swipes come back to where they started`() {
        var word = "haus"
        repeat(3) { word = TextCase.cycle(word) }
        assertEquals("haus", word)
    }

    @Test
    fun `umlauts and sharp s survive the trip`() {
        assertEquals("Über", TextCase.cycle("über"))
        assertEquals("ÜBER", TextCase.cycle("Über"))
        assertEquals("Straße", TextCase.cycle("straße"))
        // Shouting a sharp s spells it out, which is correct German.
        assertEquals("STRASSE", TextCase.cycle("Straße"))
    }

    @Test
    fun `a single letter still cycles`() {
        assertEquals("I", TextCase.cycle("i"))
        assertEquals("i", TextCase.cycle("I"))
    }

    /** A word cased deliberately is not something to second-guess into lower case. */
    @Test
    fun `mixed case goes to upper next`() {
        assertEquals("IPHONE", TextCase.cycle("iPhone"))
        assertEquals("MCDONALD", TextCase.cycle("McDonald"))
    }

    @Test
    fun `something with no letters is left alone by the first swipe`() {
        assertEquals("123", TextCase.cycle("123"))
    }
}
