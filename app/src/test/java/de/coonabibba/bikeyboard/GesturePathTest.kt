package de.coonabibba.bikeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * Resampling, which everything above it assumes is exact.
 *
 * Worth its own tests because the failure is silent: a resampler that is a
 * little wrong still returns a plausible-looking path, and the only symptom is
 * that swiping gets worse for reasons nothing points at.
 */
class GesturePathTest {

    private fun path(vararg points: Pair<Float, Float>): GesturePath? =
        GesturePath.of(
            points.map { it.first }.toFloatArray(),
            points.map { it.second }.toFloatArray(),
        )

    @Test
    fun `a straight line resamples to evenly spaced points`() {
        val line = path(0f to 0f, 100f to 0f)!!
        assertEquals(GesturePath.SAMPLES, line.xs.size)
        assertEquals(100f, line.length, 1e-3f)
        line.xs.forEachIndexed { i, x ->
            val expected = 100f * i / (GesturePath.SAMPLES - 1)
            assertEquals("sample $i", expected, x, 0.01f)
        }
    }

    /** The two ends are the two things a swipe is deliberate about. */
    @Test
    fun `the endpoints survive exactly`() {
        val bent = path(10f to 10f, 200f to 40f, 60f to 300f)!!
        assertEquals(10f, bent.startX, 1e-3f)
        assertEquals(10f, bent.startY, 1e-3f)
        assertEquals(60f, bent.endX, 1e-3f)
        assertEquals(300f, bent.endY, 1e-3f)
    }

    /**
     * The point of resampling: how fast the finger moved must not survive it.
     * The same shape reported at two different rates has to come out the same.
     */
    @Test
    fun `sampling rate does not change the result`() {
        val sparse = path(0f to 0f, 300f to 0f, 300f to 300f)!!
        val dense = GesturePath.of(
            FloatArray(61) { if (it <= 30) it * 10f else 300f },
            FloatArray(61) { if (it <= 30) 0f else (it - 30) * 10f },
        )!!
        sparse.xs.indices.forEach { i ->
            assertEquals("x at $i", sparse.xs[i], dense.xs[i], 0.5f)
            assertEquals("y at $i", sparse.ys[i], dense.ys[i], 0.5f)
        }
    }

    /** Bunched-up reports where the finger slowed must not bunch up samples. */
    @Test
    fun `a pause in the middle does not pull samples toward it`() {
        val paused = GesturePath.of(
            floatArrayOf(0f, 50f, 50f, 50f, 50f, 50f, 100f),
            floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f),
        )!!
        assertEquals(100f, paused.length, 1e-3f)
        assertEquals(50f, paused.xs[(GesturePath.SAMPLES - 1) / 2], 3f)
    }

    @Test
    fun `a tap is not a gesture`() {
        assertNull(path(5f to 5f))
        assertNull(path(5f to 5f, 5f to 5f, 5f to 5f))
        assertNotNull(path(5f to 5f, 6f to 5f))
    }

    @Test
    fun `distance to itself is zero and rises with separation`() {
        val a = path(0f to 0f, 100f to 100f)!!
        val b = path(0f to 40f, 100f to 140f)!!
        assertEquals(0f, a.distanceTo(a, keyWidth = 40f), 1e-4f)
        assertEquals(1f, a.distanceTo(b, keyWidth = 40f), 1e-3f)
    }

    /** Nothing may divide by a key width of zero — a view mid-layout has one. */
    @Test
    fun `a keyboard with no width yields no distance`() {
        val a = path(0f to 0f, 10f to 0f)!!
        assertEquals(Float.MAX_VALUE, a.distanceTo(a, keyWidth = 0f), 0f)
    }

    private fun walk(path: GesturePath): Float {
        var walked = 0f
        for (i in 1 until path.xs.size) {
            walked += hypot(path.xs[i] - path.xs[i - 1], path.ys[i] - path.ys[i - 1])
        }
        return walked
    }

    /**
     * A real word's path keeps its length: the samples land close enough
     * together to follow it round the corners rather than across them.
     *
     * Measured on an actual eight-letter journey rather than a synthetic
     * sawtooth, because the thing being checked is that [GesturePath.SAMPLES]
     * is enough for the input it will really see.
     *
     * A tenth is the real, measured loss and the bound is set just past it, so
     * that lowering the sample count fails here rather than quietly making
     * every long word a slightly worse match.
     */
    @Test
    fun `resampling roughly preserves the length of a word-shaped path`() {
        val shape = GestureDecoder(SwipeFixtures.geometry).idealPath("keyboard")!!
        val walked = walk(shape)
        assertTrue("resampled $walked against ${shape.length}", walked > shape.length * 0.9f)
        assertTrue("resampled $walked against ${shape.length}", walked <= shape.length + 1f)
    }

    /**
     * More corners than samples and detail is lost — necessarily, and it is
     * worth knowing where the limit is. [GesturePath.SAMPLES] is chosen against
     * the longest word anyone swipes, not against arbitrary input; a shape with
     * nineteen reversals in it is not a word and comes out shorter because the
     * samples cut straight across its teeth.
     */
    @Test
    fun `a path with more corners than samples loses detail`() {
        val zigzag = GesturePath.of(
            FloatArray(20) { it * 17f },
            FloatArray(20) { if (it % 2 == 0) 0f else 30f },
        )!!
        assertTrue(walk(zigzag) < zigzag.length * 0.9f)
    }
}
