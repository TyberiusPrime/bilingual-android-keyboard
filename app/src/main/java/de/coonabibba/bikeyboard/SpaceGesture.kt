package de.coonabibba.bikeyboard

/**
 * Decides whether a tap on the space bar inserts a space or ends the sentence
 * (D6, D20).
 *
 * Two ways to end a sentence, and they are the same gesture seen from either
 * side of an accepted suggestion:
 *
 * - **Two space taps in quick succession.** The plain case.
 * - **One space tap after accepting a suggestion**, because accepting one
 *   already put a space there. Without this the text ends *space, space* rather
 *   than *word, space*, D6's rule declines to make a full stop out of it, and
 *   you get a third space instead of the sentence you asked for.
 *
 * State is deliberately tiny — when the last space was, and whether a suggestion
 * put one there — and **any other input clears it**. That also fixes something
 * the key-and-timestamp version got wrong: typing `space`, a letter, `space`
 * fast enough counted as a double tap, because nothing in between ever reset it.
 *
 * Pure, so the rules can be tested without a keyboard: the caller supplies the
 * clock.
 */
class SpaceGesture(private val doubleTapMs: Long = DOUBLE_TAP_MS) {

    private var lastSpaceAt = 0L
    private var spacePending = false
    private var afterAcceptedSuggestion = false

    /**
     * Records a tap on the space bar. True when it should end the sentence
     * rather than insert a space.
     *
     * The pair is consumed either way, so a third tap starts a fresh one rather
     * than chaining another full stop off the same run.
     */
    fun tap(now: Long): Boolean {
        val doubled = spacePending && now - lastSpaceAt <= doubleTapMs
        val endsSentence = doubled || afterAcceptedSuggestion
        spacePending = !endsSentence
        lastSpaceAt = now
        afterAcceptedSuggestion = false
        return endsSentence
    }

    /** A suggestion was accepted; the space it inserted counts as the first of the pair. */
    fun suggestionAccepted() {
        spacePending = false
        afterAcceptedSuggestion = true
    }

    /** Anything else: a letter, a deletion, a cursor move, a new field. */
    fun otherInput() {
        spacePending = false
        afterAcceptedSuggestion = false
    }

    private companion object {
        /** Window for a second tap to count as part of the same gesture. */
        const val DOUBLE_TAP_MS = 350L
    }
}
