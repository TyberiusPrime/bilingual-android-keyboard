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
 * A replacement the keyboard is prepared to make on its own (D3, D28).
 *
 * [confidence] is what the whole thing turns on, and unlike the strip's
 * ranking it is meant to be acted upon: above the threshold the word is
 * replaced without being asked. It carries [original] because a replacement
 * that cannot be undone is not one that should be made (D14).
 */
data class Correction(
    val text: String,
    val original: String,
    val confidence: Float,
    val language: Language?,
)

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

    /**
     * The best replacement for a finished word, with how sure it is.
     *
     * [touches] carries where the thumb actually landed for each character
     * (D28); without it there is no way to tell a slip from a decision, so a
     * source that is given nothing should be correspondingly unsure. Returning
     * a correction is not the same as applying one — the threshold that decides
     * that is the user's (D3).
     */
    fun correct(typed: String, touches: List<TypedTouch>): Correction? = null
}

/**
 * Nothing to offer. Used while the wordlists are still being read off disk, so
 * the strip is empty for the first moment of a session rather than absent.
 */
object NoSuggestions : SuggestionSource {
    override fun suggest(word: CharSequence): List<Suggestion> = emptyList()
}

/**
 * How a slot was chosen, which decides what happens to the space after it.
 *
 * German runs words together — `Haus` plus `tür` is one word, not two — so the
 * strip has to be able to hand a word over without ending it (D26).
 */
enum class PickStyle {
    /** A tap: the word is finished, and a space follows it. */
    SPACED,

    /** A long press: the word is a piece of a longer one, so nothing follows it. */
    JOINED,
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

    /**
     * A candidate. Tapping it replaces the word in progress.
     *
     * [replaces] means **pressing space right now would substitute this word**
     * — not "this one scores well". It is the strip's purple, and it is set by
     * asking the corrector the same question space asks, rather than by
     * comparing a separate number against a separate threshold (D33).
     */
    data class Word(val suggestion: Suggestion, val replaces: Boolean = false) : StripEntry {
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

    companion object {

        /**
         * Turns ranked candidates into strip entries, marking the one that
         * pressing space would substitute and making sure it is on screen (D33).
         *
         * Ranking and correcting are different questions, so the corrector's
         * answer is not always the first candidate — and it is occasionally not
         * among them at all. Showing it anyway, in front, is the point of the
         * exercise: the purple word is a warning about the next keystroke, and a
         * warning that is sometimes absent is not one.
         */
        fun mark(suggestions: List<Suggestion>, correction: Correction?): List<Word> {
            if (correction == null) return suggestions.map { Word(it) }
            // The first match, not every match: one word is going to be
            // substituted, so one word is purple.
            val chosen = suggestions.indexOfFirst { it.text == correction.text }
            if (chosen >= 0) {
                return suggestions.mapIndexed { index, it -> Word(it, replaces = index == chosen) }
            }
            return listOf(
                Word(
                    Suggestion(correction.text, correction.confidence, correction.language),
                    replaces = true,
                ),
            ) + suggestions.map { Word(it) }
        }
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
