package de.coonabibba.bikeyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceGestureTest {

    private val gesture = SpaceGesture(doubleTapMs = 350L)

    @Test
    fun `a single tap inserts a space`() {
        assertFalse(gesture.tap(1_000L))
    }

    @Test
    fun `two quick taps end the sentence`() {
        assertFalse(gesture.tap(1_000L))
        assertTrue(gesture.tap(1_200L))
    }

    @Test
    fun `two slow taps are two spaces`() {
        assertFalse(gesture.tap(1_000L))
        assertFalse(gesture.tap(1_400L))
    }

    /** A third tap starts a fresh pair rather than chaining another full stop. */
    @Test
    fun `the pair is consumed`() {
        gesture.tap(1_000L)
        assertTrue(gesture.tap(1_100L))
        assertFalse(gesture.tap(1_200L))
        assertTrue(gesture.tap(1_300L))
    }

    /**
     * Accepting a suggestion already put a space there, so one more tap is the
     * second half of the gesture.
     */
    @Test
    fun `one tap after an accepted suggestion ends the sentence`() {
        gesture.suggestionAccepted()
        assertTrue(gesture.tap(1_000L))
    }

    @Test
    fun `an accepted suggestion does not stay armed forever`() {
        gesture.suggestionAccepted()
        assertTrue(gesture.tap(1_000L))
        assertFalse("consumed by the tap that used it", gesture.tap(5_000L))
    }

    @Test
    fun `typing after an accepted suggestion disarms it`() {
        gesture.suggestionAccepted()
        gesture.otherInput()
        assertFalse(gesture.tap(1_000L))
    }

    /**
     * The bug in the key-and-timestamp version this replaced: nothing between
     * two spaces ever reset the state, so `space`, letter, `space` typed fast
     * produced a full stop in the middle of a sentence.
     */
    @Test
    fun `a letter between two spaces breaks the pair`() {
        assertFalse(gesture.tap(1_000L))
        gesture.otherInput()
        assertFalse(gesture.tap(1_100L))
    }
}
