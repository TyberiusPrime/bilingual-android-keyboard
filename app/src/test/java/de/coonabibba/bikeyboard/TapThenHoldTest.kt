package de.coonabibba.bikeyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that lets one key carry both a letter and a gesture (D39).
 *
 * Worth testing on its own because getting it wrong is not a bug anyone would
 * report clearly: too eager and `h` occasionally refuses to type while
 * scrolling the text instead; too shy and the gesture simply cannot be found.
 */
class TapThenHoldTest {

    private val h = Layouts.letters.rows.flatten().first { it.steersLines }
    private val a = Layouts.letters.rows.flatten().first { it.label == "a" }

    private fun gesture() = TapThenHold(windowMs = 300)

    @Test
    fun `a press with nothing before it arms nothing`() {
        assertFalse(gesture().arms(h, now = 1000))
    }

    @Test
    fun `a tap arms a press that follows it quickly`() {
        val steer = gesture()
        steer.tap(h, now = 1000)
        assertTrue(steer.arms(h, now = 1200))
    }

    @Test
    fun `a tap does not arm a press that comes too late`() {
        val steer = gesture()
        steer.tap(h, now = 1000)
        assertFalse(steer.arms(h, now = 1400))
    }

    @Test
    fun `a tap on one key does not arm a press on another`() {
        val steer = gesture()
        steer.tap(a, now = 1000)
        assertFalse(steer.arms(h, now = 1100))
    }

    /**
     * Holding and releasing repeatedly must not need a rhythm, so reading the
     * state does not spend it. The tap is retired by the *next* thing to
     * happen, not by having been looked at.
     */
    @Test
    fun `asking twice gives the same answer`() {
        val steer = gesture()
        steer.tap(h, now = 1000)
        assertTrue(steer.arms(h, now = 1100))
        assertTrue(steer.arms(h, now = 1100))
    }

    @Test
    fun `anything that is not a plain tap breaks the pair`() {
        val steer = gesture()
        steer.tap(h, now = 1000)
        steer.reset()
        assertFalse(steer.arms(h, now = 1100))
    }

    /**
     * A settings change rebuilds the layout, and every [Key] in it is a new
     * object equal to the one it replaced. A gesture half-made must survive
     * that, so the comparison is by value.
     */
    @Test
    fun `an equal key from a rebuilt layout still arms`() {
        val steer = gesture()
        steer.tap(h, now = 1000)
        val rebuilt = Layouts.letters.rows.flatten().first { it.steersLines }
        assertTrue(steer.arms(rebuilt, now = 1100))
    }

    /** Three taps in a row: the second arms, and so does the third. */
    @Test
    fun `a run of taps keeps the gesture available throughout`() {
        val steer = gesture()
        steer.tap(h, now = 1000)
        assertTrue(steer.arms(h, now = 1100))
        steer.tap(h, now = 1150)
        assertTrue(steer.arms(h, now = 1250))
    }
}
