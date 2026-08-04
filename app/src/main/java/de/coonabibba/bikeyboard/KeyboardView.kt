package de.coonabibba.bikeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.max

/**
 * One entry in the recent-keypress trail.
 *
 * [alternate] records that the press produced a long-press alternate rather
 * than the key's own label, which is drawn differently.
 */
data class TrailEntry(val key: Key, val alternate: Boolean)

/**
 * Draws a [KeyboardLayout] and reports touches as key presses.
 *
 * Intentionally a plain custom View rather than Compose: an IME window is
 * created and destroyed on every focus change, and touch-to-glyph latency is
 * the whole product. This can be revisited, but the bar is "no worse".
 *
 * Hit testing here is still deterministic. Per D15 it eventually becomes a
 * spatial likelihood feeding the candidate scorer; the touch-point handling is
 * kept in one place so that swap does not spread.
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Called when a key is released. */
    var onKey: ((Key) -> Unit)? = null

    /**
     * Called when a long-press alternate is chosen, with the key it came from
     * and the text to insert. The text is already shifted.
     */
    var onAlternate: ((Key, String) -> Unit)? = null

    /**
     * Called on each auto-repeat tick of a held [Key.repeats] key. Kept
     * separate from [onKey] so the service can tell a deliberate second tap
     * from the machine gun.
     */
    var onRepeat: ((Key) -> Unit)? = null

    /** Called per character step while dragging on the space bar: +1 right, -1 left. */
    var onCursorStep: ((Int) -> Unit)? = null

    /** Called per word while swiping left on backspace. */
    var onDeleteWord: (() -> Unit)? = null

    var layout: KeyboardLayout = Layouts.letters
        set(value) {
            field = value
            dismissLongPress()
            stopRepeat()
            activePointers.clear()
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

    /**
     * Recently pressed keys, most recent first. The service owns the stack;
     * this view only renders it.
     */
    var trail: List<TrailEntry> = emptyList()
        set(value) {
            field = value
            trailDepths = buildTrailDepths(value)
            invalidate()
        }

    /**
     * Depth and alternate-ness per key, resolved once per trail change.
     *
     * A key can appear in the trail more than once; only its most recent
     * occurrence counts, so a letter typed twice shows the stronger colour
     * rather than blending the two.
     */
    private var trailDepths: Map<Key, Pair<Int, Boolean>> = emptyMap()

    private fun buildTrailDepths(entries: List<TrailEntry>): Map<Key, Pair<Int, Boolean>> {
        val depths = HashMap<Key, Pair<Int, Boolean>>()
        entries.forEachIndexed { depth, entry ->
            if (depth < TRAIL_STEPS && entry.key !in depths) {
                depths[entry.key] = depth to entry.alternate
            }
        }
        return depths
    }

    /** Full purple at depth 0, fading to the resting key colour by [TRAIL_STEPS]. */
    private fun trailColor(depth: Int): Int =
        ColorUtils.blendARGB(TRAIL_STRONG, KEY_BG, depth.toFloat() / TRAIL_STEPS)

    /**
     * [bounds] is what gets drawn; [hitBounds] is what gets touched. They differ
     * by half the inter-key gap, so the gaps between keys belong to their
     * neighbours instead of being dead.
     */
    private data class PlacedKey(val key: Key, val bounds: RectF, val hitBounds: RectF)

    /** What a sideways drag off a key has turned into, if anything. */
    private enum class DragMode { NONE, CURSOR, DELETE_WORD }

    /**
     * State of one finger currently on the keyboard.
     *
     * [fired] marks that this touch has already produced its output — a
     * repeating key fires on press, and a drag produces cursor steps or word
     * deletions instead — so release must not emit anything more.
     */
    private class Touch(
        var placed: PlacedKey,
        val downX: Float,
        var stepAnchorX: Float,
        var fired: Boolean = false,
        var dragMode: DragMode = DragMode.NONE,
    )

    private var placedKeys: List<PlacedKey> = emptyList()

    /**
     * Which key each active finger is on, by pointer id.
     *
     * Keyed by pointer rather than a single "pressed" field because fast typing
     * overlaps touches — the next finger lands before the previous one lifts,
     * and a single-pointer model silently drops one of them.
     */
    private val activePointers = mutableMapOf<Int, Touch>()

    /** Non-null while a long-press popup is open. */
    private var alternatesFor: PlacedKey? = null
    private var alternateBounds: List<RectF> = emptyList()
    private var alternateLabels: List<String> = emptyList()
    private var selectedAlternate: Int = -1
    private var longPressPointer: Int = MotionEvent.INVALID_POINTER_ID

    private val handler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable { openAlternates() }

    private var repeatPointer: Int = MotionEvent.INVALID_POINTER_ID
    private val repeatRunnable = object : Runnable {
        override fun run() {
            val touch = activePointers[repeatPointer] ?: return
            onRepeat?.invoke(touch.placed.key)
            handler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

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
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG)
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
        excludeFromSystemGestures(w, h)
    }

    /**
     * Claim the whole keyboard from the system's edge gestures.
     *
     * Without this, dragging along the space bar towards the right edge is
     * taken as the back gesture, and back while an IME is showing hides the
     * keyboard. The asymmetry gives it away: the space bar's right edge is
     * close to the screen edge, while its left edge is shielded by the layer
     * toggle and the globe key, so only rightward drags were being stolen.
     *
     * The platform caps how much of each edge a window may claim and keeps the
     * part nearest the bottom, which is the part that matters here.
     */
    private fun excludeFromSystemGestures(w: Int, h: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        dismissLongPress()
        stopRepeat()
        activePointers.clear()
    }

    private fun placeKeys(width: Float, height: Float): List<PlacedKey> {
        if (width <= 0f || layout.rows.isEmpty()) return emptyList()
        val usableHeight = height - gutterHeight - keyGap * 2
        val perRow = usableHeight / layout.rows.size
        val half = keyGap / 2f
        val lastRow = layout.rows.lastIndex
        val placed = mutableListOf<PlacedKey>()

        layout.rows.forEachIndexed { rowIndex, row ->
            val totalWeight = row.sumOf { it.widthWeight.toDouble() }.toFloat()
            val usableWidth = width - keyGap * (row.size + 1)
            var x = keyGap
            val y = gutterHeight + keyGap + rowIndex * perRow
            row.forEachIndexed { keyIndex, key ->
                val keyWidth = usableWidth * (key.widthWeight / totalWeight)
                val bounds = RectF(x, y, x + keyWidth, y + perRow - keyGap)

                // Grow into the gaps, and all the way to the view edge for the
                // outermost keys and the last row — a thumb landing a few pixels
                // past the edge of `m` still means `m`.
                val hitBounds = RectF(
                    if (keyIndex == 0) 0f else bounds.left - half,
                    if (rowIndex == 0) gutterHeight else bounds.top - half,
                    if (keyIndex == row.lastIndex) width else bounds.right + half,
                    if (rowIndex == lastRow) height else bounds.bottom + half,
                )

                placed += PlacedKey(key, bounds, hitBounds)
                x += keyWidth + keyGap
            }
        }
        return placed
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val heldKeys = activePointers.values
        placedKeys.forEach { placed ->
            val held = heldKeys.any { it.placed === placed }
            val basePaint = when {
                held -> pressedPaint
                placed.key.action is KeyAction.Text -> keyPaint
                else -> specialKeyPaint
            }
            canvas.drawRoundRect(placed.bounds, keyRadius, keyRadius, basePaint)

            // A finger currently on the key outranks its trail colour.
            if (!held) {
                trailDepths[placed.key]?.let { (depth, alternate) ->
                    trailPaint.color = trailColor(depth)
                    if (alternate) {
                        // Only the top half, matching where the alternate's
                        // hint is drawn — so "I typed the ü, not the u" reads
                        // off the key without a second glance.
                        canvas.save()
                        canvas.clipRect(
                            placed.bounds.left,
                            placed.bounds.top,
                            placed.bounds.right,
                            placed.bounds.centerY(),
                        )
                        canvas.drawRoundRect(placed.bounds, keyRadius, keyRadius, trailPaint)
                        canvas.restore()
                    } else {
                        canvas.drawRoundRect(placed.bounds, keyRadius, keyRadius, trailPaint)
                    }
                }
            }

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

    private fun scheduleLongPress(placed: PlacedKey, pointerId: Int) {
        dismissLongPress()
        if (placed.key.longPress.isEmpty()) return
        longPressPointer = pointerId
        handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
    }

    private fun dismissLongPress() {
        handler.removeCallbacks(longPressRunnable)
        longPressPointer = MotionEvent.INVALID_POINTER_ID
        if (alternatesFor != null) {
            alternatesFor = null
            alternateBounds = emptyList()
            alternateLabels = emptyList()
            selectedAlternate = -1
            invalidate()
        }
    }

    private fun openAlternates() {
        val target = activePointers[longPressPointer]?.placed ?: return
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
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                val x = event.getX(index)
                val hit = keyAt(x, event.getY(index))
                if (hit != null) {
                    val touch = Touch(placed = hit, downX = x, stepAnchorX = x)
                    activePointers[pointerId] = touch

                    if (hit.key.repeats) {
                        // Repeating keys act on press, so the first delete lands
                        // immediately rather than waiting for the release.
                        touch.fired = true
                        onKey?.invoke(hit.key)
                        startRepeat(pointerId)
                    } else if (activePointers.size == 1) {
                        scheduleLongPress(hit, pointerId)
                    } else {
                        // A second finger means fast typing, not a deliberate hold.
                        dismissLongPress()
                    }
                    invalidate()
                }
            }

            MotionEvent.ACTION_MOVE -> handleMove(event)

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                val released = activePointers.remove(pointerId)
                if (pointerId == repeatPointer) stopRepeat()

                val openPopup = alternatesFor
                if (openPopup != null && pointerId == longPressPointer) {
                    val chosen = alternateLabels.getOrNull(selectedAlternate)
                    dismissLongPress()
                    invalidate()
                    chosen?.let { onAlternate?.invoke(openPopup.key, it) }
                } else {
                    if (pointerId == longPressPointer) dismissLongPress()
                    invalidate()
                    // Commit the key this finger is on, not whatever happens to
                    // be under the release point — a tap that drifts off the
                    // keyboard entirely must still type what it started on.
                    if (released != null && !released.fired) {
                        onKey?.invoke(released.placed.key)
                    }
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                dismissLongPress()
                stopRepeat()
                activePointers.clear()
                invalidate()
            }
        }
        return true
    }

    private fun handleMove(event: MotionEvent) {
        var changed = false
        for (index in 0 until event.pointerCount) {
            val pointerId = event.getPointerId(index)
            val x = event.getX(index)
            val y = event.getY(index)
            val touch = activePointers[pointerId] ?: continue

            if (alternatesFor != null && pointerId == longPressPointer) {
                val alternate = alternateAt(x)
                if (alternate >= 0 && alternate != selectedAlternate) {
                    selectedAlternate = alternate
                    changed = true
                }
                continue
            }

            when (touch.dragMode) {
                DragMode.CURSOR -> {
                    emitCursorSteps(touch, x)
                    continue
                }

                DragMode.DELETE_WORD -> {
                    emitDeleteWordSteps(touch, x)
                    continue
                }

                DragMode.NONE -> Unit
            }

            // Dragging sideways on the space bar steers the cursor. Entry is by
            // distance rather than a hold timer: requiring a delay first would
            // make the gesture feel stuck, and horizontal travel on the space
            // bar is unambiguous on its own.
            if (touch.placed.key.action == KeyAction.Space &&
                abs(x - touch.downX) > CURSOR_DRAG_START_DP * density
            ) {
                touch.dragMode = DragMode.CURSOR
                touch.fired = true
                touch.stepAnchorX = touch.downX
                dismissLongPress()
                emitCursorSteps(touch, x)
                continue
            }

            // Swiping left on backspace eats words. Leftward only — it is a
            // directional gesture matching the direction of deletion, and
            // rightward on backspace should stay inert.
            if (touch.placed.key.action == KeyAction.Backspace &&
                touch.downX - x >= DELETE_WORD_STEP_DP * density
            ) {
                touch.dragMode = DragMode.DELETE_WORD
                touch.fired = true
                touch.stepAnchorX = touch.downX
                stopRepeat()
                emitDeleteWordSteps(touch, x)
                continue
            }

            // Only reassign when the finger is genuinely over another key.
            // Leaving it unchanged otherwise is what keeps a drifting tap from
            // being dropped.
            val moved = keyAt(x, y) ?: continue
            if (moved !== touch.placed) {
                touch.placed = moved
                if (pointerId == repeatPointer) stopRepeat()
                if (pointerId == longPressPointer) scheduleLongPress(moved, pointerId)
                changed = true
            }
        }
        if (changed) invalidate()
    }

    /** Emits one step per [CURSOR_STEP_DP] of travel since the last one. */
    private fun emitCursorSteps(touch: Touch, x: Float) {
        val step = CURSOR_STEP_DP * density
        while (abs(x - touch.stepAnchorX) >= step) {
            val direction = if (x > touch.stepAnchorX) 1 else -1
            touch.stepAnchorX += direction * step
            onCursorStep?.invoke(direction)
        }
    }

    /** One word per [DELETE_WORD_STEP_DP] of further leftward travel. */
    private fun emitDeleteWordSteps(touch: Touch, x: Float) {
        val step = DELETE_WORD_STEP_DP * density
        while (touch.stepAnchorX - x >= step) {
            touch.stepAnchorX -= step
            onDeleteWord?.invoke()
        }
    }

    private fun startRepeat(pointerId: Int) {
        stopRepeat()
        repeatPointer = pointerId
        handler.postDelayed(repeatRunnable, REPEAT_INITIAL_MS)
    }

    private fun stopRepeat() {
        handler.removeCallbacks(repeatRunnable)
        repeatPointer = MotionEvent.INVALID_POINTER_ID
    }

    private fun keyAt(x: Float, y: Float): PlacedKey? =
        placedKeys.firstOrNull { it.hitBounds.contains(x, y) }
            ?: placedKeys.minByOrNull { distanceTo(it.hitBounds, x, y) }
                ?.takeIf { distanceTo(it.hitBounds, x, y) < SLOP_DP * density }

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

        /** Trail entries beyond this depth are drawn as ordinary keys. */
        const val TRAIL_STEPS = 5

        /** Auto-repeat: long enough that a normal tap never triggers it. */
        const val REPEAT_INITIAL_MS = 400L
        const val REPEAT_INTERVAL_MS = 55L

        /** Sideways travel on the space bar before it becomes cursor steering. */
        const val CURSOR_DRAG_START_DP = 10f
        const val CURSOR_STEP_DP = 12f

        /**
         * Leftward travel on backspace per word deleted. Replaces the earlier
         * double tap, which fired when two quick single deletes were meant.
         */
        const val DELETE_WORD_STEP_DP = 26f
        val TRAIL_STRONG = Color.parseColor("#8B5CF6")
        val KEY_BG = Color.parseColor("#3A3A3C")
        val SPECIAL_BG = Color.parseColor("#2A2A2C")
        val PRESSED_BG = Color.parseColor("#5A5A5E")
        val POPUP_BG = Color.parseColor("#4A4A4E")
        val POPUP_SELECTED_BG = Color.parseColor("#2A5D8F")
        val HINT_FG = Color.parseColor("#9A9A9E")
    }
}
