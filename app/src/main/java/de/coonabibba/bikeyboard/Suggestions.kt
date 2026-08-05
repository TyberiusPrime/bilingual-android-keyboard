package de.coonabibba.bikeyboard

/**
 * One candidate offered in the suggestion strip.
 *
 * [confidence] deliberately has no default. Per D3 the scorer must emit a
 * *calibrated* probability that this is what was meant, not merely a rank, and
 * a producer that cannot say how sure it is has not done the work the
 * auto-replace threshold depends on. Making the field mandatory is the cheapest
 * way to keep that from being skipped quietly later.
 *
 * Nothing above the strip reads it yet — the strip only ever offers, never
 * replaces (D3), so at this stage every candidate is displayed regardless.
 */
data class Suggestion(val text: String, val confidence: Float)

/**
 * Where the strip's contents come from.
 *
 * Empty until roadmap step 4 lands dictionaries and the personal store. The
 * interface exists now so that the strip, the tap handling and the editor
 * bookkeeping around it can be built and tested against something.
 *
 * **This signature is provisional.** The architecture in `docs/design.md` has
 * the candidate set produced from a *sequence of touch points* (D7, D15) and
 * scored in sentence context by one multilingual model (D2, D12); a prefix of
 * committed characters is strictly less than that. It is what the keyboard
 * actually knows today, and widening it later touches only this file, the
 * service's [BilingualKeyboardService.refreshSuggestions] and the source
 * implementation.
 */
fun interface SuggestionSource {

    /**
     * Candidates for the word currently being typed, best first.
     *
     * [word] is the partial word before the cursor, empty when the cursor sits
     * at a word boundary — in which case anything returned is a next-word
     * prediction rather than a correction (D9 says the strip carries both).
     */
    fun suggest(word: CharSequence): List<Suggestion>
}

/**
 * The source until step 4. Keeps the strip present and empty, which is exactly
 * what roadmap step 2 asks for: the height is spent, the layout never moves,
 * and nothing pretends to know a word it has no dictionary for.
 */
object NoSuggestions : SuggestionSource {
    override fun suggest(word: CharSequence): List<Suggestion> = emptyList()
}

/**
 * Slot geometry for the strip, kept out of the view so it can be tested without
 * a `Context`.
 *
 * The strip always has [CAPACITY] equal slots whether or not they are occupied.
 * A suggestion therefore never slides sideways when another one appears or
 * disappears, which matters for the same reason D16 nails down the layer
 * toggle: a target that moves under the thumb between the look and the tap gets
 * mis-hit. The cost is that a single suggestion sits in the left third rather
 * than centred.
 */
object SuggestionSlots {

    /** How many candidates the strip can show at once. */
    const val CAPACITY = 3

    fun left(width: Float, index: Int): Float = width * index / CAPACITY

    fun right(width: Float, index: Int): Float = width * (index + 1) / CAPACITY

    /** The slot under [x], or -1 outside the strip. Occupancy is not considered. */
    fun indexAt(width: Float, x: Float): Int {
        if (width <= 0f || x < 0f || x >= width) return -1
        return ((x / width) * CAPACITY).toInt().coerceIn(0, CAPACITY - 1)
    }
}
