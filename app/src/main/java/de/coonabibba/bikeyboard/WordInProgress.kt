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

    /** The partial word before the cursor. Empty at a word boundary. */
    val text: CharSequence get() = builder

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
    fun insert(text: CharSequence) {
        text.forEach { char ->
            if (isWordChar(char)) {
                builder.append(char)
            } else {
                builder.setLength(0)
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
        if (!complete) known = false
    }

    /** Starts over: a new field, or a cursor movement we cannot account for. */
    fun reset(known: Boolean) {
        builder.setLength(0)
        this.known = known
    }

    private fun isWordChar(char: Char): Boolean = char.isLetterOrDigit() || char == '\''
}
