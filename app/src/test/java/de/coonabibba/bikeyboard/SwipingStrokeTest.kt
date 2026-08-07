package de.coonabibba.bikeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `swiping`, which cannot be swiped — and why that is arithmetic rather than a
 * bug (D39b).
 */
class SwipingStrokeTest {

    private val store = PersonalStore(File.createTempFile("gesture", ".txt").also { it.delete() })
    private val source: DictionarySuggestions by lazy {
        store.load()
        DictionarySuggestions(SwipeFixtures.lexicons, store)
    }
    private val decoder = GestureDecoder(SwipeFixtures.geometry)

    private fun offered(path: GesturePath) =
        source.candidatesForGesture(path, SwipeFixtures.geometry).suggestions.map { it.text }

    /**
     * The two words are **the same stroke**, exactly, and no amount of geometry
     * will ever separate them.
     *
     * `swiping` is `s w i p i n g` and `sweeping` is `s w e p i n g`. All of
     * `w`, `e`, `i` and `p` sit on the top row at the same height, so `w→i→p`
     * and `w→e→p` are the same straight line, and the tails `p→i→n→g` are
     * identical. Same start, same corners, same end, same length. Weighting
     * corners harder cannot help, because the corners agree.
     */
    @Test
    fun `swiping and sweeping trace one identical route`() {
        listOf(
            SwipeFixtures.perfectSwipe("swiping"),
            SwipeFixtures.swipe("swiping"),
            SwipeFixtures.perfectSwipe("sweeping"),
        ).forEach { stroke ->
            val swiping = decoder.cost(stroke, "swiping")
            val sweeping = decoder.cost(stroke, "sweeping")
            // Not bit-identical: the alignment settles the letters on slightly
            // different segments of the same line. Half a percent apart, where
            // a word that genuinely goes elsewhere is several times the cost.
            assertEquals("the two routes differ", swiping, sweeping, swiping * 0.02f)
            assertTrue(decoder.cost(stroke, "song") > swiping * 2f)
        }
    }

    /**
     * So frequency decides, and `sweeping` is ten times the commoner. The right
     * answer is second, in the strip, one tap away — which is the same bargain
     * `das` and `dass` strike.
     */
    @Test
    fun `the loser of that tie is still offered`() {
        val got = offered(SwipeFixtures.swipe("swiping"))
        assertTrue("swiping is not offered at all: $got", "swiping" in got)
    }

    /**
     * And teaching it settles the matter for good, which is precisely what D8
     * built the personal store for. A word somebody asked to be remembered
     * outranks the corpus by a wide margin, so a tie the corpus loses is a tie
     * the store wins.
     */
    @Test
    fun `teaching the word wins the tie permanently`() {
        // Touch the source first: it loads the store, and loading after the add
        // would read the empty file straight back over it.
        offered(SwipeFixtures.swipe("swiping"))
        store.add("swiping")
        val got = offered(SwipeFixtures.swipe("swiping"))
        assertEquals("swiping", got.firstOrNull())
    }
}
