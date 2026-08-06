package de.coonabibba.bikeyboard

/**
 * Whether a press is the second half of *tap, then press and hold* on the same
 * key.
 *
 * This exists because `h` carries two things at once (D38, D39). A plain drag
 * off a letter now traces a word, and steering the cursor by line is a drag off
 * `h` — and the two cannot be told apart by direction, since `h` to `b` is down
 * and to the left, which is exactly what steering looks like. So they are told
 * apart by what came *before*: a tap on the key, immediately followed by
 * another press. No swipe ever begins that way, because a swipe begins with a
 * finger landing on a key it has not just left.
 *
 * The state is only *read* by the press, never consumed by it, so a press that
 * turns out to be an ordinary tap can go on to arm the next one — holding and
 * releasing repeatedly must not need a rhythm. Anything that is not a plain tap
 * clears it, because a long-press alternate or a completed gesture is a
 * deliberate act in its own right and should not leave a gesture cocked behind
 * it.
 *
 * Pure, with the clock supplied by the caller, so the rules can be tested
 * without a keyboard — the same bargain [DoubleTap] and [SpaceGesture] make.
 */
class TapThenHold(private val windowMs: Long) {

    private var tapped: Key? = null
    private var at = Long.MIN_VALUE

    /** Records an ordinary tap, which may arm a press that follows it quickly. */
    fun tap(key: Key, now: Long) {
        tapped = key
        at = now
    }

    /** Anything that is not a plain tap breaks the pair. */
    fun reset() {
        tapped = null
    }

    /**
     * Whether pressing [key] now continues a tap on the same key.
     *
     * Compared by value rather than identity: rebuilding the layout — which
     * happens whenever a setting changes — replaces every [Key] with an equal
     * one, and a gesture should not be cancelled by that.
     */
    fun arms(key: Key, now: Long): Boolean =
        tapped == key && now - at <= windowMs
}
