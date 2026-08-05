package de.coonabibba.bikeyboard

/**
 * Whether a tap on a key is the second of a quick pair.
 *
 * The pair is consumed when it fires, so a third tap starts a fresh one rather
 * than chaining another action off the same run of tapping. Pure — the caller
 * supplies the clock — so the timing rules can be tested without a keyboard.
 *
 * The space bar has its own version of this ([SpaceGesture]) because a space
 * carries an extra state: the space an accepted suggestion inserted counts as
 * the first tap of the pair.
 */
class DoubleTap(private val windowMs: Long = WINDOW_MS) {

    private var lastAt = Long.MIN_VALUE
    private var pending = false

    fun tap(now: Long): Boolean {
        val doubled = pending && now - lastAt <= windowMs
        pending = !doubled
        lastAt = now
        return doubled
    }

    /** Anything else the user did in between breaks the pair. */
    fun reset() {
        pending = false
    }

    private companion object {
        /** The same window the space bar uses; one keyboard, one idea of "quickly". */
        const val WINDOW_MS = 350L
    }
}
