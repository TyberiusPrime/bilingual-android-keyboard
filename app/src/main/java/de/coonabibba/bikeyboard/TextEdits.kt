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
     *
     * What counts as something to put a stop after is [endsAWord], and it is
     * asked about a **code point** rather than a `Char` (D55) — an emoji is two
     * chars, and reading only the second of them found half a surrogate pair,
     * which is not a letter, so `hi 🙂 ` refused the gesture.
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
        val end = before.length - spaces
        if (end <= 0) return 0
        return if (endsAWord(Character.codePointBefore(before, end))) spaces else 0
    }

    /**
     * Whether a full stop belongs after [codePoint] (D6, D55).
     *
     * Three kinds of thing, and the third is why this exists:
     *
     * - **A letter or digit**, in any script — `Character.isLetterOrDigit`
     *   takes a code point, so this is not only ASCII and not only the Latin
     *   alphabet.
     * - **A full stop**, which is what makes the gesture repeat into `...`.
     * - **An emoji**, or anything else pictographic. `hi 🙂` is a finished
     *   sentence in every way that matters, and the stop after it is the one
     *   people were reaching for.
     *
     * The emoji test is by Unicode category rather than by a range list,
     * because emoji are not one range and the ranges move with every Unicode
     * revision. One category is the pictograph itself — `OTHER_SYMBOL`, which
     * is 😀 and ☀ and a flag's regional indicators, and also °, © and ™, which
     * end a sentence just as well. The other four are the kinds of piece an
     * emoji *sequence* can end with, since the test only ever sees its last
     * code point: a skin tone (`MODIFIER_SYMBOL`), a variation selector
     * (`NON_SPACING_MARK`), a keycap ring (`ENCLOSING_MARK`), and the tag
     * terminator that closes 🏴󠁧󠁢󠁳󠁣󠁴󠁿 and its two siblings (`FORMAT`).
     *
     * Accepting the pieces outright, rather than walking back to the base they
     * attach to, is deliberate: a mark that attaches to something means what
     * that something means, and a combining accent after a letter is a letter
     * either way. It brings a few strays with it — `^`, a backtick and an acute
     * are modifier symbols too — which is a fair price for never having to know
     * how any particular emoji is spelled.
     */
    private fun endsAWord(codePoint: Int): Boolean = when {
        Character.isLetterOrDigit(codePoint) -> true
        codePoint == '.'.code -> true
        else -> Character.getType(codePoint) in PICTOGRAPH_TYPES
    }

    private val PICTOGRAPH_TYPES: Set<Int> = setOf(
        Character.OTHER_SYMBOL,
        Character.MODIFIER_SYMBOL,
        Character.NON_SPACING_MARK,
        Character.ENCLOSING_MARK,
        Character.FORMAT,
    ).map { it.toInt() }.toSet()

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
     * The word that has just been *finished*, and whatever closed it (D48).
     *
     * For the moment after a word rather than during it: the cursor sits past a
     * space, or a full stop, or both, and the word before that is the one the
     * typist means. Which is most of the time, since a forgotten capital is
     * something you notice once the word is out — and since swiping a word
     * commits the space along with it, it is the *only* moment a swiped word
     * can be re-cased at all.
     *
     * [tail] is everything between the word and the cursor, kept because it has
     * to be put back: `hallo. ` re-cased is `Hallo. `, not `Hallo`.
     *
     * Null when there is nothing to act on — when the cursor is still inside a
     * word (that case is the caller's own tracked word, which it knows better
     * than the field does), when there is no word behind the tail, and
     * deliberately **when the tail crosses a newline**. A gesture that silently
     * edits the line above, out of sight of the cursor, is not one anybody
     * asked for.
     */
    fun finishedWordBefore(before: CharSequence?): FinishedWord? {
        if (before.isNullOrEmpty()) return null
        var end = before.length
        while (end > 0 && !isWordChar(before[end - 1])) {
            if (before[end - 1] == '\n') return null
            end--
        }
        // Still inside a word: nothing was finished.
        if (end == before.length) return null
        var start = end
        while (start > 0 && isWordChar(before[start - 1])) start--
        if (start == end) return null
        return FinishedWord(
            word = before.substring(start, end),
            tail = before.substring(end),
            start = start,
        )
    }

    /**
     * Whether [word] carries a capital somewhere other than the front, which
     * makes it a name and not a misspelling (D44).
     *
     * Neither of this keyboard's languages puts a capital inside a word.
     * German capitalises the first letter of a noun and English the first of a
     * sentence or a proper noun, but nothing in either puts one in the middle —
     * so anything that does is a brand, a product, an identifier or a surname:
     * `iPhone`, `eBay`, `McDonald`, `JavaScript`, `GmbH`, `PostgreSQL`. It is a
     * deliberate keystroke in a deliberate place, and the strongest evidence
     * the keyboard ever gets that the typist knows exactly what they are
     * writing.
     *
     * **A word in capitals throughout is not this**, and the exception is not
     * a nicety — shouting is a styling choice rather than a claim about the
     * word, and `TEH`, `UDN`, `ADN`, `DONT` and `HELOL` are all corrected
     * perfectly well today. Losing them to a rule aimed at `iPhone` would cost
     * far more than the rule was worth.
     */
    fun hasInternalCapital(word: CharSequence): Boolean {
        if (word.length < 2) return false
        var internal = false
        var lower = false
        for (index in word.indices) {
            val char = word[index]
            if (char.isLowerCase()) lower = true
            if (index > 0 && char.isUpperCase()) internal = true
        }
        return internal && lower
    }

    /**
     * Whether [word] is being shouted: at least two characters and not a
     * lowercase letter among them.
     *
     * Digits and apostrophes count as neither, so `DON'T` and `MP3` are as
     * shouted as `HELLO`.
     */
    fun isShouted(word: CharSequence): Boolean =
        word.length >= 2 && word.any { it.isUpperCase() } && word.none { it.isLowerCase() }

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

/**
 * A word the cursor has already moved past, and what sits between (D48).
 *
 * [start] is where the word begins within the text that was read, which is what
 * tells the caller whether the read was long enough to have seen all of it — a
 * word butting against the start of a truncated window may have more in front
 * of it that nobody can see.
 */
data class FinishedWord(val word: String, val tail: String, val start: Int) {
    /** How many characters back from the cursor the word and its tail reach. */
    val span: Int get() = word.length + tail.length
}
