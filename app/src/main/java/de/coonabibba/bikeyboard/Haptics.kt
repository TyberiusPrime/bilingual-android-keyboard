package de.coonabibba.bikeyboard

import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * The keyboard's sense of touch (D29).
 *
 * Two things to say and they must not feel the same. A **keypress** is a single
 * tick, because it happens hundreds of times a minute and anything longer
 * becomes a buzz. An **applied correction** is a double tap of the motor: the
 * keyboard changed a word without being asked, and that deserves to be
 * noticeable while the thumb is still moving — it is the only warning that the
 * undo window (D14) is open.
 *
 * The first version of this did nothing at all on a real phone, and the reasons
 * are both worth keeping written down:
 *
 * - **A 12ms pulse is not a feeling.** A linear resonant actuator needs time to
 *   spin up, and asking for twelve milliseconds at a third of full amplitude
 *   moves it imperceptibly. The durations here are longer, and where the
 *   platform offers a *predefined* effect it is used instead, because those are
 *   tuned per device by whoever knows the motor.
 * - **A vibration with no stated purpose can be dropped.** Android routes
 *   haptics by usage; without attributes saying this is touch feedback, the
 *   request competes with ringer and notification settings and may simply be
 *   discarded. Every call here is tagged.
 */
class Haptics(context: Context, private val level: KeyboardPrefs.HapticLevel) {

    private val vibrator: Vibrator? = when {
        level == KeyboardPrefs.HapticLevel.OFF -> null
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator

        else -> @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
    }?.takeIf { it.hasVibrator() }

    /** False when the phone has no vibrator at all, which the settings screen says out loud. */
    val available: Boolean get() = vibrator != null

    /**
     * A tick under a key.
     *
     * The light setting asks the platform for its own keyboard tap first: it is
     * what every other keyboard on the phone feels like, and it is already
     * tuned. Only if the view refuses — or the setting is strong, which the
     * platform effect has no way to express — does this reach for the motor
     * directly.
     */
    fun keyPress(view: View?) {
        if (level == KeyboardPrefs.HapticLevel.OFF) return
        if (level == KeyboardPrefs.HapticLevel.LIGHT && view != null) {
            val played = view.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
            )
            if (played) return
        }
        play(
            predefined = if (level == KeyboardPrefs.HapticLevel.STRONG) {
                VibrationEffect.EFFECT_HEAVY_CLICK
            } else {
                VibrationEffect.EFFECT_TICK
            },
            fallback = VibrationEffect.createOneShot(tickMs(), amplitude()),
        )
    }

    /** Two knocks: something was changed for you, and you have one keystroke to say no. */
    fun correction() {
        if (level == KeyboardPrefs.HapticLevel.OFF) return
        val gap = 60L
        play(
            predefined = VibrationEffect.EFFECT_DOUBLE_CLICK,
            fallback = VibrationEffect.createWaveform(
                longArrayOf(0, tickMs(), gap, tickMs()),
                intArrayOf(0, amplitude(), 0, amplitude()),
                -1,
            ),
        )
    }

    /**
     * Plays the device's own tuned effect where there is one, and a plain pulse
     * where there is not — always tagged as touch feedback so the system routes
     * it like the keyboard feedback it is.
     */
    private fun play(predefined: Int, fallback: VibrationEffect) {
        val vibrator = vibrator ?: return
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(predefined)
        } else {
            fallback
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, TOUCH_FEEDBACK)
        }
    }

    private fun tickMs(): Long = when (level) {
        KeyboardPrefs.HapticLevel.STRONG -> STRONG_MS
        else -> LIGHT_MS
    }

    private fun amplitude(): Int = when (level) {
        KeyboardPrefs.HapticLevel.OFF -> 0
        KeyboardPrefs.HapticLevel.LIGHT -> LIGHT_AMPLITUDE
        KeyboardPrefs.HapticLevel.STRONG -> VibrationEffect.DEFAULT_AMPLITUDE
    }

    private companion object {
        /**
         * Long enough for the motor to actually move. The first attempt at this
         * was 12ms, which on an LRA is a request the hardware answers by doing
         * nothing perceptible at all.
         */
        const val LIGHT_MS = 20L
        const val STRONG_MS = 40L

        /** Out of 255, for the light pulse; strong uses whatever the device calls default. */
        const val LIGHT_AMPLITUDE = 120

        /**
         * Says "this is a response to a touch", which is how the system decides
         * whether to play it at all.
         */
        val TOUCH_FEEDBACK: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}
