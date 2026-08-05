package de.coonabibba.bikeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchModelTest {

    /** Three keys in a row, 100 wide, centres at 50, 150, 250. */
    private val geometry = KeyGeometry(
        listOf(
            KeyGeometry.Entry('a', 50f, 50f),
            KeyGeometry.Entry('s', 150f, 50f),
            KeyGeometry.Entry('d', 250f, 50f),
        ),
        keyWidth = 100f,
    )

    /**
     * A neighbour a full key away is no likelier than any other letter, so it
     * is not carried at all — [TypedTouch.costOf] charges full price for
     * anything it has not heard of, which is the same answer for less work.
     */
    @Test
    fun `a touch dead centre has no plausible neighbours`() {
        val alternatives = geometry.alternatives(50f, 50f, 'a')
        assertTrue("got $alternatives", alternatives.isEmpty())
        assertNull(alternatives['d'])
    }

    @Test
    fun `a touch drifting towards a neighbour brings it into reach`() {
        val alternatives = geometry.alternatives(90f, 50f, 'a')
        assertTrue("s costs ${alternatives['s']}", alternatives.getValue('s') < 0.5f)
        assertNull("d is still two keys away", alternatives['d'])
    }

    /** The case the whole thing exists for: a thumb between two keys. */
    @Test
    fun `a touch on the border makes both keys nearly free`() {
        val alternatives = geometry.alternatives(100f, 50f, 'a')
        assertTrue("s costs ${alternatives['s']}", alternatives.getValue('s') < 0.1f)
    }

    @Test
    fun `the key that was pressed costs nothing`() {
        val touch = TypedTouch('a', geometry.alternatives(60f, 50f, 'a'))
        assertEquals(0f, touch.costOf('a'), 0f)
    }

    @Test
    fun `a key nowhere near the touch costs full price`() {
        val touch = TypedTouch('a', geometry.alternatives(50f, 50f, 'a'))
        assertEquals(TypedTouch.FAR, touch.costOf('d'), 0f)
        assertEquals(TypedTouch.FAR, touch.costOf('z'), 0f)
    }

    @Test
    fun `a character typed without a touch has no near misses`() {
        val touch = TypedTouch.untouched('ü')
        assertEquals(0f, touch.costOf('ü'), 0f)
        assertEquals(TypedTouch.FAR, touch.costOf('u'), 0f)
    }

    @Test
    fun `an empty keyboard offers no alternatives rather than crashing`() {
        assertTrue(KeyGeometry(emptyList(), 0f).alternatives(10f, 10f, 'a').isEmpty())
    }
}
