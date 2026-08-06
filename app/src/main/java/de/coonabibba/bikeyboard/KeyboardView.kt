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
import androidx.core.content.ContextCompat
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

    /**
     * Called when a key is released, with where the press landed.
     *
     * The touch is what lets a correction tell a slip from a decision (D28);
     * it is null for keys that do not produce a letter, and for a letter that
     * arrived from a long-press popup rather than from the key itself.
     */
    var onKey: ((Key, TypedTouch?) -> Unit)? = null

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

    /**
     * Called with -1 or 1 per line of vertical travel on the steering key (D38).
     * Separate from [onCursorStep] because moving by lines is a different
     * question for the field to answer, and a different way to fail.
     */
    var onLineStep: ((Int) -> Unit)? = null

    /** Called per word while swiping left on backspace. */
    var onDeleteWord: (() -> Unit)? = null

    /** Called on every press, before anything is decided. For haptics (D29). */
    var onPress: (() -> Unit)? = null

    /**
     * Called when shift is swiped upwards, which re-cases the word the cursor
     * is in (D23). Once per swipe: it edits text, so it wants a fixed and
     * predictable cost, the same reasoning as the backspace swipe.
     */
    var onShiftSwipeUp: (() -> Unit)? = null

    var layout: KeyboardLayout = Layouts.letters
        set(value) {
            field = value
            dismissLongPress()
            stopRepeat()
            activePointers.clear()
            placedKeys = placeKeys(width.toFloat(), height.toFloat())
            geometry = buildGeometry(placedKeys)
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
     * Whether shift is locked on. Drawn differently from a one-shot shift,
     * because the difference between the next letter being capitalised and
     * every letter being capitalised is worth one glance.
     */
    var capsLocked: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Whether the keypress trail is drawn at all (D19, D38).
     *
     * Only the label depends on it here — the service simply stops feeding
     * [trail] when this is off, and stops *recording* it, which is the part
     * that matters in a password field. Keeping the drawing unconditional means
     * there is one way for the trail to be empty rather than two.
     */
    var trailEnabled: Boolean = true
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

    /** What a drag off a key has turned into, if anything. */
    private enum class DragMode { NONE, CURSOR, LINES, DELETE_WORD, RECASE }

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
        val downY: Float,
        var stepAnchorX: Float,
        var stepAnchorY: Float,
        var fired: Boolean = false,
        var dragMode: DragMode = DragMode.NONE,
        /** Where this press landed, and what else it nearly hit (D28). */
        val touch: TypedTouch? = null,
    )

    private var placedKeys: List<PlacedKey> = emptyList()

    /**
     * Where the letter keys are, for judging what a touch nearly hit (D28).
     * Rebuilt with the layout, since the symbol layer has different letters in
     * different places.
     */
    private var geometry: KeyGeometry = KeyGeometry(emptyList(), 0f)

    private fun buildGeometry(placed: List<PlacedKey>): KeyGeometry {
        val letters = placed.mapNotNull { key ->
            val text = (key.key.action as? KeyAction.Text)?.text ?: return@mapNotNull null
            if (text.length != 1) return@mapNotNull null
            KeyGeometry.Entry(text[0].lowercaseChar(), key.bounds.centerX(), key.bounds.centerY())
        }
        // The letter keys are all one width; the space bar and the modifiers are
        // not letters and are not in here.
        val width = placed.firstOrNull { it.key.action is KeyAction.Text }?.bounds?.width() ?: 0f
        return KeyGeometry(letters, width)
    }

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
            handler.postDelayed(this, repeatIntervalMs)
        }
    }

    /**
     * Timings are settings (D30): a hold that feels deliberate to one thumb is
     * a stutter to another. Read once, here — the service rebuilds the input
     * view when they change.
     */
    private val longPressMs = KeyboardPrefs.timing(context, KeyboardPrefs.KEY_LONG_PRESS_MS)
    private val repeatDelayMs = KeyboardPrefs.timing(context, KeyboardPrefs.REPEAT_DELAY_MS)
    private val repeatIntervalMs = KeyboardPrefs.timing(context, KeyboardPrefs.REPEAT_INTERVAL_MS)

    private val density = resources.displayMetrics.density
    private val keyGap = 3f * density
    private val keyRadius = 6f * density
    private val rowHeight = 52f * density

    /**
     * How far above this view a long-press popup on the top row may overhang.
     *
     * The space is the suggestion strip's (D9) — this view no longer reserves a
     * gutter of its own, so the keyboard's height is unchanged by the strip
     * arriving. The popup is allowed to draw over the strip because the
     * container switches off child clipping and draws the keys after it; the
     * alternative, clamping the popup to this view's top edge, puts it directly
     * under the finger holding the key.
     */
    private val popupHeadroom = KeyboardPrefs.stripHeightPx(context)

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

    init {
        // Nothing paints behind an IME window. Without an opaque surface of our
        // own, the app being typed into shows through the gaps between keys and
        // through the gutter.
        setBackgroundColor(ContextCompat.getColor(context, R.color.keyboard_background))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (layout.rows.size * rowHeight + keyGap * 2).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        placedKeys = placeKeys(w.toFloat(), h.toFloat())
        geometry = buildGeometry(placedKeys)
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
        flash.cancel()
        activePointers.clear()
    }

    // -- the correction flash (D28) ------------------------------------------

    /**
     * See [CorrectionFlash]. Its shape is read once when the view is built, like
     * every other setting here; the service rebuilds the view when it changes.
     */
    private val flash = CorrectionFlash(FlashShape.fromPrefs(context), ::invalidate)

    fun flashCorrection() = flash.start()

    private fun drawFlash(canvas: Canvas) {
        // From the top of the space bar, because that is the key that caused it.
        val spaceBar = placedKeys.firstOrNull { it.key.action == KeyAction.Space }
        flash.draw(canvas, width.toFloat(), spaceBar?.bounds?.top ?: height.toFloat())
    }

    private fun placeKeys(width: Float, height: Float): List<PlacedKey> {
        if (width <= 0f || layout.rows.isEmpty()) return emptyList()
        val usableHeight = height - keyGap * 2
        val perRow = usableHeight / layout.rows.size
        val half = keyGap / 2f
        val lastRow = layout.rows.lastIndex
        val placed = mutableListOf<PlacedKey>()

        layout.rows.forEachIndexed { rowIndex, row ->
            val totalWeight = row.sumOf { it.widthWeight.toDouble() }.toFloat()
            val usableWidth = width - keyGap * (row.size + 1)
            var x = keyGap
            val y = keyGap + rowIndex * perRow
            row.forEachIndexed { keyIndex, key ->
                val keyWidth = usableWidth * (key.widthWeight / totalWeight)
                val bounds = RectF(x, y, x + keyWidth, y + perRow - keyGap)

                // Grow into the gaps, and all the way to the view edge for the
                // outermost keys and the last row — a thumb landing a few pixels
                // past the edge of `m` still means `m`. The top row grows to
                // this view's top edge and no further: above it is the
                // suggestion strip, whose taps are its own.
                val hitBounds = RectF(
                    if (keyIndex == 0) 0f else bounds.left - half,
                    if (rowIndex == 0) 0f else bounds.top - half,
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
        drawFlash(canvas)
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

    private fun displayLabel(key: Key): String = when {
        key.action == KeyAction.Shift && capsLocked -> CAPS_LOCK_LABEL
        // The one key that says what it will do rather than what it is.
        key.action == KeyAction.ToggleTrail ->
            if (trailEnabled) Layouts.TRAIL_ON_LABEL else Layouts.TRAIL_OFF_LABEL
        shifted && key.action is KeyAction.Text -> key.label.uppercase()
        else -> key.label
    }

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
        handler.postDelayed(longPressRunnable, longPressMs)
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

        // The first alternate goes under the finger and the rest extend one way
        // from it; see [AlternatePopup] for why that is the whole design.
        val lefts = AlternatePopup.cellLefts(
            count = labels.size,
            keyCentre = target.bounds.centerX(),
            cellWidth = cellWidth,
            viewWidth = width.toFloat(),
            gap = keyGap,
        )
        // Negative is allowed, up to the headroom the strip provides: a popup
        // that lands on the key you are holding is a popup you cannot read.
        val top = max(-popupHeadroom, target.bounds.top - cellHeight - keyGap)

        alternateBounds = labels.indices.map { index ->
            RectF(lefts[index], top, lefts[index] + cellWidth - keyGap, top + cellHeight)
        }
        alternateLabels = labels
        alternatesFor = target
        selectedAlternate = 0
        invalidate()
    }

    /**
     * The cell under [x], or -1 beyond the row.
     *
     * Index order is not screen order: a popup on the right of the board runs
     * leftwards (D36), so cell zero is the rightmost rectangle. Testing every
     * rectangle rather than dividing by width is what makes that a non-issue —
     * and it means a finger that drifts off the far side of cell zero keeps
     * cell zero, which is the right answer for the alternate the key advertises.
     */
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
                    onPress?.invoke()
                    val y = event.getY(index)
                    val touch = Touch(
                        placed = hit,
                        downX = x,
                        downY = y,
                        stepAnchorX = x,
                        stepAnchorY = y,
                        touch = typedTouch(hit.key, x, y),
                    )
                    activePointers[pointerId] = touch

                    if (hit.key.repeats) {
                        // Repeating keys act on press, so the first delete lands
                        // immediately rather than waiting for the release.
                        touch.fired = true
                        onKey?.invoke(hit.key, null)
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
                        onKey?.invoke(released.placed.key, released.touch)
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

                DragMode.LINES -> {
                    emitLineSteps(touch, y)
                    continue
                }

                // One word per swipe, deliberately. Repeating on continued
                // travel took whole clauses out before the finger stopped.
                // Lift and swipe again for the next word.
                DragMode.DELETE_WORD, DragMode.RECASE -> continue

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

            // Dragging up and down the steering key moves the cursor a line at
            // a time (D38). Two guards that the space bar does not need: the
            // travel has to be mostly vertical, and it has to be further, because
            // this is a letter key that gets tapped hundreds of times a minute
            // and a tap that drifts must stay a tap.
            if (touch.placed.key.steersLines) {
                val dy = y - touch.downY
                val dx = x - touch.downX
                if (abs(dy) > LINE_DRAG_START_DP * density && abs(dy) > abs(dx)) {
                    touch.dragMode = DragMode.LINES
                    touch.fired = true
                    touch.stepAnchorY = touch.downY
                    dismissLongPress()
                    emitLineSteps(touch, y)
                    continue
                }
            }

            // Swiping up on shift cycles the case of the word the cursor is
            // in. Upward because shift has always pointed that way, and
            // because nothing else on this key is vertical.
            if (touch.placed.key.action == KeyAction.Shift &&
                touch.downY - y >= RECASE_TRIGGER_DP * density
            ) {
                touch.dragMode = DragMode.RECASE
                touch.fired = true
                dismissLongPress()
                onShiftSwipeUp?.invoke()
                continue
            }

            // Swiping left on backspace eats words. Leftward only — it is a
            // directional gesture matching the direction of deletion, and
            // rightward on backspace should stay inert.
            if (touch.placed.key.action == KeyAction.Backspace &&
                touch.downX - x >= DELETE_WORD_TRIGGER_DP * density
            ) {
                touch.dragMode = DragMode.DELETE_WORD
                touch.fired = true
                stopRepeat()
                onDeleteWord?.invoke()
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


    /**
     * Emits one step per [LINE_STEP_DP] of vertical travel since the last one.
     *
     * A coarser step than the horizontal one: a line is a bigger jump than a
     * character, and overshooting by a line costs more to undo.
     */
    private fun emitLineSteps(touch: Touch, y: Float) {
        val step = LINE_STEP_DP * density
        while (abs(y - touch.stepAnchorY) >= step) {
            val direction = if (y > touch.stepAnchorY) 1 else -1
            touch.stepAnchorY += direction * step
            onLineStep?.invoke(direction)
        }
    }

    private fun startRepeat(pointerId: Int) {
        stopRepeat()
        repeatPointer = pointerId
        handler.postDelayed(repeatRunnable, repeatDelayMs)
    }

    private fun stopRepeat() {
        handler.removeCallbacks(repeatRunnable)
        repeatPointer = MotionEvent.INVALID_POINTER_ID
    }

    /**
     * What a press on [key] at ([x], [y]) says, for a key that types a letter.
     *
     * Null for everything else: a space bar has no near misses worth recording,
     * and neither does a key that produces two characters.
     */
    private fun typedTouch(key: Key, x: Float, y: Float): TypedTouch? {
        val text = (key.action as? KeyAction.Text)?.text ?: return null
        if (text.length != 1) return null
        val char = text[0].lowercaseChar()
        return TypedTouch(char, geometry.alternatives(x, y, char))
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
        const val SLOP_DP = 8f
        const val MIN_POPUP_CELL_DP = 40f

        /** Trail entries beyond this depth are drawn as ordinary keys. */
        const val TRAIL_STEPS = 5

        /** Sideways travel on the space bar before it becomes cursor steering. */
        const val CURSOR_DRAG_START_DP = 10f
        const val CURSOR_STEP_DP = 12f

        /**
         * Vertical travel on the steering key before it becomes line steering.
         *
         * Nearly twice the space bar's threshold, because the space bar cannot
         * be typed by accident and a letter can. A thumb that rolls while
         * tapping `h` has to stay a tap.
         */
        const val LINE_DRAG_START_DP = 18f
        const val LINE_STEP_DP = 22f

        /**
         * Leftward travel on backspace before a word is deleted. Fires once per
         * swipe, not per step: repeating on continued travel removed whole
         * clauses before the finger stopped. Replaces the earlier double tap,
         * which fired when two quick single deletes were meant.
         */
        const val DELETE_WORD_TRIGGER_DP = 30f

        /**
         * Upward travel on shift before the word is re-cased. Longer than the
         * backspace swipe: a thumb on shift is at the edge of the keyboard and
         * drifts upwards on the way to the letters above it.
         */
        const val RECASE_TRIGGER_DP = 36f

        /** Shift, but latched. */
        const val CAPS_LOCK_LABEL = "⇪"
        val TRAIL_STRONG = Color.parseColor("#8B5CF6")
        val KEY_BG = Color.parseColor("#3A3A3C")
        val SPECIAL_BG = Color.parseColor("#2A2A2C")
        val PRESSED_BG = Color.parseColor("#5A5A5E")
        val POPUP_BG = Color.parseColor("#4A4A4E")
        val POPUP_SELECTED_BG = Color.parseColor("#2A5D8F")
        val HINT_FG = Color.parseColor("#9A9A9E")
    }
}
