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
data class Suggestion(
    val text: String,
    val confidence: Float,
    /**
     * Which wordlist the candidate came from, or `null` for the personal store,
     * which belongs to no language.
     *
     * This is D4's language indicator, and the strip is the right place for it:
     * it says what the keyboard believes *per candidate* rather than declaring a
     * mode, and nothing reads it back. Per D2 language is a property of a word,
     * not of the keyboard, so there is nowhere else it could honestly live.
     */
    val language: Language?,
)

/** The languages the keyboard carries. Neither is the fallback (D1). */
enum class Language { GERMAN, ENGLISH }

/**
 * Where the strip's contents come from.
 *
 * **This signature is provisional.** The architecture in `docs/design.md` has
 * the candidate set produced from a *sequence of touch points* (D7, D15) and
 * scored in sentence context by one multilingual model (D2, D12); a prefix of
 * committed characters is strictly less than that. It is what the keyboard
 * actually knows today, and widening it later touches only this file, the
 * service's [BilingualKeyboardService.refreshSuggestions] and the source
 * implementation.
 */
interface SuggestionSource {

    /**
     * Candidates for the word currently being typed, best first.
     *
     * [word] is the partial word before the cursor, empty when the cursor sits
     * at a word boundary — in which case anything returned is a next-word
     * prediction rather than a correction (D9 says the strip carries both).
     */
    fun suggest(word: CharSequence): List<Suggestion>

    /**
     * Whether [word] is one this source recognises.
     *
     * Drives the add-word offer (D8): a word nothing has heard of is one worth
     * asking about. A source that cannot answer should claim to know
     * everything, so that it never offers to add a word it would then be unable
     * to store.
     */
    fun knows(word: CharSequence): Boolean = true
}

/**
 * Nothing to offer. Used while the wordlists are still being read off disk, so
 * the strip is empty for the first moment of a session rather than absent.
 */
object NoSuggestions : SuggestionSource {
    override fun suggest(word: CharSequence): List<Suggestion> = emptyList()
}

/**
 * What one slot of the strip holds.
 *
 * Two kinds, because the strip does two jobs: it offers words, and it is where
 * the user's only feedback channel lives (D8, D14).
 */
sealed interface StripEntry {

    /** What the strip draws. */
    val label: String

    /** A candidate. Tapping it replaces the word in progress. */
    data class Word(val suggestion: Suggestion) : StripEntry {
        override val label: String get() = suggestion.text
    }

    /**
     * An offer to remember a word nothing recognises. Tapping it adds the word
     * to the personal store and changes nothing in the text — the word is
     * already typed; what is missing is the keyboard knowing it.
     */
    data class AddWord(val word: String) : StripEntry {
        override val label: String get() = "+ $word"
    }
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
