package de.coonabibba.bikeyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * A stand-in keyboard for tuning the correction flash (D30).
 *
 * Runs the identical [CorrectionFlash] against a sketch of the bottom of the
 * keyboard, so the sliders above it can be judged by looking rather than by
 * rebuilding, switching apps, and misspelling a word on purpose. Two rounds of
 * "too subtle" went that way before this existed.
 *
 * It is a sketch and not the real keyboard: proportions are right, the surface
 * colour is right, and that is all the flash interacts with.
 */
class FlashPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var shape: FlashShape = FlashShape.fromPrefs(context)
        set(value) {
            field = value
            flash.shape = value
        }

    private val flash = CorrectionFlash(shape, ::invalidate)
    private val density = resources.displayMetrics.density

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = KEY_BG }
    private val surface = ContextCompat.getColor(context, R.color.keyboard_background)

    /** Plays the flash. Called when a slider moves, and on a tap. */
    fun play() = flash.start()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            (HEIGHT_DP * density).toInt(),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(surface)

        val gap = GAP_DP * density
        val corner = CORNER_DP * density
        val rowHeight = (height - gap * (ROWS + 1)) / ROWS

        fun key(left: Float, right: Float, top: Float) =
            canvas.drawRoundRect(left, top, right, top + rowHeight, corner, corner, keyPaint)

        // Two rows of plain keys, then a bottom row whose middle key is the
        // space bar. That is the only landmark the flash needs — it leaves from
        // the top of it.
        repeat(ROWS - 1) { row ->
            val top = gap + row * (rowHeight + gap)
            val keyWidth = (width - gap * (KEYS_PER_ROW + 1)) / KEYS_PER_ROW
            repeat(KEYS_PER_ROW) { column ->
                val left = gap + column * (keyWidth + gap)
                key(left, left + keyWidth, top)
            }
        }

        val spaceBarTop = gap + (ROWS - 1) * (rowHeight + gap)
        val sideWidth = (width - gap * 4) * SIDE_KEY_FRACTION
        key(gap, gap + sideWidth, spaceBarTop)
        key(gap * 2 + sideWidth, width - gap * 2 - sideWidth, spaceBarTop)
        key(width - gap - sideWidth, width - gap, spaceBarTop)

        flash.draw(canvas, width.toFloat(), spaceBarTop)
    }

    override fun performClick(): Boolean {
        play()
        return super.performClick()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        flash.cancel()
    }

    private companion object {
        const val HEIGHT_DP = 132f
        const val GAP_DP = 4f
        const val CORNER_DP = 6f
        const val ROWS = 3
        const val KEYS_PER_ROW = 8

        /** How much of the bottom row each key beside the space bar takes. */
        const val SIDE_KEY_FRACTION = 0.18f

        /** The same key colour the real keyboard uses. */
        val KEY_BG = Color.parseColor("#3A3A3C")
    }
}
