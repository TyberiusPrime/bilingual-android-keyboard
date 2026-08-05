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
     * Whether [word] starts the way [prefix] does, ignoring case and accents.
     */
    fun startsWith(word: CharSequence, prefix: CharSequence): Boolean =
        fold(word).startsWith(fold(prefix))
}
