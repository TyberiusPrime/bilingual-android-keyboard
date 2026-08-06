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
     * How many trailing spaces a double tap should swallow before writing its
     * full stop, or zero if this is not a sentence ending at all (D6).
     *
     * One space in the ordinary case — `word ` becomes `word. `. **Two when a
     * full stop is already there**, because the previous double tap left its
     * own space behind: `word. ` plus a fresh space is `word.  `, and taking
     * both back gives `word.. `. That is what makes the gesture repeat, and
     * three double taps spell `...` — otherwise a trip to the symbol layer for
     * something people type constantly.
     *
     * Zero after other punctuation, after a newline, after a deliberate run of
     * three or more spaces, or at the very start. `!.` is not a thing.
     */
    fun spacesBeforeSentenceEnd(before: CharSequence?): Int {
        if (before == null) return 0
        var spaces = 0
        while (spaces < MAX_SWALLOWED_SPACES &&
            spaces < before.length &&
            before[before.length - 1 - spaces] == ' '
        ) {
            spaces++
        }
        if (spaces == 0) return 0
        val index = before.length - spaces - 1
        if (index < 0) return 0
        val preceding = before[index]
        return if (preceding.isLetterOrDigit() || preceding == '.') spaces else 0
    }

    /** A run longer than this was typed on purpose and is left alone. */
    private const val MAX_SWALLOWED_SPACES = 2

    /**
     * Whether an accepted suggestion should be followed by a space.
     *
     * Yes at the end of the text and in front of another word, so that
     * predictions can be tapped one after another; no when the text already
     * continues with a space or a comma, which is the case when the cursor has
     * jumped back into a finished sentence (D23). Getting this wrong inserts a
     * double space every time an earlier word is corrected.
     */
    fun needsTrailingSpace(next: Char?): Boolean = next == null || isWordChar(next)

    /**
     * Whether [text] is punctuation that belongs tight against the word before
     * it, with no space in between.
     *
     * Accepting a suggestion finishes the word *and* puts a space after it,
     * which is right when the next thing is another word and wrong when it is a
     * comma. Rather than ask the typist to backspace, the keyboard takes its own
     * space back — but only for the marks where there is no argument about it.
     *
     * Closing brackets and quotes hug; opening ones do not, which is why `(` and
     * `„` are absent. The apostrophe hugs because the case it exists for is
     * `dont` → `don` + `'` + `t` (D27), never a quotation.
     */
    fun hugsPreviousWord(text: String): Boolean =
        text.length == 1 && text[0] in HUGGING

    private const val HUGGING = ",.!?;:'’)]}…"

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
