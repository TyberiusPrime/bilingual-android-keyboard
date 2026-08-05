package de.coonabibba.bikeyboard

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View

/**
 * The keyboard's sense of touch (D29).
 *
 * Two things to say and they must not feel the same. A **keypress** is a single
 * short tick, the shortest the hardware will honour, because it happens
 * hundreds of times a minute and anything longer becomes a buzz. An **applied
 * correction** is two: the keyboard changed a word without being asked, and
 * that deserves to be noticeable while the thumb is still moving — it is the
 * only warning that the undo window (D14) is open.
 *
 * Three levels rather than a switch, because "on" means something different on
 * every phone, and because the strong setting is what makes the two patterns
 * distinguishable by feel alone.
 */
class Haptics(context: Context, private val level: KeyboardPrefs.HapticLevel) {

    private val vibrator: Vibrator? = when {
        level == KeyboardPrefs.HapticLevel.OFF -> null
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val manager = context.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        }

        else -> @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
    }?.takeIf { it.hasVibrator() }

    /**
     * A tick under a key.
     *
     * [view] is a fallback: when the hardware will not take an amplitude, the
     * platform's own keyboard-tap feedback is better than nothing and is what
     * every other keyboard on the phone feels like.
     */
    fun keyPress(view: View?) {
        val vibrator = vibrator ?: return
        if (vibrator.hasAmplitudeControl()) {
            vibrator.vibrate(VibrationEffect.createOneShot(TICK_MS, amplitude()))
        } else {
            view?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    /** Two ticks: something was changed for you, and you have one keystroke to say no. */
    fun correction() {
        val vibrator = vibrator ?: return
        val timings = longArrayOf(0, TICK_MS, GAP_MS, CORRECTION_MS)
        if (vibrator.hasAmplitudeControl()) {
            val amplitude = amplitude()
            vibrator.vibrate(
                VibrationEffect.createWaveform(timings, intArrayOf(0, amplitude, 0, amplitude), -1),
            )
        } else {
            vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
        }
    }

    private fun amplitude(): Int = when (level) {
        KeyboardPrefs.HapticLevel.OFF -> 0
        KeyboardPrefs.HapticLevel.LIGHT -> LIGHT_AMPLITUDE
        KeyboardPrefs.HapticLevel.STRONG -> STRONG_AMPLITUDE
    }

    private companion object {
        /** As short as a vibrator will meaningfully do; a keypress is an event, not a buzz. */
        const val TICK_MS = 12L
        const val GAP_MS = 45L
        const val CORRECTION_MS = 22L

        /** Out of 255. Light is a hint; strong is meant to be felt through a case. */
        const val LIGHT_AMPLITUDE = 70
        const val STRONG_AMPLITUDE = 160
    }
}
