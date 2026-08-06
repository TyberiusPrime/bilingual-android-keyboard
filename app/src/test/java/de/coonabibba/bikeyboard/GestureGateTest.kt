package de.coonabibba.bikeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

/**
 * That the right word survives long enough to be *scored*.
 *
 * Separate from `GestureSearchTest`, which measures ranking, because these are
 * different failures with different symptoms. A word that is outranked still
 * appears in the strip, one tap away, and the typist barely notices. A word
 * thrown out by one of the cheap prefilters is gone before anything looks at
 * it — not offered, not a runner-up, not recoverable — and from outside that is
 * indistinguishable from the keyboard simply not knowing the word.
 *
 * So each gate is measured against the correct word directly. All four were
 * loosened after this test first ran: at their original settings a hurried
 * stroke had its own word cut off by the cost ceiling.
 */
class GestureGateTest {

    private val decoder = GestureDecoder(SwipeFixtures.geometry)

    private val source: DictionarySuggestions by lazy {
        val store = PersonalStore(File.createTempFile("gesture", ".txt").also { it.delete() })
        store.load()
        DictionarySuggestions(SwipeFixtures.lexicons, store)
    }

    private fun commonest(lexicon: Lexicon, count: Int): List<String> =
        (0 until lexicon.size)
            .sortedByDescending { lexicon.weightAt(it) }
            .asSequence()
            .map { lexicon.wordAt(it) }
            .filter { word -> word.length >= 3 && word.all { it.isLetter() } }
            .filter { Folding.fold(it).toSet().size >= 2 }
            .take(count)
            .toList()

    private fun words(count: Int = 400): List<String> =
        SwipeFixtures.lexicons.flatMap { commonest(it, count) }

    /** The same slice of dictionary the real search would look in. */
    private fun endpointLetters(x: Float, y: Float): Set<Char> {
        val keys = SwipeFixtures.geometry
        val nearest = keys.nearestLetter(x, y) ?: return emptySet()
        val letters = LinkedHashSet<Char>()
        letters += Folding.foldChar(nearest)
        keys.alternatives(x, y, nearest).entries
            .filter { it.value <= GestureDecoder.ENDPOINT_REACH }
            .sortedBy { it.value }
            .forEach { (char, _) ->
                if (letters.size < GestureDecoder.MAX_ENDPOINT_LETTERS) {
                    letters += Folding.foldChar(char)
                }
            }
        return letters
    }

    private class Audit {
        var firstLetter = 0
        var lastLetter = 0
        var length = 0
        var cost = 0
        val costs = mutableListOf<Float>()
        val rejected: Int get() = firstLetter + lastLetter + length + cost
        fun percentile(q: Double): Float =
            costs.sorted().getOrElse((q * costs.size).toInt().coerceAtMost(costs.size - 1)) { 0f }
        override fun toString() =
            "first=$firstLetter last=$lastLetter length=$length cost=$cost"
    }

    private fun audit(label: String, path: (String) -> GesturePath): Audit {
        val audit = Audit()
        words().forEach { word ->
            val stroke = path(word)
            if (Folding.fold(word).first() !in endpointLetters(stroke.startX, stroke.startY)) {
                audit.firstLetter++
                return@forEach
            }
            if (Folding.foldChar(word.last()) !in endpointLetters(stroke.endX, stroke.endY)) {
                audit.lastLetter++
                return@forEach
            }
            val ideal = decoder.idealLength(word)
            if (ideal < stroke.length * GestureDecoder.MIN_LENGTH_RATIO ||
                ideal > stroke.length * GestureDecoder.MAX_LENGTH_RATIO
            ) {
                audit.length++
                return@forEach
            }
            val cost = decoder.cost(stroke, word)
            audit.costs += cost
            if (cost > GestureDecoder.MAX_COST) audit.cost++
        }
        println(
            "$label: rejected $audit | cost p50=%.2f p90=%.2f p99=%.2f max=%.2f".format(
                audit.percentile(0.5),
                audit.percentile(0.9),
                audit.percentile(0.99),
                audit.costs.maxOrNull() ?: 0f,
            ),
        )
        return audit
    }

    @Test
    fun `an ordinary stroke is never filtered out before it is scored`() {
        assertEquals("realistic", 0, audit("realistic  ") { SwipeFixtures.swipe(it) }.rejected)
        assertEquals(
            "sloppy",
            0,
            audit("sloppy     ") { SwipeFixtures.swipe(it, rounding = 0.8f, jitter = 0.3f) }.rejected,
        )
    }

    /**
     * The two ways a real stroke goes further wrong than a tidy one: sampled at
     * frame rate rather than touch rate, and made in a hurry. Neither may cost
     * the word outright.
     */
    @Test
    fun `a hurried stroke is not filtered out either`() {
        assertEquals(0, audit("coarse     ") { SwipeFixtures.swipe(it, spacing = 140f) }.rejected)
        val hurried = audit("hurried    ") {
            SwipeFixtures.swipe(
                it,
                rounding = 1.2f,
                jitter = 0.35f,
                endpointSlop = 0.3f,
                spacing = 160f,
            )
        }
        assertEquals(0, hurried.rejected)
        // And with room to spare, so the next slightly worse stroke is not the
        // one that falls off the edge.
        assertTrue(
            "hurried strokes reach %.2f of a %.2f ceiling".format(
                hurried.percentile(0.99), GestureDecoder.MAX_COST,
            ),
            hurried.percentile(0.99) < GestureDecoder.MAX_COST * 0.8f,
        )
    }

    /**
     * Both languages, separately.
     *
     * German carries the ambiguities that swiping cannot resolve — a sixth of
     * its commonest words have a doubled letter and a twelfth an accent, and
     * both are invisible to a finger — so if the feature were going to be
     * lopsided this is where it would show. It is not: the frequency weighting
     * picks up what the geometry cannot say.
     */
    @Test
    fun `neither language is markedly worse than the other`() {
        val rates = SwipeFixtures.lexicons.map { lexicon ->
            val words = commonest(lexicon, 400)
            val hits = words.count { word ->
                source.candidatesForGesture(SwipeFixtures.swipe(word), SwipeFixtures.geometry)
                    .suggestions.firstOrNull()?.text.equals(word, ignoreCase = true)
            }
            val rate = hits.toDouble() / words.size
            println("${lexicon.language}: top-1 ${(rate * 100).roundToInt()}%")
            rate
        }
        rates.forEach { assertTrue("only ${(it * 100).roundToInt()}% top-1", it > 0.93) }
        assertTrue("the two languages differ by more than five points",
            kotlin.math.abs(rates[0] - rates[1]) < 0.05)
    }
}
