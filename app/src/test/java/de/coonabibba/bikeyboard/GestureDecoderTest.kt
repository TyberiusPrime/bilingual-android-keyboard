package de.coonabibba.bikeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ideal paths: what a word looks like as a journey. */
class GestureDecoderTest {

    private val decoder = GestureDecoder(SwipeFixtures.geometry)

    @Test
    fun `the ideal path of a word starts and ends on its letters`() {
        val path = decoder.idealPath("cat")!!
        assertEquals(SwipeFixtures.centre('c').centreX, path.startX, 0.5f)
        assertEquals(SwipeFixtures.centre('t').centreX, path.endX, 0.5f)
    }

    /** The finger does not move for the second `l` of `hello`. */
    @Test
    fun `a doubled letter costs no travel`() {
        assertEquals(decoder.idealLength("helo"), decoder.idealLength("hello"), 1e-3f)
    }

    /**
     * The apostrophe is a long-press (D17), so no finger ever goes there. A
     * candidate that demanded one could never be reached, which would put every
     * contraction permanently out of swiping range.
     */
    @Test
    fun `a character with no key is skipped rather than refused`() {
        assertEquals(decoder.idealLength("dont"), decoder.idealLength("don't"), 1e-3f)
        // The two spellings are the *same stroke*, which is the point — not
        // that either costs nothing. A perfect trace still carries a small
        // residue, because resampling cuts corners and a word's letters are
        // exactly where its corners are.
        val stroke = SwipeFixtures.perfectSwipe("dont")
        assertEquals(decoder.cost(stroke, "dont"), decoder.cost(stroke, "don't"), 1e-4f)
    }

    /** Accents fold onto the key they are typed from. */
    @Test
    fun `an accented word traces the plain one`() {
        listOf("uber" to "über", "strasse" to "Straße").forEach { (plain, accented) ->
            val stroke = SwipeFixtures.perfectSwipe(plain)
            assertEquals(decoder.cost(stroke, plain), decoder.cost(stroke, accented), 1e-4f)
        }
    }

    @Test
    fun `a word of one key cannot be swiped`() {
        assertEquals(GestureDecoder.UNSWIPEABLE, decoder.idealLength("aaa"), 0f)
        assertEquals(GestureDecoder.UNSWIPEABLE, decoder.idealLength("a"), 0f)
        assertNull(decoder.idealPath("aaa"))
        assertEquals(GestureDecoder.UNSWIPEABLE, decoder.cost(SwipeFixtures.perfectSwipe("at"), "aaa"), 0f)
    }

    @Test
    fun `a word of nothing but unswipeable characters has no path`() {
        assertNull(decoder.idealPath("'''"))
        assertEquals(GestureDecoder.UNSWIPEABLE, decoder.idealLength("42"), 0f)
    }

    /** Case is not a thing the finger can express. */
    @Test
    fun `case makes no difference to the journey`() {
        assertEquals(decoder.idealLength("berlin"), decoder.idealLength("Berlin"), 1e-3f)
    }

    /**
     * A perfect trace is cheap but not free: resampling cuts across corners,
     * and a word's letters sit on its corners. What matters is the gap to a
     * word the finger did not trace.
     */
    @Test
    fun `a perfect trace is far cheaper than a wrong word`() {
        val path = SwipeFixtures.perfectSwipe("keyboard")
        val right = decoder.cost(path, "keyboard")
        assertTrue("a perfect trace cost $right", right < 0.25f)
        assertTrue(decoder.cost(path, "kitchen") > right * 3f)
    }

    /** Longer words need a longer buffer; the decoder grows its own. */
    @Test
    fun `a very long word still traces`() {
        assertNotNull(decoder.idealPath("Rindfleischetikettierungsueberwachungsaufgabenuebertragungsgesetz"))
    }
}
