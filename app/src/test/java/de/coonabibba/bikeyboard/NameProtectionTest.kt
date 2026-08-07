package de.coonabibba.bikeyboard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.exp
import kotlin.math.max

/**
 * What the trigram prior (D43) buys in typos fixed, and costs in names
 * corrected away.
 *
 * The gain is easy to see — `hsnging` becomes `hanging`. The bill is the one D8
 * cares about: a name the keyboard used to leave alone, now rewritten because
 * the model thinks it is not word-shaped. This measures both against the same
 * dial, [WordShape]'s floor, so the setting is chosen on evidence.
 */
class NameProtectionTest {
    private val source: DictionarySuggestions by lazy {
        val store = PersonalStore(File.createTempFile("name", ".txt").also { it.delete() })
        store.load()
        DictionarySuggestions(SwipeFixtures.lexicons, store)
    }
    /** The model as the keyboard uses it, floor and all, to recover what a correction scored. */
    private val shape: WordShape by lazy { WordShape.of(SwipeFixtures.lexicons) }

    /**
     * The same model with the floor lifted out of the way, so the sweep can ask
     * what a *different* floor would have done. Reading plausibility through
     * the floor being swept would only ever report the floor back.
     */
    private val unfloored: WordShape by lazy {
        WordShape.of(SwipeFixtures.lexicons, maxSwing = 40f)
    }
    private val keys = SwipeFixtures.geometry
    private val threshold = KeyboardPrefs.DEFAULT_AUTO_CORRECT_CONFIDENCE / 100f

    /** DictionarySuggestions.UNKNOWN_WORD_PRIOR, which is private to it. */
    private val prior = 1e-6f

    private fun typed(word: String): List<TypedTouch> = word.map { ch ->
        val e = keys.centreOf(ch) ?: return@map TypedTouch.untouched(ch)
        TypedTouch(ch, keys.alternatives(e.centreX, e.centreY, ch))
    }

    private val names = listOf(
        // The user, the phone, the OS.
        "Coonabibba", "Fairphone", "eelo", "Murena", "Tyberius",
        // German given names and surnames.
        "Anja", "Sven", "Bjoern", "Kerstin", "Detlef", "Uwe", "Jost", "Thorsten",
        "Wiebke", "Hartmut", "Ingeborg", "Schmitz", "Kranzberg", "Oelschlaeger",
        "Duerrenmatt", "Zwickau", "Reutlingen", "Gelsenkirchen",
        // English and beyond.
        "Fiona", "Ffion", "Nkechi", "Zsofia", "Wrzesniewski", "Siobhan", "Aoife",
        "Ngozi", "Xiomara", "Rhys", "Bjork", "Nguyen", "Oyelaran",
        // Jargon and brands, which get typed as much as names do.
        "systemd", "Nginx", "Kubernetes", "Xiaomi", "Signal", "Wireguard", "Syncthing",
        "grep", "sudo", "btrfs", "zsh", "kotlin", "gradle", "logcat", "adb",
        "Mastodon", "Matrix", "Threema", "Nextcloud", "Jellyfin", "Wikidata",
    )

    /** Real slips, in both languages. German nouns are capitalised, as German is. */
    private val typos = listOf(
        "Schmeterling", "Ueberrschung", "Entwiclung", "Verstaendniss", "Wohnzimer",
        "Krankenhais", "Freundschsft", "Gescichte", "Bahnhoff", "Kindergsrten",
        "Wissenschsft", "Gebauede", "Regirung", "Gesselschaft", "Umgebumg",
        "hsnging", "zomorrow", "somethimg", "aboyt", "peoole", "becausr", "throught",
        "wprd", "diffrent", "gouvernment", "recieve", "seperate", "definately",
    )

    /** What a correction scored, recovered from the confidence it was given. */
    private class Case(val word: String, val text: String, val score: Float, val plausibility: Float)

    private fun cases(words: List<String>): List<Case> = words.mapNotNull { word ->
        val fix = source.candidatesFor(word, typed(word)).correction ?: return@mapNotNull null
        // Divide out the prior the source actually applied, which leaves what
        // the correction scored on its own — the one part of this that no
        // choice of floor changes.
        val score = fix.confidence * prior * shape.plausibility(word) /
            (1f - fix.confidence).coerceAtLeast(1e-9f)
        Case(word, fix.text, score, unfloored.plausibility(word))
    }

    /** Confidence this correction would get if the floor were [swing] nats. */
    private fun Case.at(swing: Float): Float {
        val floored = max(plausibility, exp(-swing))
        return score / (score + prior * floored)
    }

    @Test
    fun report() {
        val swings = listOf(12f, 9f, 7f, 6f, 5f, 4f)
        val names = cases(names)
        val typos = cases(typos)

        println("  corrections crossing the ${threshold * 100}% threshold, by floor (nats)")
        println("      %-8s %-8s %s".format("floor", "typos", "names"))
        swings.forEach { swing ->
            println(
                "      %-8.0f %-8d %d".format(
                    swing,
                    typos.count { it.at(swing) >= threshold },
                    names.count { it.at(swing) >= threshold },
                ),
            )
        }

        listOf("names, which should stay put" to names, "typos, which should be fixed" to typos)
            .forEach { (title, group) ->
                println("  $title")
                group.sortedBy { it.word }.forEach { case ->
                    println(
                        "    %-14s -> %-14s %s  p=%.1g".format(
                            case.word,
                            case.text,
                            swings.joinToString("") { "%7.2f".format(case.at(it)) },
                            case.plausibility,
                        ),
                    )
                }
            }

        // The trade the floor was chosen for. Not a tight fit — the point is
        // that if a change to the cost model or the wordlists moves this, it
        // says so here rather than on the phone.
        val chosen = 7f
        assertTrue(
            "typos fixed: ${typos.count { it.at(chosen) >= threshold }}",
            typos.count { it.at(chosen) >= threshold } >= 14,
        )
        assertTrue(
            "names corrected away: ${names.filter { it.at(chosen) >= threshold }.map { it.word }}",
            names.count { it.at(chosen) >= threshold } <= 2,
        )
    }
}
