package de.coonabibba.bikeyboard

/**
 * One language's vocabulary, with how common each word is.
 *
 * Both languages stay loaded at once and neither is "current" (D1, D2). Two
 * lexicons are queried on every keystroke and their candidates compete on one
 * scale: [weightOf] is the word's share of its own corpus, so a common German
 * word and a common English word come out comparable even though the corpora
 * differ in size.
 *
 * [words] **must already be sorted by [Folding.fold]**. The wordlist assets are
 * written that way, and trusting it is what keeps startup to a read rather than
 * a read plus a 35,000-string sort. [Wordlists] checks the order as it loads.
 */
class Lexicon(
    val language: Language,
    private val words: List<String>,
    counts: LongArray,
) {

    init {
        require(words.size == counts.size) { "each word needs a count" }
    }

    private val weights: FloatArray = run {
        var total = 0.0
        counts.forEach { total += it }
        // An empty lexicon has no weight to share out; the divisor only has to
        // avoid a division by zero, since nothing will read the result.
        val divisor = if (total > 0.0) total else 1.0
        FloatArray(counts.size) { (counts[it] / divisor).toFloat() }
    }

    val size: Int get() = words.size

    fun wordAt(index: Int): String = words[index]

    fun weightAt(index: Int): Float = weights[index]

    /**
     * Whether this lexicon knows [word], ignoring case and accents.
     *
     * Folded rather than exact, so that `fuss` counts as knowing `Fuß` — the
     * point of asking is whether to offer adding the word to the personal
     * store, and an accent variant of a word already in the dictionary is not a
     * new word.
     */
    fun knows(word: CharSequence): Boolean = indexOf(word) >= 0

    /**
     * Where [word] sits in this lexicon, or -1 if it is not a word here.
     * Folded, so `strasse` finds `Straße` — see [knows].
     */
    fun indexOf(word: CharSequence): Int {
        val folded = Folding.fold(word)
        if (folded.isEmpty()) return -1
        val index = lowerBound(folded)
        return if (index < words.size && Folding.fold(words[index]) == folded) index else -1
    }

    /**
     * Whether this lexicon has [word] spelled exactly that way, give or take
     * case.
     *
     * Stricter than [knows], and the difference matters: `uber` *knows* `über`
     * and that is what stops the strip offering to add it to the personal
     * store, but it is not the same word, and D5 wants the missing umlaut
     * corrected. Nothing is ever auto-corrected away from a spelling that is
     * exactly right (D28).
     */
    fun knowsExactly(word: CharSequence): Boolean {
        val folded = Folding.fold(word)
        if (folded.isEmpty()) return false
        val lowered = word.toString().lowercase()
        for (index in completions(folded)) {
            val candidate = words[index]
            if (Folding.fold(candidate) != folded) continue
            if (candidate.lowercase() == lowered) return true
        }
        return false
    }

    /**
     * The indices of every word starting with [foldedPrefix].
     *
     * Found by two binary searches rather than by scanning, so folding happens
     * a couple of dozen times per query instead of once per word.
     */
    fun completions(foldedPrefix: String): IntRange {
        if (foldedPrefix.isEmpty()) return IntRange.EMPTY
        val from = lowerBound(foldedPrefix)
        // Every folded key is ASCII, so appending the largest possible char
        // gives a bound just past the last word carrying the prefix.
        val to = lowerBound(foldedPrefix + '￿')
        return from until to
    }

    /** The first index whose folded word is not less than [key]. */
    private fun lowerBound(key: String): Int {
        var low = 0
        var high = words.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (Folding.fold(words[mid]) < key) low = mid + 1 else high = mid
        }
        return low
    }
}
