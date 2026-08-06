package de.coonabibba.bikeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionsTest {

    private val width = 300f

    @Test
    fun `slots divide the strip evenly`() {
        assertEquals(0f, SuggestionSlots.left(width, 0), 0.01f)
        assertEquals(100f, SuggestionSlots.right(width, 0), 0.01f)
        assertEquals(100f, SuggestionSlots.left(width, 1), 0.01f)
        assertEquals(300f, SuggestionSlots.right(width, SuggestionSlots.CAPACITY - 1), 0.01f)
    }

    /**
     * The slots are fixed whether or not they are occupied, so the first
     * suggestion stays in the same place when a second one appears. A target
     * that moves between the look and the tap gets mis-hit.
     */
    @Test
    fun `a tap lands in the slot it is over`() {
        assertEquals(0, SuggestionSlots.indexAt(width, 0f))
        assertEquals(0, SuggestionSlots.indexAt(width, 99f))
        assertEquals(1, SuggestionSlots.indexAt(width, 100f))
        assertEquals(2, SuggestionSlots.indexAt(width, 299f))
    }

    @Test
    fun `a tap outside the strip lands nowhere`() {
        assertEquals(-1, SuggestionSlots.indexAt(width, -1f))
        assertEquals(-1, SuggestionSlots.indexAt(width, 300f))
        assertEquals(-1, SuggestionSlots.indexAt(0f, 0f))
    }

    /** Until step 4 there is nothing to suggest, and nothing is what it says. */
    @Test
    fun `the placeholder source offers nothing`() {
        assertTrue(NoSuggestions.suggest("hal", emptyList()).isEmpty())
        assertTrue(NoSuggestions.suggest("", emptyList()).isEmpty())
    }
}
