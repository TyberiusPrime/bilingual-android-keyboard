package de.coonabibba.bikeyboard

/**
 * Pure text decisions, kept out of the service so they can be tested without
 * an `InputConnection`.
 */
object TextEdits {

    /**
     * How many characters to delete so that the word before the cursor is gone.
     *
     * Trailing whitespace goes first, then the word itself — otherwise
     * backspacing from just after a word would only eat the gap and leave the
     * word sitting there.
     *
     * [before] is the text immediately preceding the cursor, most recent last.
     */
    fun wordDeletionCount(before: CharSequence): Int {
        var end = before.length
        while (end > 0 && before[end - 1].isWhitespace()) end--
        while (end > 0 && !before[end - 1].isWhitespace()) end--
        return before.length - end
    }

    /**
     * Whether a second tap on space should end the sentence rather than insert
     * another space (D6).
     *
     * True only when the text is `…<word-character><space>`: after punctuation,
     * a newline, a run of spaces, or at the very start, a space is just a space.
     */
    fun endsSentenceOnDoubleSpace(before: CharSequence?): Boolean =
        before != null &&
            before.length >= 2 &&
            before[before.length - 1] == ' ' &&
            before[before.length - 2].isLetterOrDigit()
}
