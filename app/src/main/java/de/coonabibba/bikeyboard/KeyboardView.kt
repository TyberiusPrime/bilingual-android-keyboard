package de.coonabibba.bikeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
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
 *
 * Hit testing here is still a rectangle test. Per D15 it eventually becomes a
 * spatial likelihood feeding the candidate scorer; the touch-point handling is
 * kept in one place so that swap does not spread.
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Called when a key is released inside its own bounds. */
    var onKey: ((Key) -> Unit)? = null

    /** Called when a long-press alternate is chosen. Text is already shifted. */
    var onAlternate: ((String) -> Unit)? = null

    var layout: KeyboardLayout = Layouts.letters
        set(value) {
            field = value
            dismissLongPress()
            placedKeys = placeKeys(width.toFloat(), height.toFloat())
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

    /** Non-null while a long-press popup is open. */
    private var alternatesFor: PlacedKey? = null
    private var alternateBounds: List<RectF> = emptyList()
    private var alternateLabels: List<String> = emptyList()
    private var selectedAlternate: Int = -1

    private val handler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable { openAlternates() }

    private val density = resources.displayMetrics.density
    private val keyGap = 3f * density
    private val keyRadius = 6f * density
    private val rowHeight = 52f * density

    /**
     * Space above the keys, so a long-press popup on the top row has somewhere
     * to be drawn instead of being clipped. Becomes the suggestion strip (D9),
     * which is why the height is spent now rather than added later.
     */
    private val gutterHeight = 40f * density

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = KEY_BG }
    private val specialKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SPECIAL_BG }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PRESSED_BG }
    private val popupPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = POPUP_BG }
    private val popupSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = POPUP_SELECTED_BG }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 20f * density
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HINT_FG
        textAlign = Paint.Align.RIGHT
        textSize = 10f * density
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (gutterHeight + layout.rows.size * rowHeight + keyGap * 2).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        placedKeys = placeKeys(w.toFloat(), h.toFloat())
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        dismissLongPress()
    }

    private fun placeKeys(width: Float, height: Float): List<PlacedKey> {
        if (width <= 0f || layout.rows.isEmpty()) return emptyList()
        val usableHeight = height - gutterHeight - keyGap * 2
        val perRow = usableHeight / layout.rows.size
        val placed = mutableListOf<PlacedKey>()

        layout.rows.forEachIndexed { rowIndex, row ->
            val totalWeight = row.sumOf { it.widthWeight.toDouble() }.toFloat()
            val usableWidth = width - keyGap * (row.size + 1)
            var x = keyGap
            val y = gutterHeight + keyGap + rowIndex * perRow
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

            // The first alternate is shown small in the corner, so the digits
            // and umlauts are discoverable without holding every key (D17).
            placed.key.longPress.firstOrNull()?.let { hint ->
                canvas.drawText(
                    shiftAlternate(hint),
                    placed.bounds.right - 5f * density,
                    placed.bounds.top + 13f * density,
                    hintPaint,
                )
            }
        }

        drawAlternates(canvas)
    }

    private fun drawAlternates(canvas: Canvas) {
        if (alternatesFor == null) return
        alternateBounds.forEachIndexed { index, bounds ->
            canvas.drawRoundRect(
                bounds,
                keyRadius,
                keyRadius,
                if (index == selectedAlternate) popupSelectedPaint else popupPaint,
            )
            val cy = bounds.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
            canvas.drawText(alternateLabels[index], bounds.centerX(), cy, labelPaint)
        }
    }

    private fun displayLabel(key: Key): String =
        if (shifted && key.action is KeyAction.Text) key.label.uppercase() else key.label

    /**
     * `ß`.uppercase() is `SS`, which is correct German and wrong here — nobody
     * long-presses `s` on a shifted keyboard wanting two letters.
     */
    private fun shiftAlternate(alternate: String): String =
        if (shifted && alternate != "ß") alternate.uppercase() else alternate

    // -- long press ---------------------------------------------------------

    private fun scheduleLongPress(placed: PlacedKey) {
        dismissLongPress()
        if (placed.key.longPress.isEmpty()) return
        handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
    }

    private fun dismissLongPress() {
        handler.removeCallbacks(longPressRunnable)
        if (alternatesFor != null) {
            alternatesFor = null
            alternateBounds = emptyList()
            alternateLabels = emptyList()
            selectedAlternate = -1
            invalidate()
        }
    }

    private fun openAlternates() {
        val target = pressed ?: return
        val labels = target.key.longPress.map(::shiftAlternate)
        if (labels.isEmpty()) return

        val cellWidth = max(target.bounds.width(), MIN_POPUP_CELL_DP * density)
        val cellHeight = target.bounds.height()
        val totalWidth = cellWidth * labels.size

        // Anchor over the key, then clamp so the popup stays on screen.
        var left = target.bounds.centerX() - totalWidth / 2f
        left = left.coerceIn(keyGap, max(keyGap, width - totalWidth - keyGap))
        val top = max(0f, target.bounds.top - cellHeight - keyGap)

        alternateBounds = labels.indices.map { index ->
            RectF(
                left + index * cellWidth,
                top,
                left + (index + 1) * cellWidth - keyGap,
                top + cellHeight,
            )
        }
        alternateLabels = labels
        alternatesFor = target
        selectedAlternate = 0
        invalidate()
    }

    private fun alternateAt(x: Float): Int =
        alternateBounds.indexOfFirst { x >= it.left && x <= it.right + keyGap }

    // -- touch --------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = keyAt(event.x, event.y)
                pressed?.let(::scheduleLongPress)
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                if (alternatesFor != null) {
                    val index = alternateAt(event.x)
                    if (index >= 0 && index != selectedAlternate) {
                        selectedAlternate = index
                        invalidate()
                    }
                } else {
                    val moved = keyAt(event.x, event.y)
                    if (moved !== pressed) {
                        pressed = moved
                        dismissLongPress()
                        pressed?.let(::scheduleLongPress)
                        invalidate()
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                val openPopup = alternatesFor
                if (openPopup != null) {
                    val chosen = alternateLabels.getOrNull(selectedAlternate)
                    dismissLongPress()
                    pressed = null
                    invalidate()
                    chosen?.let { onAlternate?.invoke(it) }
                } else {
                    val hit = keyAt(event.x, event.y)
                    dismissLongPress()
                    pressed = null
                    invalidate()
                    hit?.let { onKey?.invoke(it.key) }
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                dismissLongPress()
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
        /**
         * Shorter than the platform's 500ms default. Per D5 this is a key you
         * hit routinely when typing German, not a rare gesture, and the stock
         * delay is noticeably too slow for it.
         */
        const val LONG_PRESS_MS = 280L
        const val SLOP_DP = 8f
        const val MIN_POPUP_CELL_DP = 40f
        val KEY_BG = Color.parseColor("#3A3A3C")
        val SPECIAL_BG = Color.parseColor("#2A2A2C")
        val PRESSED_BG = Color.parseColor("#5A5A5E")
        val POPUP_BG = Color.parseColor("#4A4A4E")
        val POPUP_SELECTED_BG = Color.parseColor("#2A5D8F")
        val HINT_FG = Color.parseColor("#9A9A9E")
    }
}
