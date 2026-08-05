package de.coonabibba.bikeyboard

import android.content.Context
import android.content.SharedPreferences
import android.util.TypedValue
import kotlin.math.max

/**
 * The handful of things about the keyboard that are a matter of taste.
 *
 * Only one so far, and it is here because two guesses at it were wrong in
 * opposite directions: the suggestion text was fine print at 16sp and shouting
 * at 26sp, and neither of those is a fact anyone can settle from a laptop. A
 * number that depends on eyesight, screen and font scale belongs to whoever is
 * reading it.
 *
 * Shared between the keyboard service and the settings screen, which live in
 * one process, so a change is visible to the keyboard as soon as it is written.
 */
object KeyboardPrefs {

    /** How large the suggestion strip's text is, in sp. */
    const val SUGGESTION_TEXT_SP = "suggestion_text_sp"

    const val DEFAULT_TEXT_SP = 20
    const val MIN_TEXT_SP = 12
    const val MAX_TEXT_SP = 32

    fun of(context: Context): SharedPreferences =
        context.getSharedPreferences("keyboard", Context.MODE_PRIVATE)

    fun suggestionTextSp(context: Context): Int =
        of(context).getInt(SUGGESTION_TEXT_SP, DEFAULT_TEXT_SP)
            .coerceIn(MIN_TEXT_SP, MAX_TEXT_SP)

    fun setSuggestionTextSp(context: Context, sp: Int) {
        of(context).edit()
            .putInt(SUGGESTION_TEXT_SP, sp.coerceIn(MIN_TEXT_SP, MAX_TEXT_SP))
            .apply()
    }

    /** The suggestion text size in pixels, honouring the system font scale. */
    fun suggestionTextPx(context: Context): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        suggestionTextSp(context).toFloat(),
        context.resources.displayMetrics,
    )

    /**
     * The height of the band above the keys.
     *
     * Follows the text rather than being fixed, so choosing larger suggestions
     * makes room for them instead of clipping them — but never drops below the
     * minimum, because the band is also the headroom a long-press popup on the
     * top row overhangs into (D21).
     */
    fun stripHeightPx(context: Context): Float = max(
        context.resources.getDimension(R.dimen.suggestion_strip_min_height),
        suggestionTextPx(context) * BAND_TO_TEXT,
    )

    /** Enough room above and below the tallest letters to not look wedged in. */
    private const val BAND_TO_TEXT = 1.9f
}
