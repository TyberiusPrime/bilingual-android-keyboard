package de.coonabibba.bikeyboard

import java.text.Normalizer

/**
 * Reduces a word to the letters someone types when they skip the accents.
 *
 * `über` and `uber` fold to the same thing, so do `Straße` and `strasse`. This
 * is the cheap half of D5's promise: the umlaut costs a long-press, so typing
 * `uber` has to find `über` anyway.
 *
 * **The wordlists are sorted by this function**, by `scripts/build-wordlists.py`
 * which implements the identical rule in Python. The keyboard binary-searches
 * that order instead of sorting 35,000 strings at startup, so the two must not
 * drift; `WordlistAssetTest` checks them against each other on the real files.
 */
object Folding {

    fun fold(word: CharSequence): String {
        // ß before the accent stripping: it decomposes to nothing, and German
        // spells it out as `ss` when it cannot be written.
        val lowered = word.toString().lowercase().replace("ß", "ss")
        val decomposed = Normalizer.normalize(lowered, Normalizer.Form.NFD)
        val folded = StringBuilder(decomposed.length)
        decomposed.forEach { char ->
            if (Character.getType(char) != Character.NON_SPACING_MARK.toInt()) {
                folded.append(char)
            }
        }
        return folded.toString()
    }

    /**
     * The single-character version of [fold], without the allocation.
     *
     * [fold] normalises and strips combining marks, which means building two
     * strings; doing that inside an edit-distance table costs more than the
     * whole rest of the search put together — it was 18ms per correction before
     * this existed and a fraction of one after. The cases below are the
     * accented letters the two wordlists actually contain.
     *
     * `ß` folds to `s` rather than to `ss`, which [fold] cannot do and this
     * cannot avoid; the difference shows up as one extra edit for `strasse`
     * against `straße`, which is the right sort of wrong.
     */
    fun foldChar(char: Char): Char = when (char) {
        'ä', 'à', 'á', 'â', 'ã', 'å' -> 'a'
        'ë', 'è', 'é', 'ê' -> 'e'
        'ï', 'ì', 'í', 'î' -> 'i'
        'ö', 'ò', 'ó', 'ô', 'õ' -> 'o'
        'ü', 'ù', 'ú', 'û' -> 'u'
        'ç' -> 'c'
        'ñ' -> 'n'
        'ß' -> 's'
        else -> char
    }

    /**
     * Whether [word] starts the way [prefix] does, ignoring case and accents.
     */
    fun startsWith(word: CharSequence, prefix: CharSequence): Boolean =
        fold(word).startsWith(fold(prefix))
}
