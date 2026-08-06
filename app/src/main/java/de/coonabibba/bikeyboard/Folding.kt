package de.coonabibba.bikeyboard

/**
 * Reduces a word to the letters someone types when they skip the accents.
 *
 * `über` and `uber` fold to the same thing, so do `Straße` and `strasse`. This
 * is the cheap half of D5's promise: the umlaut costs a long-press, so typing
 * `uber` has to find `über` anyway.
 *
 * **The wordlists are sorted by this function**, by `scripts/build-wordlists.py`
 * which implements the equivalent rule in Python. The keyboard binary-searches
 * that order instead of sorting 35,000 strings at startup, so the two must not
 * drift; `WordlistAssetTest` checks them against each other on the real files.
 *
 * This used to normalise to NFD and strip combining marks, which is the general
 * answer and the wrong one here: the two wordlists between them contain fifteen
 * non-ASCII characters — `ä ü ö ß Ä Ü Ö é ñ â ê à á ó è` — every one of them
 * precomposed, and the keyboard can only type four of them. A table covers the
 * alphabet these two languages have, without a Unicode library, and the same
 * table then works one character at a time inside the edit-distance loop where
 * building strings was costing more than the entire rest of the search.
 *
 * The table is wider than the data, because the long-press popups can now type
 * accents no German or English word contains (D32) — `ø`, `ł`, `ž`. Folding
 * those costs a line each and means a name typed with its accents still finds
 * the personal store entry typed without them. It does not touch the sort
 * order, since none of them occur in either wordlist.
 *
 * The trade is that an accent from outside the table — Greek, Cyrillic,
 * Vietnamese — is not folded away. Text like that can still arrive by being
 * read back out of a field (D23), and the result is that it fails to match a
 * dictionary word, which is correct: it is not a word in either of these
 * languages.
 */
object Folding {

    fun fold(word: CharSequence): String {
        val folded = StringBuilder(word.length)
        word.forEach { char ->
            val lowered = char.lowercaseChar()
            when {
                // German spells it out when it cannot be written, so folding it
                // this way makes `strasse` and `Straße` the same lookup.
                lowered == 'ß' -> folded.append("ss")
                // A combining accent left over from text somewhere else in the
                // world, arriving decomposed. Cheaper to drop than to compose.
                lowered in COMBINING -> Unit
                else -> folded.append(foldChar(lowered))
            }
        }
        return folded.toString()
    }

    /**
     * The single-character version of [fold].
     *
     * `ß` is the one character the two cannot agree on: this returns `s` where
     * [fold] gives `ss`, because a character has to fold to a character. The
     * difference shows up as one extra edit between `strasse` and `Straße`,
     * which is the right sort of wrong.
     */
    fun foldChar(char: Char): Char = when (char.lowercaseChar()) {
        'ä', 'à', 'á', 'â', 'ã', 'å' -> 'a'
        'ë', 'è', 'é', 'ê' -> 'e'
        'ï', 'ì', 'í', 'î' -> 'i'
        'ö', 'ò', 'ó', 'ô', 'õ', 'ø' -> 'o'
        'ü', 'ù', 'ú', 'û' -> 'u'
        'ý', 'ÿ' -> 'y'
        'ç', 'č', 'ć' -> 'c'
        'ñ' -> 'n'
        'ł' -> 'l'
        'š', 'ś' -> 's'
        'ž', 'ź', 'ż' -> 'z'
        'ß' -> 's'
        else -> char.lowercaseChar()
    }

    /**
     * Whether [word] starts the way [prefix] does, ignoring case and accents.
     */
    fun startsWith(word: CharSequence, prefix: CharSequence): Boolean =
        fold(word).startsWith(fold(prefix))

    /** Combining diacritics, for the decomposed text that occasionally turns up. */
    private val COMBINING = '̀'..'ͯ'
}
