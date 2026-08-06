package de.coonabibba.bikeyboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils

/**
 * The shape of the correction flash, as five numbers (D28, D30).
 *
 * Separate from the drawing because it is the thing being tuned: the settings
 * screen builds one of these on every slider move and hands it to a preview,
 * and the keyboard builds one when its input view is made.
 */
data class FlashShape(
    /** How long the whole rise takes. Zero switches the flash off. */
    val durationMs: Long,
    /** How opaque the wave is at its brightest, out of 255. */
    val peakAlpha: Int,
    /** Fraction of the rise spent at full strength before fading begins. */
    val hold: Float,
    /** Fraction of the way up the keys the wave front climbs. */
    val reach: Float,
    /** How long the tail behind the front is, as a fraction. 0 is a hard edge. */
    val softness: Float,
) {
    companion object {
        fun fromPrefs(context: Context): FlashShape = FlashShape(
            durationMs = KeyboardPrefs.timing(context, KeyboardPrefs.FLASH_MS),
            peakAlpha = KeyboardPrefs.value(context, KeyboardPrefs.FLASH_ALPHA),
            hold = KeyboardPrefs.value(context, KeyboardPrefs.FLASH_HOLD) / 100f,
            reach = KeyboardPrefs.value(context, KeyboardPrefs.FLASH_REACH) / 100f,
            softness = KeyboardPrefs.value(context, KeyboardPrefs.FLASH_SOFTNESS) / 100f,
        )
    }
}

/**
 * A wash of colour rising from the space bar towards the top of the keys.
 *
 * The keyboard just changed a word without being asked, and the typist is
 * looking at the text rather than at the keys — so the signal has to be
 * something caught out of the corner of an eye. It starts at the space bar
 * because that is the key that caused it, and it rises because that is the
 * direction of the word it changed.
 *
 * Deliberately not a flash *of* the word: the strip is where words live, and
 * colouring one there would say "here is a suggestion" when the point is that
 * something already happened.
 *
 * Pulled out of the keyboard view so the settings screen can run the identical
 * animation in a preview. Two rounds of "too subtle" from the phone were enough
 * to conclude that this cannot be tuned by editing a constant, rebuilding, and
 * typing a wrong word on purpose to see it.
 */
class CorrectionFlash(var shape: FlashShape, private val invalidate: () -> Unit) {

    private var animator: ValueAnimator? = null
    private var progress = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    val running: Boolean get() = animator?.isRunning == true

    fun start() {
        if (shape.durationMs <= 0L) return
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = shape.durationMs
            // Fast off the space bar and slowing as it climbs, which reads as
            // something thrown upwards rather than a bar sliding.
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun cancel() {
        animator?.cancel()
        animator = null
    }

    /**
     * Draws the wave, rising from [from] towards the top of the view.
     *
     * [from] is the top edge of the space bar in the keyboard, and the bottom of
     * the preview in the settings screen — in both cases the line the wave
     * leaves from.
     */
    fun draw(canvas: Canvas, width: Float, from: Float) {
        val animator = animator ?: return
        if (!animator.isRunning) {
            this.animator = null
            return
        }

        // The front climbs, and the whole thing holds at full strength for the
        // first part of its travel before fading. The first version faded from
        // the moment it started, which at 420ms meant it was already half gone
        // by the time an eye moved to it.
        val reach = from * shape.reach * progress
        val fade = if (progress < shape.hold) {
            1f
        } else {
            1f - (progress - shape.hold) / (1f - shape.hold).coerceAtLeast(EPSILON)
        }
        val alpha = (shape.peakAlpha * fade).toInt().coerceIn(0, 255)
        if (alpha == 0 || reach <= 0f) return

        // Brightest at the front, trailing away behind it — a wave rather than a
        // rectangle that appears and disappears. Softness is where the tail
        // starts: at zero the gradient is one pixel wide and the front is a hard
        // bright edge, at one it fades the whole way back to the space bar.
        val tail = (1f - shape.softness).coerceIn(0f, 1f)
        paint.shader = LinearGradient(
            0f,
            from,
            0f,
            from - reach,
            intArrayOf(
                Color.TRANSPARENT,
                ColorUtils.setAlphaComponent(COLOUR, alpha / 2),
                ColorUtils.setAlphaComponent(COLOUR, alpha),
            ),
            floatArrayOf(0f, tail.coerceAtMost(MID_STOP), 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, from - reach, width, from, paint)
    }

    private companion object {
        /** Purple, as on the keypress trail: the keyboard's own voice (D19). */
        val COLOUR = Color.parseColor("#8B5CF6")

        /** The mid stop never passes the front, or the gradient inverts. */
        const val MID_STOP = 0.98f

        const val EPSILON = 0.001f
    }
}
