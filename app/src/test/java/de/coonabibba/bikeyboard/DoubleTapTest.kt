package de.coonabibba.bikeyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleTapTest {

    private val taps = DoubleTap(windowMs = 350L)

    @Test
    fun `one tap is not two`() {
        assertFalse(taps.tap(1_000L))
    }

    @Test
    fun `two quick taps are a pair`() {
        taps.tap(1_000L)
        assertTrue(taps.tap(1_200L))
    }

    @Test
    fun `two slow taps are not`() {
        taps.tap(1_000L)
        assertFalse(taps.tap(1_400L))
    }

    /** Otherwise a run of taps would fire on every one after the first. */
    @Test
    fun `the pair is consumed`() {
        taps.tap(1_000L)
        assertTrue(taps.tap(1_100L))
        assertFalse(taps.tap(1_200L))
        assertTrue(taps.tap(1_300L))
    }

    @Test
    fun `anything in between breaks the pair`() {
        taps.tap(1_000L)
        taps.reset()
        assertFalse(taps.tap(1_100L))
    }
}
