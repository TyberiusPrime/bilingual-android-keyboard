package de.coonabibba.bikeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Checks the bigram stores that ship (D46, stage 6a).
 *
 * They are counted by `scripts/build-bigrams.py` in Python and will be read by
 * the app in Kotlin, and the two agree about a binary layout rather than about
 * a text format — so there is more that can silently drift here than with the
 * wordlists, not less.
 *
 * **The binding to the wordlist is the important one.** A store's keys are line
 * indices into `de.txt` or `en.txt`; rebuild a wordlist without rebuilding its
 * store and every prediction becomes a different word, quietly and plausibly.
 * The header carries the wordlist's entry count and SHA-256 for exactly that
 * reason, and this test is what makes carrying them worth anything.
 *
 * Also checks the properties D46 and `PROVENANCE.md` rest the artefact's
 * legality on: a closed vocabulary and a count threshold. Those are not
 * performance tuning, so they are asserted rather than assumed.
 */
class BigramAssetTest {

    private fun asset(name: String): File {
        val candidates = listOf(
            File("src/main/assets/wordlists/$name"),
            File("app/src/main/assets/wordlists/$name"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("asset $name not found; looked in ${candidates.map { it.absolutePath }}")
    }

    /** The header, and the two arrays behind it. */
    private class Store(bytes: ByteArray) {
        val magic: String
        val version: Int
        val threshold: Int
        val words: Int
        val entries: Int
        val mass: Long
        val checksum: String
        val starts: IntArray
        val table: IntArray

        init {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            magic = String(ByteArray(4).also { buffer.get(it) }, Charsets.US_ASCII)
            version = buffer.short.toInt() and 0xFFFF
            threshold = buffer.short.toInt() and 0xFFFF
            words = buffer.int
            entries = buffer.int
            mass = buffer.long
            checksum = ByteArray(32).also { buffer.get(it) }
                .joinToString("") { "%02x".format(it) }
            // One row per word, plus the sentence start, plus the end sentinel.
            starts = IntArray(words + 2) { buffer.int }
            table = IntArray(entries) { buffer.int }
        }

        fun followers(row: Int): IntArray =
            IntArray(starts[row + 1] - starts[row]) { table[starts[row] + it] ushr FOLLOWER_SHIFT }

        /** Decoded back from the quantised log the writer stored. */
        fun probabilities(row: Int): DoubleArray = DoubleArray(starts[row + 1] - starts[row]) {
            val quantised = table[starts[row] + it] and QUANT_MAX
            Math.exp(quantised.toDouble() / QUANT_MAX * LOG_FLOOR)
        }
    }

    private fun store(language: String) = Store(asset("$language.bigrams").readBytes())

    private fun words(language: String): List<String> =
        asset("$language.txt").readLines()
            .filterNot { it.isEmpty() || it.startsWith("#") }
            .map { it.substringBefore('\t') }

    private fun eachStore(check: (String, Store, List<String>) -> Unit) {
        listOf("de", "en").forEach { check(it, store(it), words(it)) }
    }

    @Test
    fun `the header is what the builder writes`() {
        eachStore { language, store, _ ->
            assertEquals("$language magic", "BKBG", store.magic)
            assertEquals("$language version", 1, store.version)
            assertTrue("$language has only ${store.entries} entries", store.entries > 100_000)
        }
    }

    /**
     * The binding. A store counted against a different wordlist would still
     * load and would still predict — different words.
     */
    @Test
    fun `each store matches the wordlist it was counted from`() {
        eachStore { language, store, words ->
            assertEquals("$language word count", words.size, store.words)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(asset("$language.txt").readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals("$language.bigrams was built against a different $language.txt", digest, store.checksum)
        }
    }

    /** CSR: rows never go backwards and the last one ends at the entry count. */
    @Test
    fun `the row index is well formed`() {
        eachStore { language, store, _ ->
            assertEquals("$language first row", 0, store.starts.first())
            assertEquals("$language last row", store.entries, store.starts.last())
            for (row in 0 until store.starts.size - 1) {
                assertTrue(
                    "$language row $row runs backwards",
                    store.starts[row + 1] >= store.starts[row],
                )
            }
        }
    }

    /**
     * **The closed vocabulary**, which is what keeps a name or an address in
     * the corpus from reaching this file (D46).
     */
    @Test
    fun `every follower is a word in the list`() {
        eachStore { language, store, words ->
            for (row in 0..store.words) {
                store.followers(row).forEach {
                    assertTrue("$language row $row points at word $it of ${words.size}", it < words.size)
                }
            }
        }
    }

    /** Rows are sorted by follower, which is what lets the app binary-search one. */
    @Test
    fun `followers are sorted within a row`() {
        eachStore { language, store, _ ->
            for (row in 0..store.words) {
                val followers = store.followers(row)
                for (index in 1 until followers.size) {
                    assertTrue(
                        "$language row $row is unsorted at $index",
                        followers[index] > followers[index - 1],
                    )
                }
            }
        }
    }

    /**
     * **The threshold**, the other property the artefact's provenance rests on.
     * It cannot be checked directly — the counts are not shipped, only their
     * ratios — so what is checked is that one was declared and is not one.
     */
    @Test
    fun `a threshold above one is recorded`() {
        eachStore { language, store, _ ->
            assertTrue("$language shipped with threshold ${store.threshold}", store.threshold >= 2)
        }
    }

    /** Every row is a distribution: nothing above one, nothing at zero. */
    @Test
    fun `probabilities are probabilities`() {
        eachStore { language, store, _ ->
            for (row in 0..store.words step 7) {
                store.probabilities(row).forEach {
                    assertTrue("$language row $row holds $it", it > 0.0 && it <= 1.0 + 1e-6)
                }
            }
        }
    }

    /**
     * The point of the whole exercise, on the real data: after a word, the
     * store knows what tends to come next. `guten` is followed by `Tag`, and
     * `thank` by `you` — and the sentence-start row, which is the one that
     * fills the strip where it is emptiest, has an opinion too.
     *
     * Matched on the folded form, because which casing the wordlist kept is
     * D22's business and not this test's: `vielen Dank` is stored as `dank`,
     * the least-capitalised spelling the German dictionary offered.
     */
    @Test
    fun `the store predicts the obvious things`() {
        val expectations = mapOf(
            "de" to listOf("guten" to "tag", "vielen" to "dank", "ich" to "habe"),
            "en" to listOf("thank" to "you", "of" to "the", "good" to "morning"),
        )
        expectations.forEach { (language, cases) ->
            val store = store(language)
            val words = words(language)
            val folded = words.map(Folding::fold)
            cases.forEach { (context, wanted) ->
                val row = folded.indexOf(Folding.fold(context))
                assertTrue("$language has no word $context", row >= 0)
                val followers = store.followers(row).map { folded[it] }
                assertTrue(
                    "$language: $context is not followed by $wanted, only ${followers.take(12)}",
                    Folding.fold(wanted) in followers,
                )
            }
        }
    }

    /** The sentence start is a row of its own, one past the last word (D46). */
    @Test
    fun `the sentence start has its own row`() {
        eachStore { language, store, _ ->
            val followers = store.followers(store.words)
            assertTrue("$language has no sentence-start predictions", followers.isNotEmpty())
        }
    }

    private companion object {
        /** 17 bits of wordlist index above 15 bits of quantised probability. */
        const val FOLLOWER_SHIFT = 15
        const val QUANT_MAX = (1 shl 15) - 1

        /** The least likely follower the quantisation bothers to distinguish. */
        const val LOG_FLOOR = -12.0
    }
}
