package de.coonabibba.bikeyboard

/**
 * Reads the shipped wordlists out of the APK's assets.
 *
 * Format and provenance are documented in
 * `app/src/main/assets/wordlists/PROVENANCE.md`: a `#` comment header, then
 * `word<TAB>count` per line, in folded order.
 *
 * The folded order is taken on trust here — `WordlistAssetTest` checks the real
 * files against the app's own folding, which is the right place for it. Doing
 * it again at startup would mean folding every one of the 70,000 words, which
 * is most of what avoiding the sort was for.
 *
 * That parse is still a tenth of a second or so, which is why
 * [BilingualKeyboardService] does it once per service — not per field — and off
 * the main thread.
 */
object Wordlists {

    const val GERMAN = "wordlists/de.txt"
    const val ENGLISH = "wordlists/en.txt"

    fun load(open: (String) -> java.io.InputStream, path: String): Lexicon {
        val words = ArrayList<String>(EXPECTED_ENTRIES)
        var counts = LongArray(EXPECTED_ENTRIES)
        var size = 0

        open(path).bufferedReader().use { reader ->
            reader.forEachLine { line ->
                if (line.isEmpty() || line[0] == '#') return@forEachLine
                val tab = line.indexOf('\t')
                if (tab <= 0) return@forEachLine
                val count = line.substring(tab + 1).toLongOrNull() ?: return@forEachLine
                words.add(line.substring(0, tab))
                if (size == counts.size) counts = counts.copyOf(counts.size * 2)
                counts[size++] = count
            }
        }

        return Lexicon(words, counts.copyOf(size))
    }

    /** Both files are a little over 35,000 entries; sized to avoid regrowing. */
    private const val EXPECTED_ENTRIES = 40_000
}
