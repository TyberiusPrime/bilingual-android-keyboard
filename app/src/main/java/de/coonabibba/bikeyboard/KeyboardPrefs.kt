package de.coonabibba.bikeyboard

import android.content.Context
import android.content.SharedPreferences
import android.util.TypedValue
import kotlin.math.max

/**
 * Everything about the keyboard that is a matter of taste, in one place.
 *
 * Two kinds of thing end up here. **Sizes and timings**, because every guess at
 * them from a laptop has been wrong on the phone — the suggestion text was fine
 * print and then shouting, and a long-press delay that feels right to one thumb
 * is a stutter to another. And **the auto-correction threshold**, because D3
 * says in as many words that it must be tunable.
 *
 * Shared between the keyboard service and the settings screen, which live in
 * one process, so a change is visible immediately. The views bake their values
 * in when they are built, so [revision] exists to tell the service that
 * something changed and the input view has to be made again.
 */
object KeyboardPrefs {

    // -- appearance ----------------------------------------------------------

    const val SUGGESTION_TEXT_SP = "suggestion_text_sp"
    const val DEFAULT_TEXT_SP = 20
    const val MIN_TEXT_SP = 12
    const val MAX_TEXT_SP = 32

    // -- timings, all in milliseconds ----------------------------------------

    /** How long a key must be held before its alternates appear (D5). */
    const val KEY_LONG_PRESS_MS = "key_long_press_ms"
    const val DEFAULT_KEY_LONG_PRESS_MS = 280

    /** How long a suggestion must be held to be joined rather than spaced (D26). */
    const val SUGGESTION_LONG_PRESS_MS = "suggestion_long_press_ms"
    const val DEFAULT_SUGGESTION_LONG_PRESS_MS = 400

    /** How long backspace must be held before it starts repeating (D20). */
    const val REPEAT_DELAY_MS = "repeat_delay_ms"
    const val DEFAULT_REPEAT_DELAY_MS = 400

    /** How fast it repeats once it has started. */
    const val REPEAT_INTERVAL_MS = "repeat_interval_ms"
    const val DEFAULT_REPEAT_INTERVAL_MS = 55

    /** The window in which a second tap is part of the same gesture (D6, D24). */
    const val DOUBLE_TAP_MS = "double_tap_ms"
    const val DEFAULT_DOUBLE_TAP_MS = 350

    /** How long the correction flash takes to cross the keyboard (D28). */
    const val FLASH_MS = "flash_ms"
    const val DEFAULT_FLASH_MS = 650

    /** Every timing, with the range the settings screen offers for it. */
    val TIMINGS: List<Timing> = listOf(
        Timing(KEY_LONG_PRESS_MS, DEFAULT_KEY_LONG_PRESS_MS, 120, 700, R.string.setting_key_long_press),
        Timing(
            SUGGESTION_LONG_PRESS_MS,
            DEFAULT_SUGGESTION_LONG_PRESS_MS,
            200,
            900,
            R.string.setting_suggestion_long_press,
        ),
        Timing(REPEAT_DELAY_MS, DEFAULT_REPEAT_DELAY_MS, 150, 900, R.string.setting_repeat_delay),
        Timing(REPEAT_INTERVAL_MS, DEFAULT_REPEAT_INTERVAL_MS, 20, 200, R.string.setting_repeat_interval),
        Timing(DOUBLE_TAP_MS, DEFAULT_DOUBLE_TAP_MS, 150, 700, R.string.setting_double_tap),
        Timing(FLASH_MS, DEFAULT_FLASH_MS, 0, 1_000, R.string.setting_flash),
    )

    class Timing(
        val key: String,
        val default: Int,
        val min: Int,
        val max: Int,
        val label: Int,
    )

    // -- correction ----------------------------------------------------------

    /** Whether the keyboard may replace a word without being asked (D3, D28). */
    const val AUTO_CORRECT = "auto_correct"
    const val DEFAULT_AUTO_CORRECT = true

    /**
     * How sure it has to be, as a percentage.
     *
     * D8 argues this should start conservative: nothing here learns from being
     * wrong, so a bad replacement stays wrong until the word is added by hand.
     */
    const val AUTO_CORRECT_CONFIDENCE = "auto_correct_confidence"
    const val DEFAULT_AUTO_CORRECT_CONFIDENCE = 90
    const val MIN_CONFIDENCE = 50
    const val MAX_CONFIDENCE = 99

    // -- haptics -------------------------------------------------------------

    /** How hard the keyboard buzzes, if at all (D29). */
    const val HAPTICS = "haptics"

    enum class HapticLevel { OFF, LIGHT, STRONG }

    // -- reading and writing --------------------------------------------------

    private const val REVISION = "revision"

    fun of(context: Context): SharedPreferences =
        context.getSharedPreferences("keyboard", Context.MODE_PRIVATE)

    /**
     * Bumped on every write. The input view reads its settings once when it is
     * built, so this is how the service knows to build it again.
     */
    fun revision(context: Context): Int = of(context).getInt(REVISION, 0)

    fun putInt(context: Context, key: String, value: Int) {
        val prefs = of(context)
        prefs.edit()
            .putInt(key, value)
            .putInt(REVISION, prefs.getInt(REVISION, 0) + 1)
            .apply()
    }

    fun putBoolean(context: Context, key: String, value: Boolean) {
        val prefs = of(context)
        prefs.edit()
            .putBoolean(key, value)
            .putInt(REVISION, prefs.getInt(REVISION, 0) + 1)
            .apply()
    }

    fun suggestionTextSp(context: Context): Int =
        of(context).getInt(SUGGESTION_TEXT_SP, DEFAULT_TEXT_SP)
            .coerceIn(MIN_TEXT_SP, MAX_TEXT_SP)

    /** A timing in milliseconds, clamped to the range the settings screen offers. */
    fun timing(context: Context, timing: Timing): Long =
        of(context).getInt(timing.key, timing.default)
            .coerceIn(timing.min, timing.max)
            .toLong()

    fun timing(context: Context, key: String): Long =
        timing(context, TIMINGS.first { it.key == key })

    fun autoCorrect(context: Context): Boolean =
        of(context).getBoolean(AUTO_CORRECT, DEFAULT_AUTO_CORRECT)

    /** The confidence an auto-correction needs, as a fraction. */
    fun autoCorrectConfidence(context: Context): Float =
        of(context).getInt(AUTO_CORRECT_CONFIDENCE, DEFAULT_AUTO_CORRECT_CONFIDENCE)
            .coerceIn(MIN_CONFIDENCE, MAX_CONFIDENCE) / 100f

    fun haptics(context: Context): HapticLevel =
        runCatching {
            HapticLevel.valueOf(
                of(context).getString(HAPTICS, HapticLevel.LIGHT.name) ?: HapticLevel.LIGHT.name,
            )
        }.getOrDefault(HapticLevel.LIGHT)

    fun setHaptics(context: Context, level: HapticLevel) {
        val prefs = of(context)
        prefs.edit()
            .putString(HAPTICS, level.name)
            .putInt(REVISION, prefs.getInt(REVISION, 0) + 1)
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
