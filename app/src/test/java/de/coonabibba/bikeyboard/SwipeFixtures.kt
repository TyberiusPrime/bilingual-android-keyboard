package de.coonabibba.bikeyboard

import java.io.File
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * A keyboard's worth of geometry, and fingers to drag across it.
 *
 * The geometry mirrors `KeyboardView.placeKeys` rather than calling it, because
 * that lives on an Android `View` and these tests run on the JVM. The
 * duplication is deliberate and narrow — proportional widths within a row, one
 * gap between keys — and if the two drift the effect is that these accuracy
 * numbers describe a keyboard slightly unlike the shipped one, not that
 * anything breaks.
 */
object SwipeFixtures {

    /**
     * The phone this is built for: 1080 wide at density 3, and the keyboard's
     * own `rowHeight = 52dp` and `keyGap = 3dp` from `KeyboardView`.
     *
     * The proportions are the point. Letter keys come out about 98px wide and
     * 147px tall, so the grid is markedly taller than it is wide, and every
     * cost in the decoder is quoted in *key widths* — which means a vertical
     * error counts for half again what the same error costs horizontally. Made
     * up numbers here would have quietly described a keyboard with squarer keys
     * and flattered every measurement taken on it.
     */
    private const val DENSITY = 3f
    const val WIDTH = 1080f
    const val GAP = 3f * DENSITY
    const val ROW_HEIGHT = 52f * DENSITY
    const val HEIGHT = ROW_HEIGHT * 4 + GAP * 2

    val geometry: KeyGeometry by lazy { build(Layouts.letters) }

    /** The width one letter key gets, which is the unit every cost is quoted in. */
    val keyWidth: Float get() = geometry.keyWidth

    private fun build(layout: KeyboardLayout): KeyGeometry {
        val entries = mutableListOf<KeyGeometry.Entry>()
        var letterWidth = 0f
        var letterHeight = 0f
        val perRow = (HEIGHT - GAP * 2) / layout.rows.size

        layout.rows.forEachIndexed { rowIndex, row ->
            val totalWeight = row.sumOf { it.widthWeight.toDouble() }.toFloat()
            val usableWidth = WIDTH - GAP * (row.size + 1)
            var x = GAP
            val y = GAP + rowIndex * perRow
            row.forEach { key ->
                val width = usableWidth * (key.widthWeight / totalWeight)
                val text = (key.action as? KeyAction.Text)?.text
                if (text != null && text.length == 1) {
                    if (letterWidth == 0f) letterWidth = width
                    if (letterHeight == 0f) letterHeight = perRow - GAP
                    entries += KeyGeometry.Entry(
                        text[0].lowercaseChar(),
                        x + width / 2f,
                        y + (perRow - GAP) / 2f,
                    )
                }
                x += width + GAP
            }
        }
        return KeyGeometry(entries, letterWidth, letterHeight)
    }

    /** Where [char] sits, for building a swipe by hand. */
    fun centre(char: Char): KeyGeometry.Entry =
        geometry.centreOf(char) ?: error("no key for $char")

    /**
     * A finger tracing [word] perfectly: straight to every key centre, no
     * rounding, no overshoot. The easiest possible input, and the floor any
     * decoder has to clear before the realistic ones are worth running.
     */
    fun perfectSwipe(word: String): GesturePath =
        swipe(word, rounding = 0f, jitter = 0f, overshoot = 0f)

    /**
     * A finger tracing [word] the way a thumb actually does it.
     *
     * Two things are modelled, because they are the two that show up when you
     * watch someone swipe. **Corners get rounded**: nobody stops dead on a key
     * and sets off again at an angle. And the whole path **wanders**, by a
     * fraction of a key, because a thumb travelling at speed is not steered
     * precisely.
     *
     * The rounding is a *fillet* — the corner is cut locally, within [rounding]
     * key widths of the vertex, and the rest of each leg is left alone. The
     * first attempt pulled each vertex toward the midpoint of its neighbours
     * instead, which is not corner cutting but corner *deletion*: on a word
     * that doubles back, like `haben`, it removed half the total travel and the
     * finger no longer went anywhere near `b`. A thumb that never approaches a
     * letter is not a sloppy swipe of that word, it is a swipe of a different
     * word, and no decoder should be asked to find it.
     *
     * [jitter] is the wander, in key widths. [endpointSlop] displaces where the
     * finger comes down and lifts, also in key widths — the one kind of
     * sloppiness the search cannot shrug off, since the first and last letters
     * are what bound it.
     */
    fun swipe(
        word: String,
        rounding: Float = 0.4f,
        jitter: Float = 0.12f,
        /**
         * How far past a key the finger is carried before it can turn, in key
         * widths, at a full reversal. Scaled by how sharp each turn actually
         * is, so a gentle bend is barely affected.
         */
        overshoot: Float = 0.35f,
        endpointSlop: Float = 0f,
        /**
         * Pixels between reported touch points. The default is roughly what a
         * digitiser gives; larger values model losing the batched samples and
         * seeing the stroke once per frame instead.
         */
        spacing: Float = SAMPLE_SPACING,
        random: Random = Random(word.hashCode()),
    ): GesturePath {
        val xs = mutableListOf<Float>()
        val ys = mutableListOf<Float>()
        word.forEach { char ->
            val entry = geometry.centreOf(char) ?: return@forEach
            if (xs.isNotEmpty() && xs.last() == entry.centreX && ys.last() == entry.centreY) return@forEach
            xs += entry.centreX
            ys += entry.centreY
        }
        require(xs.size >= 2) { "$word cannot be swiped" }

        if (endpointSlop > 0f) {
            val slop = endpointSlop * keyWidth
            listOf(0, xs.size - 1).forEach { i ->
                xs[i] += (random.nextFloat() - 0.5f) * 2f * slop
                ys[i] += (random.nextFloat() - 0.5f) * 2f * slop
            }
        }

        // Fillet every interior corner: leave each leg early, arrive at the next
        // one late, and sweep between the two through the vertex. The endpoints
        // stay exactly put — a swipe's first and last letters are the part
        // people are deliberate about, and blurring them would flatter the
        // decoder about the very assumption its search rests on.
        val radius = rounding * keyWidth
        val cutX = mutableListOf(xs[0])
        val cutY = mutableListOf(ys[0])
        for (i in 1 until xs.size - 1) {
            val (px, py) = towards(xs[i], ys[i], xs[i - 1], ys[i - 1], radius)
            val (qx, qy) = towards(xs[i], ys[i], xs[i + 1], ys[i + 1], radius)

            // Momentum carries the finger *past* a key before it can turn, and
            // the sharper the turn the further past. A stroke that only ever
            // cut corners was the model's second big lie: it made every path
            // shorter than the ideal, when a real one full of reversals — the
            // `u`-`g`-`h` of `laughing`, say — comes out longer, with visible
            // loops where the finger swung wide and came back.
            val (cx, cy) = overshootAt(xs, ys, i, overshoot * keyWidth)

            cutX += px
            cutY += py
            // A quadratic through the control point, which is the vertex itself
            // when there is no overshoot and beyond it when there is.
            for (step in 1 until ARC_STEPS) {
                val t = step.toFloat() / ARC_STEPS
                val u = 1f - t
                cutX += u * u * px + 2f * u * t * cx + t * t * qx
                cutY += u * u * py + 2f * u * t * cy + t * t * qy
            }
            cutX += qx
            cutY += qy
        }
        cutX += xs.last()
        cutY += ys.last()

        // Walk the polyline at something like a digitiser's sampling rate, and
        // let the finger drift as it goes.
        //
        // The drift is **smooth**. Independent noise per sample was the first
        // thing tried and it is not a finger, it is a sawtooth: at six pixels
        // between reports and a tenth of a key of amplitude, it inflated the
        // arc length of `the` by half as much again, which then wrecked the
        // resampling — every sample sat somewhere quite different along the
        // stroke than it should have. A thumb wanders over the span of a whole
        // gesture, so the wander here is two slow waves with random phase,
        // tapered to nothing at both ends because the endpoints are the part
        // people are deliberate about.
        val wander = jitter * keyWidth
        val phase = FloatArray(4) { random.nextFloat() * TAU }
        fun drift(t: Float, channel: Int): Float {
            val taper = sin(PI.toFloat() * t)
            val slow = sin(TAU * SLOW_WAVES * t + phase[channel * 2])
            val fast = sin(TAU * FAST_WAVES * t + phase[channel * 2 + 1])
            return wander * taper * (slow * 0.7f + fast * 0.3f)
        }

        var travelled = 0f
        val total = (1 until cutX.size).sumOf {
            hypot(cutX[it] - cutX[it - 1], cutY[it] - cutY[it - 1]).toDouble()
        }.toFloat()

        val outX = mutableListOf<Float>()
        val outY = mutableListOf<Float>()
        for (i in 1 until cutX.size) {
            val dx = cutX[i] - cutX[i - 1]
            val dy = cutY[i] - cutY[i - 1]
            val leg = hypot(dx, dy)
            val steps = (leg / SAMPLE_SPACING).toInt().coerceAtLeast(1)
            for (step in 0 until steps) {
                val along = step.toFloat() / steps
                val t = (travelled + leg * along) / total
                outX += cutX[i - 1] + dx * along + drift(t, 0)
                outY += cutY[i - 1] + dy * along + drift(t, 1)
            }
            travelled += leg
        }
        outX += cutX.last()
        outY += cutY.last()

        val (reportedX, reportedY) = report(outX, outY, spacing)
        return GesturePath.of(reportedX.toFloatArray(), reportedY.toFloatArray())
            ?: error("$word produced no path")
    }

    /**
     * Thins a finger's true path down to what the keyboard is actually told
     * about it.
     *
     * The path a thumb draws is continuous; what arrives is a sample of it. The
     * thinning has to happen *after* the path is built, never by building a
     * coarser one — the corners are polyline vertices, so a coarse construction
     * would keep every one of them and model nothing at all.
     *
     * The last point always survives: it is where the finger lifted, and that
     * is one of the two letters the whole search is bounded by.
     */
    private fun report(
        xs: List<Float>,
        ys: List<Float>,
        spacing: Float,
    ): Pair<List<Float>, List<Float>> {
        if (spacing <= SAMPLE_SPACING) return xs to ys
        val keptX = mutableListOf(xs.first())
        val keptY = mutableListOf(ys.first())
        var carried = 0f
        for (i in 1 until xs.size) {
            carried += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
            if (carried >= spacing) {
                keptX += xs[i]
                keptY += ys[i]
                carried = 0f
            }
        }
        if (keptX.last() != xs.last() || keptY.last() != ys.last()) {
            keptX += xs.last()
            keptY += ys.last()
        }
        return keptX to keptY
    }

    /**
     * Where the finger actually gets to at vertex [i] before momentum lets it
     * turn — the control point of the corner's curve.
     *
     * Sharpness is the turn angle mapped to 0 for dead straight and 1 for a
     * full reversal, so a stroke bulges past the keys it has to double back
     * from and passes cleanly through the ones it merely bends around.
     */
    private fun overshootAt(
        xs: List<Float>,
        ys: List<Float>,
        i: Int,
        distance: Float,
    ): Pair<Float, Float> {
        if (distance <= 0f) return xs[i] to ys[i]
        val inX = xs[i] - xs[i - 1]
        val inY = ys[i] - ys[i - 1]
        val outX = xs[i + 1] - xs[i]
        val outY = ys[i + 1] - ys[i]
        val inLen = hypot(inX, inY)
        val outLen = hypot(outX, outY)
        if (inLen <= 0f || outLen <= 0f) return xs[i] to ys[i]

        val cos = (inX * outX + inY * outY) / (inLen * outLen)
        val sharpness = ((1f - cos) / 2f).coerceIn(0f, 1f)
        val carry = distance * sharpness
        return xs[i] + inX / inLen * carry to ys[i] + inY / inLen * carry
    }

    /**
     * A point [distance] from ([fromX], [fromY]) in the direction of
     * ([toX], [toY]), never past the halfway mark — two tight corners in a row
     * must not overlap and turn the path inside out.
     */
    private fun towards(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        distance: Float,
    ): Pair<Float, Float> {
        val leg = hypot(toX - fromX, toY - fromY)
        if (leg <= 0f) return fromX to fromY
        val t = (distance / leg).coerceAtMost(0.5f)
        return fromX + (toX - fromX) * t to fromY + (toY - fromY) * t
    }

    /** The shipped wordlists, loaded once for the whole test run. */
    val lexicons: List<Lexicon> by lazy {
        listOf(Language.GERMAN to "de.txt", Language.ENGLISH to "en.txt").map { (language, name) ->
            val words = mutableListOf<String>()
            val counts = mutableListOf<Long>()
            asset(name).forEachLine { line ->
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val tab = line.indexOf('\t')
                if (tab <= 0) return@forEachLine
                words += line.substring(0, tab)
                counts += line.substring(tab + 1).toLong()
            }
            Lexicon(language, words, counts.toLongArray())
        }
    }

    private fun asset(name: String): File =
        listOf(
            File("src/main/assets/wordlists/$name"),
            File("app/src/main/assets/wordlists/$name"),
        ).firstOrNull { it.exists() } ?: error("wordlist $name not found")

    /** Roughly one touch report every few pixels, as a digitiser gives. */
    private const val SAMPLE_SPACING = 6f

    private const val TAU = (2.0 * PI).toFloat()

    /** Segments per rounded corner. Enough to be a curve, not a chamfer. */
    private const val ARC_STEPS = 6

    /** How many times the thumb wanders across the whole stroke. */
    private const val SLOW_WAVES = 1.3f
    private const val FAST_WAVES = 3.7f
}
