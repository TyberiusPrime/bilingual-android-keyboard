package de.coonabibba.bikeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

/**
 * Draws a [KeyboardLayout] and reports touches as key presses.
 *
 * Intentionally a plain custom View rather than Compose: an IME window is
 * created and destroyed on every focus change, and touch-to-glyph latency is
 * the whole product. This can be revisited, but the bar is "no worse".
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Called when a key is released inside its own bounds. */
    var onKey: ((Key) -> Unit)? = null

    var layout: KeyboardLayout = Layouts.letters
        set(value) {
            field = value
            placedKeys = emptyList()
            requestLayout()
            invalidate()
        }

    /** Whether the shift key is currently engaged (affects labels only). */
    var shifted: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private data class PlacedKey(val key: Key, val bounds: RectF)

    private var placedKeys: List<PlacedKey> = emptyList()
    private var pressed: PlacedKey? = null

    private val density = resources.displayMetrics.density
    private val keyGap = 3f * density
    private val keyRadius = 6f * density
    private val rowHeight = 52f * density

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = KEY_BG }
    private val specialKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SPECIAL_BG }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PRESSED_BG }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 20f * density
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (layout.rows.size * rowHeight + keyGap * 2).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        placedKeys = placeKeys(w.toFloat(), h.toFloat())
    }

    private fun placeKeys(width: Float, height: Float): List<PlacedKey> {
        if (width <= 0f || layout.rows.isEmpty()) return emptyList()
        val usableHeight = height - keyGap * 2
        val perRow = usableHeight / layout.rows.size
        val placed = mutableListOf<PlacedKey>()

        layout.rows.forEachIndexed { rowIndex, row ->
            val totalWeight = row.sumOf { it.widthWeight.toDouble() }.toFloat()
            val usableWidth = width - keyGap * (row.size + 1)
            var x = keyGap
            val y = keyGap + rowIndex * perRow
            row.forEach { key ->
                val keyWidth = usableWidth * (key.widthWeight / totalWeight)
                placed += PlacedKey(
                    key = key,
                    bounds = RectF(x, y, x + keyWidth, y + perRow - keyGap),
                )
                x += keyWidth + keyGap
            }
        }
        return placed
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        placedKeys.forEach { placed ->
            val paint = when {
                placed === pressed -> pressedPaint
                placed.key.action is KeyAction.Text -> keyPaint
                else -> specialKeyPaint
            }
            canvas.drawRoundRect(placed.bounds, keyRadius, keyRadius, paint)

            val label = displayLabel(placed.key)
            if (label.isNotEmpty()) {
                val cx = placed.bounds.centerX()
                val cy = placed.bounds.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
                canvas.drawText(label, cx, cy, labelPaint)
            }
        }
    }

    private fun displayLabel(key: Key): String =
        if (shifted && key.action is KeyAction.Text) key.label.uppercase() else key.label

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                pressed = keyAt(event.x, event.y)
                invalidate()
            }

            MotionEvent.ACTION_UP -> {
                val hit = keyAt(event.x, event.y)
                pressed = null
                invalidate()
                hit?.let { onKey?.invoke(it.key) }
            }

            MotionEvent.ACTION_CANCEL -> {
                pressed = null
                invalidate()
            }
        }
        return true
    }

    private fun keyAt(x: Float, y: Float): PlacedKey? =
        placedKeys.firstOrNull { it.bounds.contains(x, y) }
            ?: placedKeys.minByOrNull { distanceTo(it.bounds, x, y) }
                ?.takeIf { distanceTo(it.bounds, x, y) < SLOP_DP * density }

    private fun distanceTo(rect: RectF, x: Float, y: Float): Float {
        val dx = max(max(rect.left - x, 0f), x - rect.right)
        val dy = max(max(rect.top - y, 0f), y - rect.bottom)
        return max(dx, dy)
    }

    private companion object {
        const val SLOP_DP = 8f
        val KEY_BG = Color.parseColor("#3A3A3C")
        val SPECIAL_BG = Color.parseColor("#2A2A2C")
        val PRESSED_BG = Color.parseColor("#5A5A5E")
    }
}
