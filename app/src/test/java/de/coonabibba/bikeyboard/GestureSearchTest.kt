package de.coonabibba.bikeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt
import kotlin.system.measureNanoTime

/**
 * How well a swipe finds its word, measured against the wordlists that ship.
 *
 * This is the test that decides whether the feature is worth having, and it
 * runs headlessly on seventy thousand real words rather than on a handful of
 * hand-picked ones. The decoder is geometry and the geometry is simple; what
 * makes swipe typing work or not is whether the frequency weighting behind it
 * picks the right word out of the several that any given shape fits, and that
 * cannot be judged from a fixture.
 */
class GestureSearchTest {

    private val source: DictionarySuggestions by lazy {
        val store = PersonalStore(File.createTempFile("gesture", ".txt").also { it.delete() })
        store.load()
        DictionarySuggestions(SwipeFixtures.lexicons, store)
    }

    private fun decode(path: GesturePath): List<String> =
        source.candidatesForGesture(path, SwipeFixtures.geometry).suggestions.map { it.text }

    /** The commonest words of each language: what a swipe is mostly used for. */
    private fun commonWords(count: Int): List<String> =
        SwipeFixtures.lexicons.flatMap { lexicon ->
            (0 until lexicon.size)
                .sortedByDescending { lexicon.weightAt(it) }
                .asSequence()
                .map { lexicon.wordAt(it) }
                .filter { word -> word.length >= 3 && word.all { it.isLetter() } }
                // A word whose letters are all one key cannot be swiped by
                // anyone, and asking the decoder for it measures nothing.
                .filter { Folding.fold(it).toSet().size >= 2 }
                .take(count)
                .toList()
        }

    private fun report(label: String, words: List<String>, path: (String) -> GesturePath): Double {
        var top1 = 0
        var top3 = 0
        words.forEach { word ->
            val got = decode(path(word))
            if (got.firstOrNull().equals(word, ignoreCase = true)) top1++
            if (got.any { it.equals(word, ignoreCase = true) }) top3++
        }
        val rate = top1.toDouble() / words.size
        println(
            "$label: top-1 ${(rate * 100).roundToInt()}%  " +
                "top-3 ${(top3 * 100.0 / words.size).roundToInt()}%  (${words.size} words)",
        )
        return rate
    }

    @Test
    fun `a perfect trace finds its word`() {
        val words = commonWords(400)
        val rate = report("perfect", words) { SwipeFixtures.perfectSwipe(it) }
        assertTrue("only ${(rate * 100).roundToInt()}% top-1 on perfect traces", rate > 0.94)
    }

    @Test
    fun `a realistic trace finds its word`() {
        val words = commonWords(400)
        val rate = report("realistic", words) { SwipeFixtures.swipe(it) }
        assertTrue("only ${(rate * 100).roundToInt()}% top-1 on realistic traces", rate > 0.94)
    }

    /**
     * The word is always in the strip, even when it is not first.
     *
     * This is the number that actually matters, and it is the one the whole
     * design leans on: a swipe commits its best guess, and the three slots
     * carry the runners-up, so being wrong costs one tap rather than a
     * retype. Every miss measured so far has been at rank two.
     */
    @Test
    fun `the word is always somewhere in the strip`() {
        val words = commonWords(400)
        val missing = words.filter { word ->
            decode(SwipeFixtures.swipe(word)).none { it.equals(word, ignoreCase = true) }
        }
        assertTrue("not offered at all: $missing", missing.isEmpty())
    }

    /**
     * The two ambiguities no amount of geometry can resolve, recorded because
     * they are permanent and because their shape decides what the strip is for.
     *
     * A doubled letter is *one place on the keyboard* — the finger does not
     * move for the second `s` of `dass` — so `das` and `dass` are traced along
     * literally the same path. Folding does the same to `wurde` and `würde`,
     * since neither umlaut nor accent can be swiped at all (D5 puts them on a
     * long-press). In both cases nothing distinguishes the candidates but how
     * common they are, the commoner one wins, and the other must be one tap
     * away or the feature is a trap.
     */
    @Test
    fun `a doubled letter and an umlaut are the same stroke as their plain form`() {
        val decoder = GestureDecoder(SwipeFixtures.geometry)
        listOf("das" to "dass", "to" to "too", "wurde" to "würde", "schon" to "schön")
            .forEach { (plain, doubled) ->
                val path = SwipeFixtures.perfectSwipe(plain)
                assertEquals(
                    "$plain and $doubled should trace identically",
                    decoder.cost(path, plain),
                    decoder.cost(path, doubled),
                    1e-4f,
                )
                val offered = decode(SwipeFixtures.swipe(doubled))
                assertTrue("$doubled is not offered at all: $offered", doubled in offered)
            }
    }

    @Test
    fun `a sloppy trace still mostly finds its word`() {
        val words = commonWords(400)
        val rate = report("sloppy", words) {
            SwipeFixtures.swipe(it, rounding = 0.8f, jitter = 0.3f)
        }
        assertTrue("only ${(rate * 100).roundToInt()}% top-1 on sloppy traces", rate > 0.88)
    }

    /**
     * The one kind of sloppiness that costs real accuracy, and the reason it
     * gets its own test: everything between the endpoints is forgiven by the
     * shape comparison, but the endpoints *bound the search*. A finger that
     * comes down a third of a key off still finds its word; one that comes down
     * most of a key off is asking about a different first letter, and no amount
     * of good geometry in the middle can recover it.
     */
    @Test
    fun `imprecise endpoints are where accuracy actually goes`() {
        val words = commonWords(400)
        listOf(0.15f, 0.3f, 0.5f).forEach { slop ->
            report("slop $slop", words) {
                SwipeFixtures.swipe(it, endpointSlop = slop)
            }
        }
        val rate = report("slop 0.3", words) { SwipeFixtures.swipe(it, endpointSlop = 0.3f) }
        assertTrue("only ${(rate * 100).roundToInt()}% top-1 at a third of a key", rate > 0.80)
    }

    /**
     * A swipe happens once per word, not once per keystroke, so it has a whole
     * word's worth of typing to hide in — but it also lands at the moment the
     * finger lifts, which is the moment the result is stared at.
     */
    @Test
    fun `decoding is fast enough to be invisible`() {
        val words = commonWords(60)
        val paths = words.map { SwipeFixtures.swipe(it) }
        repeat(3) { paths.forEach { decode(it) } }

        val nanos = measureNanoTime { paths.forEach { decode(it) } } / paths.size
        println("decode: ${nanos / 1000}µs per swipe")
        assertTrue("a swipe takes ${nanos / 1_000_000}ms to decode", nanos < 30_000_000)
    }
}
