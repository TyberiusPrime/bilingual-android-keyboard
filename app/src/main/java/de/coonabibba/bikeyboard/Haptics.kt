package de.coonabibba.bikeyboard

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
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
 * ## Why this is more complicated than it should be
 *
 * Two rounds on the phone have now produced no vibration at all, and the reason
 * it is hard to fix blind is that **nothing here reports back**. Every API below
 * is fire-and-forget: `vibrate` returns `Unit`, and the one call that returns a
 * boolean returns it about whether the *view* accepted the request, not about
 * whether the motor moved. There are at least four gates between this code and
 * the hardware — the app's permission, the system touch-feedback switch, the
 * per-usage intensity slider, and whether the device implements the particular
 * effect asked for — and a shut gate looks exactly like a working keyboard on a
 * phone with a dead motor.
 *
 * So this class stops guessing and offers the routes separately, with
 * [diagnose] reporting what the phone will admit to. The settings screen fires
 * one route per button; whichever is felt becomes the setting. [KeyboardPrefs.HapticRoute.INSISTENT]
 * is the load-bearing one for diagnosis: it asks with a usage the system does
 * *not* scale down to nothing, so if it buzzes and the others do not, the answer
 * is a system setting rather than this code.
 */
class Haptics(
    private val context: Context,
    private val level: KeyboardPrefs.HapticLevel,
    /**
     * Deliberately without a default. The route was a setting that nothing read
     * for one whole round — the settings screen's test buttons named a route
     * explicitly and worked, while the keyboard and the level buttons quietly
     * took the default and stayed silent. A parameter that can be forgotten will
     * be; [fromPrefs] is how callers should get one anyway.
     */
    private val route: KeyboardPrefs.HapticRoute,
) {

    companion object {
        /** Both settings, read together, which is the only way they are correct. */
        fun fromPrefs(context: Context): Haptics = Haptics(
            context,
            KeyboardPrefs.haptics(context),
            KeyboardPrefs.hapticRoute(context),
        )

        private const val LIGHT_MS = 25L
        private const val STRONG_MS = 55L

        /** The gap between the two knocks of a correction. */
        private const val CORRECTION_GAP_MS = 70L

        /** Out of 255. Both are high, because the system scales them down again. */
        private const val LIGHT_AMPLITUDE = 160
        private const val STRONG_AMPLITUDE = 255
    }

    /**
     * Resolved on first use, never in the constructor.
     *
     * **This is load-bearing.** An earlier version looked the vibrator up in a
     * field initialiser, which was survivable only by accident: the first branch
     * of the lookup returned null for [KeyboardPrefs.HapticLevel.OFF] without
     * touching the context, and the service happened to build its placeholder at
     * that level. Removing that branch turned the same line into a crash on
     * every launch, because a `Service` field initialiser runs before
     * `attachBaseContext` and `getSystemService` on a context with no base is a
     * null dereference.
     *
     * Being lazy makes the hazard structural rather than incidental: there is no
     * level, and no caller, for which constructing this object can touch the
     * system.
     */
    private val vibrator: Vibrator? by lazy(LazyThreadSafetyMode.NONE) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
        }.getOrNull()?.takeIf { it.hasVibrator() }
    }

    /** False when the phone has no vibrator at all, which the settings screen says out loud. */
    val available: Boolean get() = vibrator != null

    /**
     * A tick under a key.
     *
     * [view] is only needed by [KeyboardPrefs.HapticRoute.VIEW]; the other
     * routes drive the motor directly and ignore it.
     */
    fun keyPress(view: View?) {
        if (level == KeyboardPrefs.HapticLevel.OFF) return
        fire(route, view, correction = false)
    }

    /** Two knocks: something was changed for you, and you have one keystroke to say no. */
    fun correction() {
        if (level == KeyboardPrefs.HapticLevel.OFF) return
        fire(route, view = null, correction = true)
    }

    /**
     * Plays one route, or tries them in order for [KeyboardPrefs.HapticRoute.AUTO].
     *
     * Public so the settings screen can fire a named route on demand: that is
     * the whole test bench, and it has to go through the same code the keyboard
     * uses or it proves nothing.
     */
    fun fire(route: KeyboardPrefs.HapticRoute, view: View?, correction: Boolean) {
        when (route) {
            KeyboardPrefs.HapticRoute.AUTO -> {
                // The stock path first — it is what every other keyboard on the
                // phone feels like, and it is already tuned. A correction has no
                // view-feedback constant meaning "two knocks", so it goes
                // straight to the motor.
                if (!correction && viewFeedback(view)) return
                if (predefined(correction)) return
                pulse(correction, VibrationAttributes.USAGE_TOUCH)
            }

            KeyboardPrefs.HapticRoute.VIEW -> viewFeedback(view)
            KeyboardPrefs.HapticRoute.PREDEFINED -> predefined(correction)
            KeyboardPrefs.HapticRoute.PULSE -> pulse(correction, VibrationAttributes.USAGE_TOUCH)
            // USAGE_ALARM is not scaled by the touch-feedback intensity slider,
            // which is exactly the point: this is the route that answers "is the
            // motor alive at all".
            KeyboardPrefs.HapticRoute.INSISTENT ->
                pulse(correction, VibrationAttributes.USAGE_ALARM)
        }
    }

    private fun viewFeedback(view: View?): Boolean {
        val target = view ?: return false
        val constant = if (level == KeyboardPrefs.HapticLevel.STRONG) {
            HapticFeedbackConstants.LONG_PRESS
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        }
        return target.performHapticFeedback(
            constant,
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
        )
    }

    /**
     * The vendor's own tuned effect, where the device has one.
     *
     * False when it does not, which is the trap the previous version fell into:
     * `createPredefined` for an unsupported effect is allowed to do nothing at
     * all, so making it the only path can be worse than the plain pulse it
     * replaced.
     */
    private fun predefined(correction: Boolean): Boolean {
        val vibrator = vibrator ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val effect = when {
            correction -> VibrationEffect.EFFECT_DOUBLE_CLICK
            level == KeyboardPrefs.HapticLevel.STRONG -> VibrationEffect.EFFECT_HEAVY_CLICK
            else -> VibrationEffect.EFFECT_TICK
        }
        if (supportsEffect(vibrator, effect) == false) return false
        send(vibrator, VibrationEffect.createPredefined(effect), VibrationAttributes.USAGE_TOUCH)
        return true
    }

    private fun pulse(correction: Boolean, usage: Int) {
        val vibrator = vibrator ?: return
        val ms = tickMs()
        val effect = if (correction) {
            VibrationEffect.createWaveform(
                longArrayOf(0, ms, CORRECTION_GAP_MS, ms),
                intArrayOf(0, amplitude(), 0, amplitude()),
                -1,
            )
        } else {
            VibrationEffect.createOneShot(ms, amplitude())
        }
        send(vibrator, effect, usage)
    }

    private fun send(vibrator: Vibrator, effect: VibrationEffect, usage: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(usage))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, audioAttributesFor(usage))
        }
    }

    /**
     * A plain pulse is longer than a predefined tick because it has to be: the
     * device effects are shaped waveforms, and a flat request needs duration to
     * be felt at all. Twelve milliseconds, the first attempt, is below what a
     * linear resonant actuator can answer.
     */
    private fun tickMs(): Long = when (level) {
        KeyboardPrefs.HapticLevel.STRONG -> STRONG_MS
        else -> LIGHT_MS
    }

    private fun amplitude(): Int = when (level) {
        KeyboardPrefs.HapticLevel.OFF -> 0
        // Not a fraction of full any more. Light was at 120/255 and reported as
        // nothing; on a phone whose motor is already being scaled down by a
        // system slider, asking for half is asking for nothing.
        KeyboardPrefs.HapticLevel.LIGHT -> LIGHT_AMPLITUDE
        KeyboardPrefs.HapticLevel.STRONG -> STRONG_AMPLITUDE
    }

    /**
     * What the phone will admit to, as label-and-value pairs for the settings
     * screen.
     *
     * Every line here is a gate that can independently produce silence, and the
     * point of printing them together is that the combination usually names the
     * culprit outright: a motor that exists, touch feedback switched off, and a
     * touch intensity of zero is not a bug in this code.
     */
    fun diagnose(): List<Pair<String, String>> {
        val vibrator = vibrator
        val lines = mutableListOf<Pair<String, String>>()
        lines += "Motor" to if (vibrator == null) "none reported" else "present"
        if (vibrator == null) return lines

        lines += "Amplitude control" to if (vibrator.hasAmplitudeControl()) "yes" else "no"
        lines += "System touch feedback" to when (systemTouchFeedback()) {
            null -> "unreadable"
            true -> "on"
            false -> "OFF — this alone silences every route but Insistent"
        }
        lines += "Touch intensity" to intensityName(touchIntensity())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lines += "Tick effect" to supportName(supportsEffect(vibrator, VibrationEffect.EFFECT_TICK))
            lines += "Double-click effect" to
                supportName(supportsEffect(vibrator, VibrationEffect.EFFECT_DOUBLE_CLICK))
        }
        return lines
    }

    /**
     * The conclusion the diagnosis supports, or null when the numbers do not
     * name a culprit.
     *
     * A list of readings is data; this is the sentence the reader actually
     * wants. It exists because the reading that matters here — touch feedback
     * muted at the system level while the motor is fine — is invisible unless
     * someone knows that "Insistent" and "USAGE_ALARM" are the same thing.
     */
    fun verdict(): String? {
        if (vibrator == null) return null
        val muted = systemTouchFeedback() == false || touchIntensity() == 0
        if (!muted) return null
        return "The motor works, but this phone has touch feedback switched off " +
            "system-wide, which silences every route except Insistent. Either " +
            "turn touch feedback back on in the system settings, or keep " +
            "Insistent selected above — note that alarm-strength vibration can " +
            "still be suppressed by Do Not Disturb."
    }

    /**
     * Whether the system-wide touch-feedback switch is on.
     *
     * Null when it cannot be read. This gates `performHapticFeedback` outright
     * and, on newer releases, everything tagged as touch feedback — so it is the
     * single likeliest reason for "no vibration and no error message".
     */
    private fun systemTouchFeedback(): Boolean? = runCatching {
        // Deprecated as a *write* target; it is still what the platform reads
        // when it decides whether to honour touch feedback, and there is no
        // replacement getter.
        @Suppress("DEPRECATION")
        Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED) != 0
    }.getOrNull()

    /**
     * The system's haptic strength slider, 0 (off) to 3 (high), or -1 unknown.
     *
     * Read by name rather than through `Vibrator`, whose public getter for this
     * only arrived in API 35 — and the setting itself has existed, under this
     * name, for far longer than the getter. A value of zero scales every
     * touch-tagged vibration to nothing, which is one of the two states that
     * produce exactly the symptom being chased here.
     */
    private fun touchIntensity(): Int = runCatching {
        Settings.System.getInt(context.contentResolver, "haptic_feedback_intensity")
    }.getOrDefault(-1)

    /** True, false, or null where the platform is too old to be asked. */
    private fun supportsEffect(vibrator: Vibrator, effect: Int): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return when (vibrator.areEffectsSupported(effect).firstOrNull()) {
            Vibrator.VIBRATION_EFFECT_SUPPORT_YES -> true
            Vibrator.VIBRATION_EFFECT_SUPPORT_NO -> false
            else -> null
        }
    }

    private fun supportName(value: Boolean?): String = when (value) {
        true -> "supported"
        false -> "not supported — falls back to a plain pulse"
        null -> "unknown"
    }

    private fun intensityName(intensity: Int): String = when (intensity) {
        0 -> "OFF — touch haptics are scaled to nothing"
        1 -> "low"
        2 -> "medium"
        3 -> "high"
        else -> "unreadable"
    }

    private fun audioAttributesFor(usage: Int): AudioAttributes = AudioAttributes.Builder()
        .setUsage(
            if (usage == VibrationAttributes.USAGE_ALARM) {
                AudioAttributes.USAGE_ALARM
            } else {
                AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
            },
        )
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

}
