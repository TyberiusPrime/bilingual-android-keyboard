package de.coonabibba.bikeyboard

/**
 * The word being typed, reconstructed from our own edits instead of read back
 * from the app.
 *
 * The strip needs the current word on every keystroke. Asking the app for it —
 * `getTextBeforeCursor` — is an IPC round trip per key, which the API notes
 * name as the thing that makes a keyboard feel broken. So the keyboard
 * remembers what it typed.
 *
 * [known] is the honesty valve. It is false whenever that memory cannot account
 * for the text immediately before the cursor: after the app or the user moved
 * the cursor, after a selection was replaced, or after a backspace ate past the
 * start of what we had tracked. Nothing may be suggested while it is false,
 * because a suggestion is an offer to replace characters we would then be
 * guessing at.
 *
 * Same posture as the expected-cursor bookkeeping in the service, and the same
 * down payment on roadmap step 3: conservative, and it gives up rather than
 * inventing.
 */
class WordInProgress {

    private val builder = StringBuilder()

    /** The part of the word in front of the cursor. Empty at a word boundary. */
    val text: CharSequence get() = builder

    /**
     * The part of the word *after* the cursor, when the cursor is sitting in
     * the middle of one — which happens when it is put there rather than typed
     * to (D23). Empty while typing normally, which is why nothing else has to
     * think about it.
     */
    var suffix: String = ""
        private set

    /** The whole word the cursor is in. What gets looked up, and what gets replaced. */
    val full: String get() = builder.toString() + suffix

    private val typedTouches = mutableListOf<TypedTouch>()

    /**
     * Where the thumb landed for each character of the word, when every one of
     * them was typed here (D28).
     *
     * Empty unless the whole word can be accounted for: a word the cursor
     * jumped back to was not typed on these keys, and a long-press alternate
     * came from a popup rather than from a key. Auto-correction refuses to act
     * without the full set, because half a set is worse than none — it would
     * make the keyboard confident about exactly the words it knows least about.
     */
    val touches: List<TypedTouch>
        get() = if (typedTouches.size == builder.length && suffix.isEmpty()) typedTouches else emptyList()

    /**
     * Touches for [full], one per character, synthesised where the keyboard has
     * no record of them (D37).
     *
     * [touches] is all-or-nothing on purpose: half a set would make the
     * auto-correction confident about exactly the words it knows least about.
     * The strip's search needs the same shape regardless, so a word that cannot
     * account for itself gets untouched ones — every substitution at full
     * price, which is the honest reading of "nobody saw where the thumb went".
     */
    val fullTouches: List<TypedTouch>
        get() {
            val recorded = touches
            val word = full
            if (recorded.size == word.length) return recorded
            return word.map(TypedTouch::untouched)
        }

    var known: Boolean = true
        private set

    /**
     * Records text we just committed.
     *
     * A word character extends the word; anything else ends it. The apostrophe
     * counts as a word character so that `don't` and `hab's` are one word — it
     * is on long-press `v` precisely because contractions need it, and D6
     * expects correction to place it unprompted later.
     *
     * Crossing a boundary also restores [known]: whatever we could not account
     * for is now behind a separator, and the word starting here is one we have
     * seen every character of.
     */
    fun insert(text: CharSequence, touch: TypedTouch? = null) {
        text.forEach { char ->
            if (isWordChar(char)) {
                builder.append(char)
                // Only a single-character insertion can carry a touch, so
                // anything longer leaves the list short and the word without
                // spatial evidence — which is what [touches] checks for.
                if (touch != null && text.length == 1) typedTouches += touch
            } else {
                builder.setLength(0)
                typedTouches.clear()
                // Whatever followed the cursor is on the far side of a
                // separator now, so it is a different word.
                suffix = ""
                known = true
            }
        }
    }

    /**
     * Records a single-character deletion.
     *
     * Deleting past the start of the tracked word means the cursor is now
     * eating into text this keyboard never typed, so it stops claiming to know
     * what is there.
     */
    fun deleteOne() {
        if (builder.isNotEmpty()) {
            builder.setLength(builder.length - 1)
            if (typedTouches.isNotEmpty()) typedTouches.removeAt(typedTouches.size - 1)
        } else {
            known = false
        }
    }

    /**
     * Records a word deletion (the backspace swipe of D20), which by definition
     * removes everything back to a whitespace boundary — so the word in
     * progress afterwards is empty, and known to be.
     *
     * [complete] is false when the deletion was truncated by how far back the
     * service can read, which may have left part of a word standing.
     */
    fun deleteWord(complete: Boolean) {
        builder.setLength(0)
        typedTouches.clear()
        suffix = ""
        if (!complete) known = false
    }

    /** Starts over: a new field, or a cursor movement we cannot account for. */
    fun reset(known: Boolean) {
        builder.setLength(0)
        typedTouches.clear()
        suffix = ""
        this.known = known
    }

    /**
     * Adopts a word read back from the app, split at the cursor (D23).
     *
     * This is the one place the keyboard learns what is in front of it by
     * asking rather than by remembering, and it happens once per cursor jump
     * rather than once per keystroke.
     */
    fun adopt(word: WordAtCursor) {
        builder.setLength(0)
        typedTouches.clear()
        builder.append(word.before)
        suffix = word.after
        known = true
    }

    private fun isWordChar(char: Char): Boolean = TextEdits.isWordChar(char)
}
