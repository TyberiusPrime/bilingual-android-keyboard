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
    val TIMINGS: List<Range> = listOf(
        Range(KEY_LONG_PRESS_MS, DEFAULT_KEY_LONG_PRESS_MS, 120, 700, R.string.setting_key_long_press),
        Range(
            SUGGESTION_LONG_PRESS_MS,
            DEFAULT_SUGGESTION_LONG_PRESS_MS,
            200,
            900,
            R.string.setting_suggestion_long_press,
        ),
        Range(REPEAT_DELAY_MS, DEFAULT_REPEAT_DELAY_MS, 150, 900, R.string.setting_repeat_delay),
        Range(REPEAT_INTERVAL_MS, DEFAULT_REPEAT_INTERVAL_MS, 20, 200, R.string.setting_repeat_interval),
        Range(DOUBLE_TAP_MS, DEFAULT_DOUBLE_TAP_MS, 150, 700, R.string.setting_double_tap),
    )

    /**
     * A setting the screen renders as a slider: what it is called, what it
     * defaults to, and how far it may be pushed.
     */
    class Range(
        val key: String,
        val default: Int,
        val min: Int,
        val max: Int,
        val label: Int,
    )

    // -- the shape of the correction flash (D28, D30) -------------------------

    /**
     * How opaque the wave is at its brightest, out of 255.
     *
     * 150 was invisible, 230 was described as "better, but the wave could be
     * more powerful", and the ceiling is the whole 255 — because the honest
     * position is that nobody here can judge this from a laptop, and the range
     * has to include the answer.
     */
    const val FLASH_ALPHA = "flash_alpha"
    const val DEFAULT_FLASH_ALPHA = 255

    /** What fraction of the rise runs at full strength before it starts fading. */
    const val FLASH_HOLD = "flash_hold"
    const val DEFAULT_FLASH_HOLD = 60

    /** How far up the keys the wave front climbs, as a percentage of the way. */
    const val FLASH_REACH = "flash_reach"
    const val DEFAULT_FLASH_REACH = 100

    /**
     * How long the tail behind the wave front is, as a percentage.
     *
     * Zero is a hard edge — a bar of colour sweeping up. A hundred is a gradient
     * that fades all the way back to the space bar. The difference between "a
     * wave" and "the keyboard lit up" is mostly this.
     */
    const val FLASH_SOFTNESS = "flash_softness"
    const val DEFAULT_FLASH_SOFTNESS = 45

    /** Everything about the flash, in the order the settings screen shows it. */
    val FLASH: List<Range> = listOf(
        Range(FLASH_MS, DEFAULT_FLASH_MS, 0, 2_000, R.string.setting_flash),
        Range(FLASH_ALPHA, DEFAULT_FLASH_ALPHA, 0, 255, R.string.setting_flash_alpha),
        Range(FLASH_HOLD, DEFAULT_FLASH_HOLD, 0, 100, R.string.setting_flash_hold),
        Range(FLASH_REACH, DEFAULT_FLASH_REACH, 10, 100, R.string.setting_flash_reach),
        Range(FLASH_SOFTNESS, DEFAULT_FLASH_SOFTNESS, 0, 95, R.string.setting_flash_softness),
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

    /**
     * How sharply confidence falls away as the thumb sits further from the key
     * the word needed (D34), in tenths.
     *
     * The companion to the threshold, and the one that decides *shape* rather
     * than height. Low is forgiving — a correction still fires when the touch
     * was half a key out; high is fussy, and only a graze counts. Two knobs
     * because they are genuinely two questions, and measuring showed that the
     * threshold alone moves the boundary without changing how abruptly it
     * arrives.
     */
    const val CONFIDENCE_FALLOFF = "confidence_falloff"
    const val DEFAULT_CONFIDENCE_FALLOFF = 70

    /** The threshold and the falloff, the two sliders that shape a correction. */
    val CORRECTION: List<Range> = listOf(
        Range(
            AUTO_CORRECT_CONFIDENCE,
            DEFAULT_AUTO_CORRECT_CONFIDENCE,
            MIN_CONFIDENCE,
            MAX_CONFIDENCE,
            R.string.setting_confidence,
        ),
        Range(CONFIDENCE_FALLOFF, DEFAULT_CONFIDENCE_FALLOFF, 10, 200, R.string.setting_falloff),
    )

    /** The falloff as the scorer wants it: a rate, not tenths of one. */
    fun confidenceFalloff(context: Context): Float =
        value(context, CORRECTION.first { it.key == CONFIDENCE_FALLOFF }) / 10f

    // -- haptics -------------------------------------------------------------

    /** The single level this used to have, kept only so old settings survive. */
    const val HAPTICS = "haptics"

    enum class HapticLevel { OFF, LIGHT, STRONG }

    /**
     * The two things the keyboard has to say, each with its own level (D29).
     *
     * Separate because they are not the same message and people do not want
     * them in the same amount. A keypress tick is texture — hundreds a minute,
     * and plenty of typists want none of it. A correction is *news*: the
     * keyboard changed a word unasked and the undo window is open. Wanting
     * silence while typing and a firm knock when something is replaced is an
     * entirely coherent position, and one shared slider could not express it.
     *
     * The defaults encode D29's original point that the two must not feel the
     * same: light for keys, strong for corrections.
     */
    enum class HapticEvent(val key: String, val default: HapticLevel) {
        KEY_PRESS("haptics_key_press", HapticLevel.LIGHT),
        CORRECTION("haptics_correction", HapticLevel.STRONG),
    }

    /** Both levels at once, which is how [Haptics] wants them. */
    data class HapticLevels(val keyPress: HapticLevel, val correction: HapticLevel) {
        fun forEvent(event: HapticEvent): HapticLevel = when (event) {
            HapticEvent.KEY_PRESS -> keyPress
            HapticEvent.CORRECTION -> correction
        }
    }

    /**
     * *Which way* the keyboard asks the phone to buzz.
     *
     * This should not have to be a setting, and on a phone where the obvious
     * path works it is never touched. It exists because Android offers four
     * different ways to ask for the same tick, each gated by a different
     * combination of system settings and hardware support, and none of them
     * reports back whether anything actually moved. When the answer is "no
     * vibration at all", the only way to find out which gate is shut is to try
     * each door — so the settings screen offers them one at a time, and
     * whichever one is felt can be kept (D29).
     */
    const val HAPTIC_ROUTE = "haptic_route"

    enum class HapticRoute {
        /** View feedback, then a predefined effect, then a plain pulse. */
        AUTO,

        /** `View.performHapticFeedback`, the path every stock keyboard takes. */
        VIEW,

        /** `VibrationEffect.createPredefined`, tuned per device by the vendor. */
        PREDEFINED,

        /** A plain pulse of our own length, tagged as touch feedback. */
        PULSE,

        /**
         * A plain pulse tagged as something the system may not silence.
         *
         * The blunt instrument, and the useful diagnostic: touch-feedback
         * haptics are scaled — to nothing, if the system slider says so — while
         * this route is not. If this one is felt and the others are not, the
         * phone is working and a system setting is switched off.
         */
        INSISTENT,
    }

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

    /** A setting's current value, clamped to the range the screen offers for it. */
    fun value(context: Context, range: Range): Int =
        of(context).getInt(range.key, range.default).coerceIn(range.min, range.max)

    fun value(context: Context, key: String): Int =
        value(context, (TIMINGS + FLASH + CORRECTION).first { it.key == key })

    /** A timing in milliseconds, clamped the same way. */
    fun timing(context: Context, range: Range): Long = value(context, range).toLong()

    fun timing(context: Context, key: String): Long = value(context, key).toLong()

    fun autoCorrect(context: Context): Boolean =
        of(context).getBoolean(AUTO_CORRECT, DEFAULT_AUTO_CORRECT)

    /** The confidence an auto-correction needs, as a fraction. */
    fun autoCorrectConfidence(context: Context): Float =
        of(context).getInt(AUTO_CORRECT_CONFIDENCE, DEFAULT_AUTO_CORRECT_CONFIDENCE)
            .coerceIn(MIN_CONFIDENCE, MAX_CONFIDENCE) / 100f

    /**
     * How hard to buzz for one kind of event.
     *
     * Falls back to the old single-level setting before the default, so that
     * splitting this in two did not silently reset anybody's choice — someone
     * who had set Strong keeps Strong for both until they say otherwise.
     */
    fun haptics(context: Context, event: HapticEvent): HapticLevel {
        val prefs = of(context)
        val name = prefs.getString(event.key, null)
            ?: prefs.getString(HAPTICS, null)
            ?: event.default.name
        return runCatching { HapticLevel.valueOf(name) }.getOrDefault(event.default)
    }

    fun haptics(context: Context): HapticLevels = HapticLevels(
        keyPress = haptics(context, HapticEvent.KEY_PRESS),
        correction = haptics(context, HapticEvent.CORRECTION),
    )

    fun setHaptics(context: Context, event: HapticEvent, level: HapticLevel) {
        val prefs = of(context)
        prefs.edit()
            .putString(event.key, level.name)
            .putInt(REVISION, prefs.getInt(REVISION, 0) + 1)
            .apply()
    }

    fun hapticRoute(context: Context): HapticRoute =
        runCatching {
            HapticRoute.valueOf(
                of(context).getString(HAPTIC_ROUTE, HapticRoute.AUTO.name) ?: HapticRoute.AUTO.name,
            )
        }.getOrDefault(HapticRoute.AUTO)

    fun setHapticRoute(context: Context, route: HapticRoute) {
        val prefs = of(context)
        prefs.edit()
            .putString(HAPTIC_ROUTE, route.name)
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
