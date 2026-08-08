package de.coonabibba.bikeyboard

import org.junit.Test
import java.io.File

class LearningStrokeTest {

    private val source: DictionarySuggestions by lazy {
        val store = PersonalStore(File.createTempFile("gesture", ".txt").also { it.delete() })
        store.load()
        DictionarySuggestions(SwipeFixtures.lexicons, store)
    }
    private val decoder = GestureDecoder(SwipeFixtures.geometry)

    private fun report(label: String, path: GesturePath) {
        val got = source.candidatesForGesture(path, SwipeFixtures.geometry).suggestions
        println(
            "$label -> " + got.joinToString { "${it.text} %.3f".format(it.confidence) } +
                "  | costs learning=%.3f laughing=%.3f leaving=%.3f".format(
                    decoder.cost(path, "learning"),
                    decoder.cost(path, "laughing"),
                    decoder.cost(path, "leaving"),
                ),
        )
    }

    /**
     * The stroke from the phone: `learning`, decoded as `laughing`.
     *
     * `laughing` is 98,420 against `learning`'s 17,252 — nearly six times as
     * common — so the geometry has to do real work to overcome that, and it
     * did not. The route for `laughing` never approaches `e` or `r`, and the
     * stroke plainly went to both; nothing in the cost charged it for leaving
     * that excursion unexplained.
     */
    @Test
    fun learning() {
        report("perfect  ", SwipeFixtures.perfectSwipe("learning"))
        report("realistic", SwipeFixtures.swipe("learning"))
        report("sloppy   ", SwipeFixtures.swipe("learning", rounding = 0.8f, jitter = 0.3f))
        report("coarse   ", SwipeFixtures.swipe("learning", spacing = 140f))
        report(
            "hurried  ",
            SwipeFixtures.swipe("learning", rounding = 1.2f, jitter = 0.35f, spacing = 160f),
        )
        listOf(0.6f, 1.0f, 1.5f, 2.0f).forEach { o ->
            report("loop %.1f ".format(o), SwipeFixtures.swipe("learning", overshoot = o))
        }
    }
}
