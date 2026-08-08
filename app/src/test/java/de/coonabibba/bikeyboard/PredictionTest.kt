package de.coonabibba.bikeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/**
 * Next-word prediction, on the real stores (D46).
 *
 * The half of D9 that was missing: what the strip shows when nothing has been
 * typed yet. On the shipped assets rather than a fixture, because the claim
 * being tested is about what two subtitle corpora say and a made-up table would
 * only assert the numbers chosen for it.
 */
class PredictionTest {

    private fun asset(name: String): File = listOf(
        File("src/main/assets/wordlists/$name"),
        File("app/src/main/assets/wordlists/$name"),
    ).firstOrNull { it.exists() } ?: error("missing asset $name")

    private val source: DictionarySuggestions by lazy {
        val lexicons = SwipeFixtures.lexicons
        val store = PersonalStore(File.createTempFile("predict", ".txt").also { it.delete() })
        store.load()
        DictionarySuggestions(
            lexicons = lexicons,
            personal = store,
            bigrams = lexicons.associate { lexicon ->
                val name = if (lexicon.language == Language.GERMAN) "de" else "en"
                lexicon.language to BigramStore.read(
                    ByteBuffer.wrap(asset("$name.bigrams").readBytes()),
                    lexicon.size,
                )
            },
        )
    }

    private fun after(word: String) = source.predict(Preceding.Word(word)).map { it.text }

    @Test
    fun `the store loads against the shipped wordlists`() {
        val lexicons = SwipeFixtures.lexicons
        listOf("de" to Language.GERMAN, "en" to Language.ENGLISH).forEach { (name, language) ->
            val words = lexicons.first { it.language == language }.size
            val store = BigramStore.read(ByteBuffer.wrap(asset("$name.bigrams").readBytes()), words)
            assertTrue("$name did not load", store.size > 100_000)
            assertEquals("$name word count", words, store.wordCount)
            assertTrue("$name threshold", store.threshold >= 2)
        }
    }

    /**
     * **Refuses rather than guesses.** A store whose indices mean something
     * else would predict fluently and wrongly, which is worse than predicting
     * nothing at all.
     */
    @Test
    fun `a store built for another wordlist is rejected`() {
        val bytes = asset("de.bigrams").readBytes()
        val wrong = BigramStore.read(ByteBuffer.wrap(bytes), words = 12_345)
        assertEquals(0, wrong.size)
        assertEquals(BigramStore.NONE, wrong)
    }

    @Test
    fun `rubbish is not mistaken for a store`() {
        assertEquals(BigramStore.NONE, BigramStore.read(ByteBuffer.wrap(ByteArray(0)), 0))
        assertEquals(BigramStore.NONE, BigramStore.read(ByteBuffer.wrap(ByteArray(400)), 0))
    }

    /** Nothing to go on, nothing offered — D21's refusal, inherited. */
    @Test
    fun `an unknown context predicts nothing`() {
        assertEquals(emptyList<Suggestion>(), source.predict(Preceding.Unknown))
    }

    /** A word neither corpus has is no context at all. */
    @Test
    fun `an unknown word predicts nothing`() {
        assertEquals(emptyList<Suggestion>(), source.predict(Preceding.Word("Tyberius")))
    }

    @Test
    fun `the obvious continuations are offered`() {
        assertTrue("vielen -> ${after("vielen")}", after("vielen").first().lowercase() == "dank")
        assertTrue("thank -> ${after("thank")}", after("thank").first() == "you")
        assertTrue("guten -> ${after("guten")}", after("guten").any { it.lowercase() == "tag" })
        assertTrue("how -> ${after("how")}", after("how").isNotEmpty())
    }

    /** Three slots, and no more (D21). */
    @Test
    fun `never more than the strip holds`() {
        listOf("the", "die", "ich", "of", "und", "vielen").forEach {
            assertTrue("$it gave ${after(it).size}", after(it).size <= SuggestionSlots.CAPACITY)
        }
    }

    /**
     * The sentence-start row, which is the slot that has been empty since the
     * strip was built — nothing typed, nothing before it, and until now nothing
     * to say.
     */
    @Test
    fun `a sentence opens with something, capitalised`() {
        val opening = source.predict(Preceding.SentenceStart)
        assertTrue("nothing offered to open a sentence", opening.isNotEmpty())
        opening.forEach {
            assertTrue("${it.text} is not capitalised", it.text.first().isUpperCase())
        }
        assertTrue("expected I or Ich, got ${opening.map { it.text }}",
            opening.any { it.text == "I" || it.text == "Ich" })
    }

    /**
     * **D2's per-word language inference, in the only form the evidence
     * supports.** `die` is 55 times commoner in German than in English, so the
     * German store gets almost the whole vote and the continuations are German
     * — without anything anywhere deciding that the sentence is in German.
     */
    @Test
    fun `the context word decides which language answers`() {
        val german = SwipeFixtures.lexicons.first { it.language == Language.GERMAN }
        val english = SwipeFixtures.lexicons.first { it.language == Language.ENGLISH }
        // The premise, checked rather than assumed.
        assertTrue(
            "die is not much commoner in German",
            german.weightAt(german.indexOf("die")) > 20 * english.weightAt(english.indexOf("die")),
        )
        val followers = source.predict(Preceding.Word("die"))
        assertTrue("nothing follows die", followers.isNotEmpty())
        assertTrue(
            "die was answered in English: ${followers.map { it.text to it.language }}",
            followers.none { it.language == Language.ENGLISH },
        )
    }

    /**
     * And the other way: a word both corpora own lets both answer, which is the
     * bilingual case this project exists for (D1, D2).
     */
    @Test
    fun `a shared context lets both languages answer`() {
        // `in` is near-equally common in both, so neither store should be able
        // to shut the other out.
        val languages = source.predict(Preceding.Word("in")).mapNotNull { it.language }.toSet() +
            source.predict(Preceding.Word("so")).mapNotNull { it.language }.toSet() +
            source.predict(Preceding.Word("was")).mapNotNull { it.language }.toSet()
        assertTrue("only $languages answered", languages.size == 2)
    }

    /** Predictions are probabilities, not shares of a mass that includes junk. */
    @Test
    fun `confidences are probabilities`() {
        listOf("thank", "vielen", "the", "die").forEach { context ->
            val total = source.predict(Preceding.Word(context)).sumOf { it.confidence.toDouble() }
            assertTrue("$context sums to $total", total > 0.0 && total <= 1.0 + 1e-5)
        }
    }

    /**
     * The two halves together, which is the thing that actually ships: what the
     * keyboard remembers typing, feeding what the strip offers next.
     */
    @Test
    fun `typing a word and a space fills the strip`() {
        val word = WordInProgress()
        "vielen".forEach { word.insert(it.toString()) }
        // Mid-word there is nothing behind the cursor to go on, and completion
        // rather than prediction is the strip's job anyway.
        assertEquals(emptyList<Suggestion>(), source.predict(word.preceding))

        word.insert(" ")
        assertEquals("dank", source.predict(word.preceding).first().text.lowercase())

        // And a full stop hands the strip the opening of the next sentence.
        word.insert("dank")
        word.insert(". ")
        assertEquals(Preceding.SentenceStart, word.preceding)
        assertTrue(source.predict(word.preceding).all { it.text.first().isUpperCase() })
    }

    /** Without a store, everything behaves exactly as it did before. */
    @Test
    fun `a source with no stores predicts nothing`() {
        val store = PersonalStore(File.createTempFile("empty", ".txt").also { it.delete() })
        store.load()
        val plain = DictionarySuggestions(SwipeFixtures.lexicons, store)
        assertEquals(emptyList<Suggestion>(), plain.predict(Preceding.SentenceStart))
        assertEquals(emptyList<Suggestion>(), plain.predict(Preceding.Word("thank")))
    }
}
