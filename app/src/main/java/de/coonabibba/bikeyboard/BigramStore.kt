package de.coonabibba.bikeyboard

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer

/**
 * How often one word follows another, counted from the subtitle corpus (D46).
 *
 * The half of D9 that was never built: the strip is blank at every word
 * boundary, which is a third of the typing cycle, because both wordlists are
 * unigram and there was nothing to predict from. This is what fills it.
 *
 * **Read, never parsed.** The asset is a memory-mapped `ByteBuffer` straight
 * out of the APK — no allocation, no startup cost, nothing on the heap. That
 * matters more here than it did for the wordlists: this file is twenty times
 * their size, and an input method that holds twenty megabytes of table is one
 * the system kills while somebody is typing into it.
 *
 * The layout is written by `scripts/build-bigrams.py` and checked by
 * `BigramAssetTest`:
 *
 * ```
 * header    magic "BKBG", version, threshold, word count, entry count,
 *           retained pair mass, SHA-256 of the wordlist it belongs to
 * starts    one int per word, plus one for the sentence start, plus a sentinel
 * entries   one int each: 17 bits of wordlist index, 15 bits of quantised
 *           conditional log probability
 * ```
 *
 * **Keys are wordlist indices**, which is what makes four and a half million
 * entries fit in eighteen megabytes. The price is that a store belongs to the
 * wordlist it was counted from: swap one and every prediction becomes a
 * different word, quietly and plausibly. [wordCount] is checked against the
 * lexicon on load for that reason, and the asset test checks the checksum too.
 */
class BigramStore private constructor(
    private val starts: IntBuffer,
    private val entries: IntBuffer,
    /** How many words the wordlist had when this was counted. */
    val wordCount: Int,
    /** The count below which a pair was dropped. Never one — see D46. */
    val threshold: Int,
) {

    /** How many pairs this store has an opinion about. */
    val size: Int get() = entries.capacity()

    /**
     * The row for a context: a word's index, or [sentenceStart].
     *
     * Returns the empty range for anything out of range, which includes every
     * context the store dropped below the threshold. A context with no
     * followers is the normal case for a rare word, not an error.
     */
    private fun rowOf(context: Int): IntRange {
        if (context < 0 || context > wordCount) return IntRange.EMPTY
        return starts.get(context) until starts.get(context + 1)
    }

    /** The context standing for the beginning of a sentence. */
    val sentenceStart: Int get() = wordCount

    /**
     * Walks what may follow [context], best first.
     *
     * Rows are stored sorted by follower index rather than by probability, so
     * that [probabilityOf] can binary-search one — which means this has to sort
     * to answer "what is likeliest". Rows are short enough for that to be
     * cheaper than storing them twice: the median is a handful of entries and
     * the caller only ever wants the top few.
     */
    fun forEachFollower(context: Int, action: (word: Int, probability: Float) -> Unit) {
        for (slot in rowOf(context)) {
            val packed = entries.get(slot)
            action(packed ushr FOLLOWER_SHIFT, decode(packed))
        }
    }

    /**
     * How likely [word] is to follow [context], or zero if the store has no
     * opinion — which is the honest answer for a pair it never saw five times.
     *
     * By binary search within the row, since a context like `the` has thousands
     * of followers and this is asked once per candidate.
     */
    fun probability(context: Int, word: Int): Float {
        val row = rowOf(context)
        if (row.isEmpty()) return 0f
        var low = row.first
        var high = row.last
        while (low <= high) {
            val middle = (low + high) ushr 1
            val found = entries.get(middle) ushr FOLLOWER_SHIFT
            when {
                found < word -> low = middle + 1
                found > word -> high = middle - 1
                else -> return decode(entries.get(middle))
            }
        }
        return 0f
    }

    /**
     * A walk through one context's followers, in wordlist order.
     *
     * For asking about a *run* of words rather than one — which is what
     * completing a prefix is, since the wordlists are sorted by folded form and
     * so every completion of `s` is one contiguous range of indices (D47).
     *
     * Both sides are sorted by index, so the whole run costs one binary search
     * and then a linear merge. Asking [probability] per candidate instead is a
     * binary search each, and for a one-letter prefix that is eight thousand of
     * them against a row that can hold thousands — measured at 1.4ms on a
     * laptop, which is most of the per-keystroke budget spent on the commonest
     * keystroke there is.
     */
    inner class Cursor(context: Int, from: Int) {
        private val end: Int
        private var slot: Int

        init {
            val row = rowOf(context)
            end = row.last + 1
            // The first follower at or past `from`.
            var low = row.first
            var high = row.last
            while (low <= high) {
                val middle = (low + high) ushr 1
                if ((entries.get(middle) ushr FOLLOWER_SHIFT) < from) low = middle + 1
                else high = middle - 1
            }
            slot = low
        }

        /**
         * How likely [word] is here, advancing past everything before it.
         *
         * Must be called with non-decreasing [word], which is what walking a
         * completion range gives.
         */
        fun probabilityOf(word: Int): Float {
            while (slot < end && (entries.get(slot) ushr FOLLOWER_SHIFT) < word) slot++
            if (slot >= end) return 0f
            val packed = entries.get(slot)
            return if ((packed ushr FOLLOWER_SHIFT) == word) decode(packed) else 0f
        }
    }

    /**
     * Undoes the writer's quantisation: fifteen bits of natural log, linear
     * from certain down to [LOG_FLOOR].
     *
     * The floor is where the interpolation with the unigram weight takes over
     * anyway (D46), so the resolution lost at the bottom buys nothing back.
     */
    private fun decode(packed: Int): Float =
        Math.exp((packed and QUANT_MASK).toDouble() / QUANT_MAX * LOG_FLOOR).toFloat()

    companion object {

        const val GERMAN = "wordlists/de.bigrams"
        const val ENGLISH = "wordlists/en.bigrams"

        /** A store with nothing to say, for a lexicon whose asset failed to load. */
        val NONE = BigramStore(
            IntBuffer.allocate(1),
            IntBuffer.allocate(0),
            wordCount = 0,
            threshold = 0,
        )

        private const val FOLLOWER_SHIFT = 15
        private const val QUANT_MAX = (1 shl 15) - 1
        private const val QUANT_MASK = QUANT_MAX
        private const val LOG_FLOOR = -12.0

        private const val MAGIC = 0x424B4247 // "BKBG", big-endian for readability
        private const val VERSION = 1
        private const val HEADER = 4 + 2 + 2 + 4 + 4 + 8 + 32

        /**
         * Reads a store out of [buffer], or returns [NONE] if it is not one.
         *
         * **Refuses rather than guesses.** A truncated asset, a version this
         * build does not know, or a word count that disagrees with [words] all
         * produce a store with no opinions — the strip goes back to being empty
         * between words, which is where it was yesterday. Predicting from a
         * table whose indices mean something else would be worse than
         * predicting nothing, because it would look like it was working.
         */
        fun read(buffer: ByteBuffer, words: Int): BigramStore {
            if (buffer.capacity() < HEADER) return NONE
            val head = buffer.duplicate().order(ByteOrder.BIG_ENDIAN)
            if (head.getInt(0) != MAGIC) return NONE

            val little = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val version = little.getShort(4).toInt() and 0xFFFF
            if (version != VERSION) return NONE
            val threshold = little.getShort(6).toInt() and 0xFFFF
            val wordCount = little.getInt(8)
            val count = little.getInt(12)
            if (wordCount != words) return NONE

            // One row per word, one for the sentence start, one sentinel.
            val rows = wordCount + 2
            val needed = HEADER.toLong() + rows.toLong() * 4 + count.toLong() * 4
            if (needed > buffer.capacity()) return NONE

            val starts = slice(little, HEADER, rows)
            val entries = slice(little, HEADER + rows * 4, count)
            return BigramStore(starts, entries, wordCount, threshold)
        }

        private fun slice(buffer: ByteBuffer, offset: Int, ints: Int): IntBuffer {
            val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            view.position(offset)
            view.limit(offset + ints * 4)
            return view.slice().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        }
    }
}
