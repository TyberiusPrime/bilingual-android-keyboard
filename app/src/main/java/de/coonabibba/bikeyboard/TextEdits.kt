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

    /**
     * The word the cursor is sitting in, split at the cursor.
     *
     * Used when the cursor arrives somewhere this keyboard did not put it —
     * a tap back into an earlier word — where the only way to know what is
     * there is to ask the app (D23). Both halves are kept because correcting
     * the word means replacing all of it, not just the part in front of the
     * cursor.
     */
    fun wordAtCursor(before: CharSequence?, after: CharSequence?): WordAtCursor {
        val prefix = before?.takeLastWhile(::isWordChar)?.toString().orEmpty()
        val suffix = after?.takeWhile(::isWordChar)?.toString().orEmpty()
        return WordAtCursor(prefix, suffix)
    }

    /**
     * What counts as part of a word. The apostrophe is in, so `don't` is one
     * word — it is on long-press `v` precisely because contractions need it.
     */
    fun isWordChar(char: Char): Boolean = char.isLetterOrDigit() || char == '\''
}

/** A word split at the cursor: [before] it and [after] it. */
data class WordAtCursor(val before: String, val after: String) {
    val text: String get() = before + after
}
