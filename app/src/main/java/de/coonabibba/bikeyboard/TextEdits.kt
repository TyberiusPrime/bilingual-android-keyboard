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

    /**
     * Whether the cursor is at the start of a sentence, and so whether the next
     * letter should be a capital (D42).
     *
     * True at the very beginning, after a newline, and after a sentence mark
     * followed by a space. Not immediately after the mark itself: `Hello.` with
     * the cursor tight against the full stop is still the same sentence until a
     * space says otherwise, and capitalising there would fight anyone typing
     * `e.g.` or a decimal.
     *
     * Closing quotes and brackets are stepped over, so `He said "Stop." ` opens
     * a sentence the same as `Stop. ` does.
     */
    fun startsSentence(before: CharSequence?): Boolean {
        if (before.isNullOrEmpty()) return true
        var index = before.length - 1

        var spaces = 0
        while (index >= 0 && before[index] == ' ') {
            index--
            spaces++
        }
        if (index < 0) return true
        if (before[index] == '\n') return true
        // A mark with nothing after it has not finished the sentence yet.
        if (spaces == 0) return false

        while (index >= 0 && before[index] in AFTER_MARK) index--
        return index >= 0 && before[index] in SENTENCE_MARKS
    }

    /** What ends a sentence. The two languages agree. */
    const val SENTENCE_MARKS = ".!?…"

    /**
     * Quotes and brackets that may sit between the mark and the space.
     *
     * Every quote character, whichever side it nominally belongs to: German
     * closes a quotation with `“` where English opens one with it, and D2 says
     * both languages are live in the same paragraph. The same trap as the one
     * [tokenAtCursor] fell into.
     */
    private const val AFTER_MARK = "\")]}'\u2018\u2019\u201c\u201d\u00ab\u00bb"

    /**
     * Everything between the spaces around the cursor, for remembering a word
     * by hand (D40).
     *
     * A *different* question from [wordAtCursor], and the difference is the
     * whole point. Suggestions and corrections are about words, so they stop at
     * anything that is not a letter — but the things worth putting in the
     * personal store on purpose are frequently not words by that definition.
     * `john@coonabibba.de` is three of them with punctuation in between, and
     * asking the tokeniser for it returns `de`. So the token is delimited by
     * whitespace and nothing else, which is the same rule the eye uses.
     *
     * Both sides of the cursor, because the finger holding the key is nowhere
     * near it and there is no reason to assume it sits at the end.
     *
     * Punctuation that is plainly framing is trimmed: a trailing comma or
     * closing bracket is part of the sentence rather than of the thing. **The
     * full stop is deliberately left alone** — German abbreviates `z.B.`,
     * `d.h.` and `usw.` with one, and a domain is nothing but full stops, so
     * trimming would break more than it fixed. The cost is that remembering an
     * address after the sentence's own full stop keeps it, which is visible in
     * the launcher and removable there.
     */
    fun tokenAtCursor(before: CharSequence?, after: CharSequence?): String {
        val head = before?.takeLastWhile { !it.isWhitespace() }?.toString().orEmpty()
        val tail = after?.takeWhile { !it.isWhitespace() }?.toString().orEmpty()
        return (head + tail)
            .trimStart(*OPENING)
            .trimEnd(*CLOSING)
    }

    private val OPENING = charArrayOf('(', '[', '{', '<', '¿', '¡') + QUOTES
    private val CLOSING = charArrayOf(',', ';', ':', '!', '?', '…', ')', ']', '}', '>') + QUOTES

    /**
     * Quotes are trimmed from **both** ends, because on this keyboard they have
     * no fixed side. German writes `„Fairphone“` and English writes
     * `“Fairphone”`, so `“` opens one language's quotation and closes the
     * other's — and D2 says both are in play in the same sentence.
     */
    private val QUOTES: CharArray
        get() = charArrayOf('"', '„', '“', '”', '‚', '‘', '’', '«', '»')
}

/** A word split at the cursor: [before] it and [after] it. */
data class WordAtCursor(val before: String, val after: String) {
    val text: String get() = before + after
}
