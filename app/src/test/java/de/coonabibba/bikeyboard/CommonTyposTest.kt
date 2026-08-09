package de.coonabibba.bikeyboard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The classic slips — transpositions and doubled letters — against the real
 * wordlists.
 *
 * These are the hardest class for a keyboard that judges spelling alone,
 * because a transposition of a real word usually *is* word-shaped: that is why
 * fingers make it. So this is where [WordShape] has least to offer and where
 * what remains uncorrected marks the boundary of what D10/D12 will have to
 * pick up.
 */
class CommonTyposTest {
    private val shape: WordShape by lazy { WordShape.of(SwipeFixtures.lexicons) }
    private val source: DictionarySuggestions by lazy {
        val store = PersonalStore(File.createTempFile("typo", ".txt").also { it.delete() })
        store.load()
        DictionarySuggestions(SwipeFixtures.lexicons, store)
    }
    private val keys = SwipeFixtures.geometry
    private val threshold = KeyboardPrefs.DEFAULT_AUTO_CORRECT_CONFIDENCE / 100f

    private fun typed(word: String) = word.map { ch ->
        val e = keys.centreOf(ch) ?: return@map TypedTouch.untouched(ch)
        TypedTouch(ch, keys.alternatives(e.centreX, e.centreY, ch))
    }

    private val slips = listOf(
        "teh" to "the", "hte" to "the", "thier" to "their", "taht" to "that",
        "waht" to "what", "adn" to "and", "tihs" to "this", "owuld" to "would",
        "thne" to "then", "liek" to "like", "jsut" to "just", "knwo" to "know",
        "tahn" to "than", "wnat" to "want", "gaurd" to "guard", "freind" to "friend",
        "beleive" to "believe", "acheive" to "achieve", "wierd" to "weird",
        "throught" to "thought", "untill" to "until", "occured" to "occurred",
        "udn" to "und", "dei" to "die", "nciht" to "nicht", "jetz" to "jetzt",
        "villeicht" to "vielleicht", "eintragn" to "eintragen", "gesatgt" to "gesagt",
        "wiel" to "weil", "auhc" to "auch", "mti" to "mit",
    )

    @Test
    fun report() {
        println("  %-12s %-12s %-12s %6s %8s".format("typed", "wanted", "got", "conf", "p"))
        val fixed = slips.count { (typo, wanted) ->
            val fix = source.candidatesFor(typo, typed(typo)).correction
            val right = fix?.text == wanted && fix.confidence >= threshold
            println(
                "  %-12s %-12s %-12s %6.2f %8.1g  %s".format(
                    typo, wanted, fix?.text ?: "-", fix?.confidence ?: 0f,
                    shape.plausibility(typo), if (right) "" else "missed",
                ),
            )
            right
        }
        println("  $fixed of ${slips.size} corrected")

        // Two thirds, where the trigram prior (D43) left it and D45 added one.
        // What remains is the class the prior cannot see — a transposition of a
        // real word is word-shaped by construction, and in the union of two
        // languages it is usually word-shaped in the *other* one: `freind` is
        // built from `rei`, `ein` and `ind`, all common German. Those need
        // context (D10/D12).
        assertTrue("only $fixed of ${slips.size} corrected", fixed >= 20)
    }

    /**
     * The wrong ones must at least stay under the threshold. `wierd` reaches
     * German `wird` and `thne` reaches `the`; neither may fire.
     */
    @Test
    fun `a wrong guess is never confident`() {
        slips.forEach { (typo, wanted) ->
            val fix = source.candidatesFor(typo, typed(typo)).correction ?: return@forEach
            if (fix.text == wanted) return@forEach
            assertTrue(
                "$typo became ${fix.text} at ${fix.confidence}, wanting $wanted",
                fix.confidence < threshold,
            )
        }
    }
}
