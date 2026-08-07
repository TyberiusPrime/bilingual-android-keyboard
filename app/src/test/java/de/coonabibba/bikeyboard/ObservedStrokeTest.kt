package de.coonabibba.bikeyboard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

/**
 * The second `swiping` stroke, traced off the screenshot pixel by pixel.
 *
 * Read in the screenshot's own coordinates and converted, rather than built
 * from key centres — because the interesting thing about this one is precisely
 * where it *misses* the key centres.
 */
class ObservedStrokeTest {

    private val store = PersonalStore(File.createTempFile("gesture", ".txt").also { it.delete() })
    private val source: DictionarySuggestions by lazy {
        store.load(); DictionarySuggestions(SwipeFixtures.lexicons, store)
    }
    private val decoder = GestureDecoder(SwipeFixtures.geometry)

    // Screenshot: top row y=390, home 547, bottom 705; q x=62, p x=1052.
    private fun fx(x: Float) = SwipeFixtures.centre('q').centreX +
        (x - 62f) * (SwipeFixtures.centre('p').centreX - SwipeFixtures.centre('q').centreX) / 990f
    private fun fy(y: Float) = SwipeFixtures.centre('q').centreY +
        (y - 390f) * (SwipeFixtures.centre('a').centreY - SwipeFixtures.centre('q').centreY) / 157f

    private val waypoints = listOf(
        215f to 550f,   // down on s
        195f to 500f,   // rising, curving left
        185f to 455f,   // the corner — low in the w key
        230f to 452f,
        280f to 450f,   // over e, well below the row
        390f to 440f,   // over r
        500f to 415f,   // over t
        610f to 400f,   // y
        720f to 393f,   // u
        830f to 388f,   // i
        940f to 405f,   // o
        1050f to 425f,  // p, the far corner
        960f to 400f,   // back left
        860f to 392f,
        838f to 470f,   // the turn down
        840f to 600f,
        845f to 690f,   // down past j into the bottom row
        800f to 710f,   // n
        740f to 700f,
        680f to 655f,
        610f to 590f,
        535f to 520f,   // lift on g
    )

    private fun observed(): GesturePath {
        val xs = mutableListOf<Float>()
        val ys = mutableListOf<Float>()
        for (i in 1 until waypoints.size) {
            val (ax, ay) = waypoints[i - 1]
            val (bx, by) = waypoints[i]
            val steps = (hypot(fx(bx) - fx(ax), fy(by) - fy(ay)) / 5f).toInt().coerceAtLeast(1)
            for (s in 0 until steps) {
                val t = s.toFloat() / steps
                xs += fx(ax) + (fx(bx) - fx(ax)) * t
                ys += fy(ay) + (fy(by) - fy(ay)) * t
            }
        }
        xs += fx(waypoints.last().first)
        ys += fy(waypoints.last().second)
        return GesturePath.of(xs.toFloatArray(), ys.toFloatArray())!!
    }

    /**
     * The stroke that came back as `stopping`.
     *
     * The finger came up off `s`, turned inside the **bottom** of the `w` key
     * and set off right — and `w` was billed 0.71 of a key for that, enough to
     * lose the word. Two things were wrong with the number, and neither was
     * about fingers:
     *
     * - the miss was almost all *vertical*, and every cost here is quoted in
     *   key *widths* while a phone's keys are half again as tall as they are
     *   wide, so it was inflated by half;
     * - the corner was **inside the `w` key**, which the keyboard's own hit
     *   testing would call a `w` without hesitating.
     *
     * With distances measured in key units and a key's own extent allowed for
     * free, `swiping` costs less than `stopping` on this stroke instead of
     * more. The order was 0.627 against 0.584; it is now 0.251 against 0.281.
     */
    @Test
    fun `the word the finger traced is the cheapest one`() {
        val path = observed()
        val swiping = decoder.cost(path, "swiping")
        listOf("stopping", "song", "seeing").forEach {
            assertTrue(
                "$it costs %.3f against swiping's %.3f".format(decoder.cost(path, it), swiping),
                decoder.cost(path, it) > swiping,
            )
        }
    }

    /**
     * Clipping the bottom of a key is not a miss.
     *
     * `w` sits 0.71 of a key width from the stroke measured centre to centre in
     * horizontal units, and none of that is the typist's fault: scale the
     * vertical properly and allow the key its own size, and the visit term for
     * `swiping` all but vanishes.
     */
    @Test
    fun `a corner inside a key counts as reaching it`() {
        val (visit, _) = decoder.costParts(observed(), "swiping")
        assertTrue("swiping still pays $visit for a corner inside w", visit < 0.05f)
    }

    @Test
    fun analyse() {
        val path = observed()
        val got = source.candidatesForGesture(path, SwipeFixtures.geometry).suggestions
        println("offered -> " + got.joinToString { "${it.text} %.3f".format(it.confidence) })
        listOf("swiping", "stopping", "song", "sweeping", "swooping", "seeing").forEach {
            val (visit, coverage) = decoder.costParts(path, it)
            println("   %-9s cost=%.3f  visit=%.3f coverage=%.3f".format(
                it, decoder.cost(path, it), visit, coverage))
        }
        // How far each letter of the two contenders sits from the stroke.
        listOf("swiping", "stopping").forEach { word ->
            val gaps = word.toSet().sorted().joinToString(" ") { c ->
                val e = SwipeFixtures.centre(c)
                var best = Float.MAX_VALUE
                for (j in 0 until path.xs.size) {
                    best = minOf(best, hypot(e.centreX - path.xs[j], e.centreY - path.ys[j]))
                }
                "%c=%.2f".format(c, best / SwipeFixtures.keyWidth)
            }
            println("   $word letters (key widths): $gaps")
        }
    }
}
