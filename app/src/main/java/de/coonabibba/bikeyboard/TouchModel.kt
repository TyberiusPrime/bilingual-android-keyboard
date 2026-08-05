package de.coonabibba.bikeyboard

import kotlin.math.hypot

/**
 * One typed character, with the keys the thumb nearly hit instead.
 *
 * [alternatives] maps a character to what it would cost to say the typist meant
 * *that* rather than [char]: zero for the key actually pressed, small for a
 * neighbour the touch was almost on, and absent for anything the finger was
 * nowhere near. It is the difference between "these two words are one edit
 * apart" and "this word is one *plausible slip* away", which is the difference
 * between offering a correction and being sure enough to apply one (D28).
 */
class TypedTouch(val char: Char, val alternatives: Map<Char, Float>) {

    /** What substituting [candidate] for this character costs. */
    fun costOf(candidate: Char): Float = when {
        candidate == char -> 0f
        else -> alternatives[candidate] ?: FAR
    }

    companion object {
        /** A key the finger was not near: as expensive as any other substitution. */
        const val FAR = 1f

        /** For a character that arrived without a touch — a long-press alternate. */
        fun untouched(char: Char) = TypedTouch(char, emptyMap())
    }
}

/**
 * Where the letter keys are, and how plausible each one is for a given touch.
 *
 * This is the smallest useful piece of D15's touch model: taps produce a
 * distribution over keys rather than a single key. What D15 asks for in full —
 * learning that one-thumb taps drift predictably — is roadmap step 5 and is not
 * here. What is here is the geometry, which is enough to tell an adjacent-key
 * slip from a typo, and that is what the auto-correction threshold rests on.
 */
class KeyGeometry(private val keys: List<Entry>, private val keyWidth: Float) {

    class Entry(val char: Char, val centreX: Float, val centreY: Float)

    /**
     * The keys plausibly meant by a touch at ([x], [y]) that landed on [pressed].
     *
     * Cost rises with how much further the neighbour's centre is than the
     * pressed key's, measured in key widths: a touch exactly between two keys
     * makes both nearly free, and a key a full width further away costs as much
     * as any other substitution — at which point it is not worth carrying.
     */
    fun alternatives(x: Float, y: Float, pressed: Char): Map<Char, Float> {
        if (keys.isEmpty() || keyWidth <= 0f) return emptyMap()
        val pressedDistance = keys.firstOrNull { it.char == pressed }
            ?.let { hypot(x - it.centreX, y - it.centreY) }
            ?: return emptyMap()

        val alternatives = HashMap<Char, Float>()
        keys.forEach { key ->
            if (key.char == pressed) return@forEach
            val distance = hypot(x - key.centreX, y - key.centreY)
            val cost = (distance - pressedDistance) / keyWidth
            if (cost < TypedTouch.FAR) {
                alternatives[key.char] = cost.coerceAtLeast(NEAREST)
            }
        }
        return alternatives
    }

    private companion object {
        /**
         * Even a key the touch was dead centre between is not free: the typist
         * did hit one of them, and the letter they hit should win a tie.
         */
        const val NEAREST = 0.08f
    }
}
