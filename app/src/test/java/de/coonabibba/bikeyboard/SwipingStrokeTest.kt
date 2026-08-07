package de.coonabibba.bikeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

/**
 * The stroke that came back as `seeing`, read off the phone rather than
 * synthesised (D39b).
 *
 * `s` up to `w`, right along the whole top row to `p`, back to `i`, down to
 * `n`, up to `g`. That is `swiping` — and it was decoded as `seeing`, with
 * `song` and `strong` offered beside it. All three ignore the corner at `w`,
 * and `strong` additionally demands a back-and-forth over `r` and `t` that the
 * finger never made.
 */
class SwipingStrokeTest {

    private val store = PersonalStore(File.createTempFile("gesture", ".txt").also { it.delete() })
    private val source: DictionarySuggestions by lazy {
        store.load()
        DictionarySuggestions(SwipeFixtures.lexicons, store)
    }
    private val decoder = GestureDecoder(SwipeFixtures.geometry)

    /** The corners the finger actually turned at, joined up and sampled. */
    private val observed: GesturePath by lazy {
        val corners = listOf('s', 'w', 'p', 'i', 'n', 'g').map { SwipeFixtures.centre(it) }
        val xs = mutableListOf<Float>()
        val ys = mutableListOf<Float>()
        for (i in 1 until corners.size) {
            val a = corners[i - 1]
            val b = corners[i]
            val steps = (hypot(b.centreX - a.centreX, b.centreY - a.centreY) / 6f)
                .toInt().coerceAtLeast(1)
            for (step in 0 until steps) {
                val t = step.toFloat() / steps
                xs += a.centreX + (b.centreX - a.centreX) * t
                ys += a.centreY + (b.centreY - a.centreY) * t
            }
        }
        xs += corners.last().centreX
        ys += corners.last().centreY
        GesturePath.of(xs.toFloatArray(), ys.toFloatArray())!!
    }

    /**
     * The three words that beat it must now lose, and by a distance.
     *
     * Each fails to account for a corner. `seeing` and `song` never approach
     * `w`, where the finger plainly went before setting off right; `strong`
     * wants `t` then `r`, which is a reversal along the top row that never
     * happened. Weighting corners above straights is what charges them for it —
     * `seeing` goes from 0.66 to 1.08 as the emphasis rises, while `swiping`
     * does not move at all.
     */
    @Test
    fun `a word that ignores a corner is not a candidate`() {
        val right = decoder.cost(observed, "swiping")
        assertTrue("swiping should fit this stroke almost exactly: $right", right < 0.15f)
        listOf("seeing", "song", "strong").forEach { wrong ->
            val cost = decoder.cost(observed, wrong)
            assertTrue("$wrong costs only $cost against swiping's $right", cost > right * 8f)
        }
    }

    @Test
    fun `none of them is offered any more`() {
        val got = source.candidatesForGesture(observed, SwipeFixtures.geometry).suggestions
            .map { it.text }
        listOf("seeing", "song", "strong").forEach {
            assertTrue("$it is still offered: $got", it !in got)
        }
    }

    /**
     * What is left is a three-way tie that geometry cannot break. `swiping`,
     * `sweeping` and `swooping` are `s w i p i n g`, `s w e p i n g` and
     * `s w o p i n g` — and `e`, `i`, `o` and `p` all sit on the top row at the
     * same height, so all three trace the identical polyline. Frequency orders
     * them; the strip carries all three; the personal key settles it for good.
     */
    @Test
    fun `the words that remain trace one identical route`() {
        val swiping = decoder.cost(observed, "swiping")
        listOf("sweeping", "swooping").forEach {
            assertEquals("$it should be the same stroke", swiping, decoder.cost(observed, it), 1e-4f)
        }
        val got = source.candidatesForGesture(observed, SwipeFixtures.geometry).suggestions
            .map { it.text }
        assertTrue("swiping is not even offered: $got", "swiping" in got)
    }

    @Test
    fun `teaching the word wins the tie permanently`() {
        source.candidatesForGesture(observed, SwipeFixtures.geometry)
        store.add("swiping")
        val got = source.candidatesForGesture(observed, SwipeFixtures.geometry).suggestions
        assertEquals("swiping", got.firstOrNull()?.text)
    }
}
