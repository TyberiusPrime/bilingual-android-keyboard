package de.coonabibba.bikeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
import kotlin.math.hypot
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

    /**
     * Called when a whole word has been traced in one stroke (D39).
     *
     * The geometry goes with it because the decoder needs to know where the
     * letters were *for this stroke* — the layout can change between one swipe
     * and the next, and a path is meaningless without the keyboard it was drawn
     * on.
     */
    var onGlide: ((GesturePath, KeyGeometry) -> Unit)? = null

    /**
     * Asked for the words to put on the quick menu when the personal key is
     * tapped (D40).
     *
     * A question rather than a property because the list can change between one
     * tap and the next — the launcher screen writes the same file the keyboard
     * reads — and a view holding a stale copy of it would be a menu that
     * silently stops matching the settings that produced it.
     */
    var onPersonalMenu: (() -> List<String>)? = null

    /** A word chosen from the quick menu. */
    var onQuickInsert: ((String) -> Unit)? = null

    /** The personal key held down: remember the word in front of the cursor. */
    var onPersonalHold: (() -> Unit)? = null

    /** The personal key tapped twice, or tapped with nothing yet on the menu. */
    var onPersonalSettings: (() -> Unit)? = null

    /**
     * Called when the steering gesture claims a letter the previous tap already
     * typed (D39): tap `h`, press again and drag, and that first `h` has to go.
     *
     * Only on the drag. A plain double tap still types both, so `withhold` and
     * `Rohheit` are unaffected — the gesture costs nothing rather than costing
     * the doubled letter.
     */
    var onRetractSteerTap: (() -> Unit)? = null

    var layout: KeyboardLayout = Layouts.letters
        set(value) {
            field = value
            dismissLongPress()
            stopRepeat()
            abandonGlide()
            dismissQuickMenu()
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
     * Whether a drag off a letter may trace a word (D39).
     *
     * Off wherever there are no suggestions — a password box, a field that asks
     * not to be helped — because a stroke there has no dictionary to be decoded
     * against and could not produce anything. Switching off the *recording*
     * rather than the commit is the point: a gesture that visibly draws itself
     * across the keys and then does nothing is worse than one that is simply
     * not there, and the ribbon would be a picture of a password.
     */
    var glideEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) abandonGlide()
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
    private enum class DragMode { NONE, CURSOR, LINES, DELETE_WORD, RECASE, GLIDE }

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
        /**
         * Whether this press is the second half of a tap-then-hold on the
         * steering key, and so may steer by line rather than start a glide
         * (D39).
         */
        val steerArmed: Boolean = false,
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

    // -- swiping (D39) --------------------------------------------------------

    /**
     * The stroke so far, recorded from the moment a finger lands on a letter.
     *
     * Recording starts before anything has decided the touch is a glide,
     * because by the time it *is* one — the finger has to reach a second letter
     * key — the interesting half of the stroke has already happened. Points are
     * floats in a fixed array rather than objects: this runs on every move
     * event of every tap, and a keyboard that allocates per touch report is a
     * keyboard that stutters.
     */
    private var glideX = FloatArray(GLIDE_CAPACITY)
    private var glideY = FloatArray(GLIDE_CAPACITY)
    private var glideCount = 0

    /** The pointer whose stroke is being recorded, glide or not yet. */
    private var glidePointer = MotionEvent.INVALID_POINTER_ID

    /** True once the stroke has been accepted as a glide and is being drawn. */
    private var gliding = false

    /**
     * True once the finger has lifted but the stroke is still on screen.
     *
     * A swipe that produces the wrong word is otherwise impossible to argue
     * with: by the time the word appears, the evidence for how it was decided
     * has already gone. Leaving the stroke up until the next press — with the
     * two ends ringed, because the two ends are what bound the search — turns
     * "it guessed wrong again" into something that can be looked at. If the
     * ring is sitting on the wrong key, that is the whole explanation.
     */
    private var glideSettled = false

    private val glidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = TRAIL_STRONG
    }
    private val glideRender = Path()

    // -- the quick menu (D40) -------------------------------------------------

    /**
     * The quick menu's rows, empty while it is closed.
     *
     * Unlike the long-press popup this one is *modal*: it opens on a release,
     * so by the time it is on screen the finger has already gone, and it has to
     * survive until a separate press picks something. That is the whole reason
     * it is not the same mechanism — the alternates popup lives inside one
     * touch, from press to release, and cannot outlast it.
     */
    private var quickItems: List<String> = emptyList()
    private var quickPressed = -1

    /**
     * The window the rows are drawn in, and how far the list has been dragged
     * up inside it.
     *
     * A viewport plus an offset rather than a rectangle per row, because with
     * scrolling there is no longer a fixed rectangle for a row to have — row
     * *n* is wherever the scroll puts it, and computing that from the offset in
     * both directions is what keeps drawing and hit testing from disagreeing.
     */
    private val quickViewport = RectF()
    private var quickRowHeight = 0f
    private var quickScroll = 0f
    private var quickMaxScroll = 0f

    /** Where the finger went down, to tell a tap on a row from a drag of the list. */
    private var quickDownY = 0f
    private var quickLastY = 0f
    private var quickScrolling = false

    /**
     * Whether the menu was already open when the current press began, so that
     * the press which dismisses it is not also the press that reopens it.
     */
    private var quickWasOpen = false

    val quickMenuOpen: Boolean get() = quickItems.isNotEmpty()

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
    private val doubleTapMs = KeyboardPrefs.timing(context, KeyboardPrefs.DOUBLE_TAP_MS)

    /**
     * Lets the steering key tell a fresh press from the second half of a
     * tap-then-hold (D39). See [TapThenHold].
     *
     * Declared here rather than beside the rest of the swiping state because a
     * field initialiser can only read fields declared above it, and this one
     * needs [doubleTapMs]. Kotlin catches that at compile time for a field in
     * the same class; it is the same shape as the mistake that made the whole
     * app crash on launch when a `Service` field reached for a system service
     * before the base context existed, and it is worth keeping the two
     * together where the dependency is visible.
     */
    private val steerArming = TapThenHold(doubleTapMs)

    /**
     * Tap pairs on the personal key (D40). Lives here rather than in the
     * service because the view already owns whether the quick menu is open, and
     * the two answers have to be decided together: the second tap of a pair
     * both dismisses the menu and opens the settings.
     */
    private val personalTaps = DoubleTap(doubleTapMs)

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

    // The quick menu's own paints (D40): left-aligned and smaller than a key's,
    // because these rows carry addresses rather than letters.
    private val quickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = POPUP_BG }
    private val quickPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = POPUP_SELECTED_BG }
    private val quickTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        textSize = 16f * density
    }
    private val quickScrollbarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

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
        dismissQuickMenu()
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
                // The personal key is purple, the same purple as the trail and
                // the correction flash. Everything in this keyboard that means
                // "the keyboard knows something about your words" is that
                // colour, and this is the key that decides what it knows (D40).
                labelPaint.color = if (placed.key.action == KeyAction.Personal) {
                    TRAIL_STRONG
                } else {
                    Color.WHITE
                }
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

        drawGlide(canvas)
        drawAlternates(canvas)
        drawQuickMenu(canvas)
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
        // The personal key has no alternates but does have a hold (D40), so it
        // wants the timer even though there is no popup at the end of it.
        if (placed.key.longPress.isEmpty() && placed.key.action != KeyAction.Personal) return
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
        val touch = activePointers[longPressPointer] ?: return
        val target = touch.placed

        // Holding the personal key remembers the word rather than opening
        // anything (D40). It fires here, on the timer, so the word is learned
        // the moment the hold is long enough — releasing is not part of it, and
        // the release must then produce no tap.
        if (target.key.action == KeyAction.Personal) {
            touch.fired = true
            dismissLongPress()
            personalTaps.reset()
            onPersonalHold?.invoke()
            return
        }

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
        // The quick menu is modal while it is up, so it gets first refusal on
        // everything (D40).
        if (handleQuickMenu(event)) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // The last stroke stays on screen only until something else
                // happens, whatever that something is — including a press on a
                // key that starts no stroke of its own.
                clearSettledGlide()
                // Only a press that had to close the menu carries this; the
                // next one starts clean.
                if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) quickWasOpen = false
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
                        steerArmed = armsSteering(hit.key, event.eventTime),
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

                    if (glideEnabled && activePointers.size == 1 && isLetter(hit) && !touch.steerArmed) {
                        beginRecording(pointerId, x, y)
                    } else {
                        // Two fingers down is typing, not tracing.
                        abandonGlide()
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

                // A finished stroke is a whole word, and nothing else happens
                // on this release: no key, no popup, no tap remembered.
                if (pointerId == glidePointer && gliding) {
                    val path = finishGlide()
                    dismissLongPress()
                    forgetTap()
                    invalidate()
                    path?.let { onGlide?.invoke(it, geometry) }
                    return true
                }
                if (pointerId == glidePointer) abandonGlide()

                val openPopup = alternatesFor
                if (openPopup != null && pointerId == longPressPointer) {
                    val chosen = alternateLabels.getOrNull(selectedAlternate)
                    dismissLongPress()
                    forgetTap()
                    invalidate()
                    chosen?.let { onAlternate?.invoke(openPopup.key, it) }
                } else {
                    if (pointerId == longPressPointer) dismissLongPress()
                    invalidate()
                    // Commit the key this finger is on, not whatever happens to
                    // be under the release point — a tap that drifts off the
                    // keyboard entirely must still type what it started on.
                    if (released != null && !released.fired) {
                        if (released.placed.key.action == KeyAction.Personal) {
                            tapPersonal(released.placed, event.eventTime)
                        } else {
                            onKey?.invoke(released.placed.key, released.touch)
                        }
                        rememberTap(released.placed.key, event.eventTime)
                    } else {
                        forgetTap()
                    }
                    quickWasOpen = false
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                dismissLongPress()
                stopRepeat()
                abandonGlide()
                forgetTap()
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

                DragMode.GLIDE -> {
                    recordBatch(event, index, x, y)
                    changed = true
                    continue
                }

                // One word per swipe, deliberately. Repeating on continued
                // travel took whole clauses out before the finger stopped.
                // Lift and swipe again for the next word.
                DragMode.DELETE_WORD, DragMode.RECASE -> continue

                DragMode.NONE -> Unit
            }

            // Record before deciding. By the time a stroke has proved itself a
            // glide it has already crossed a key, and that first leg is the one
            // carrying the first letter — the letter the whole search is
            // bounded by. Throwing it away and starting from the crossing point
            // would lose exactly the part that cannot be guessed.
            if (pointerId == glidePointer) recordBatch(event, index, x, y)

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
            // a time (D38) — but only on the second press of a tap-then-hold,
            // because a plain drag off a letter now means something else (D39).
            //
            // The two gestures live on the same key and cannot be told apart by
            // direction: `h` to `b` is down and to the left, which is exactly
            // what steering looks like. So they are told apart by what came
            // before instead. Tap `h`, press it again straight away, and drag:
            // that sequence is not something a swipe ever produces.
            if (touch.steerArmed) {
                val dy = y - touch.downY
                val dx = x - touch.downX
                if (abs(dy) > LINE_DRAG_START_DP * density && abs(dy) > abs(dx)) {
                    touch.dragMode = DragMode.LINES
                    touch.fired = true
                    touch.stepAnchorY = touch.downY
                    dismissLongPress()
                    // The tap that armed this typed a letter. It was the price
                    // of reaching the gesture, not something anybody wanted in
                    // the text, so it goes back. Only here, on the drag — a
                    // plain double tap still types both letters.
                    onRetractSteerTap?.invoke()
                    emitLineSteps(touch, y)
                    continue
                }
            }

            // A stroke that leaves the letter it started on and reaches another
            // one is a swiped word (D39).
            //
            // Reaching a *different letter key* is the whole test, and distance
            // alone would not do: a tap that drifts must stay a tap, and on a
            // phone a lazy thumb drifts a surprising way without ever meaning
            // to leave the key. Requiring an actual crossing also means the
            // gesture cannot fire on the modifiers, none of which are letters,
            // so the space bar, shift and backspace keep their own drags
            // untouched.
            if (pointerId == glidePointer && !gliding && isLetter(touch.placed)) {
                val reached = keyAt(x, y)
                if (reached != null &&
                    reached !== touch.placed &&
                    isLetter(reached) &&
                    hypot(x - touch.downX, y - touch.downY) > GLIDE_START_DP * density
                ) {
                    gliding = true
                    touch.dragMode = DragMode.GLIDE
                    touch.fired = true
                    dismissLongPress()
                    stopRepeat()
                    changed = true
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

    // -- the quick menu (D40) -------------------------------------------------

    /**
     * What a tap on the personal key does.
     *
     * Three outcomes from one key, and the order they are tested in is the
     * design. A second tap inside the double-tap window always wins and opens
     * the settings — including the very tap that closes the menu the first one
     * opened, which is what makes "tap for the menu, double tap for settings"
     * work as one motion rather than as two conflicting ones.
     *
     * A tap with nothing on the menu goes to the settings too, rather than
     * doing nothing. D38 was explicit about this: a control that silently does
     * nothing is worse than one that is absent, and the settings screen is
     * exactly where somebody with an empty menu needs to go.
     */
    private fun tapPersonal(target: PlacedKey, now: Long) {
        val reopening = quickWasOpen
        if (personalTaps.tap(now)) {
            dismissQuickMenu()
            onPersonalSettings?.invoke()
            return
        }
        if (reopening) return

        val items = onPersonalMenu?.invoke().orEmpty()
        if (items.isEmpty()) {
            onPersonalSettings?.invoke()
            return
        }
        openQuickMenu(target, items)
    }

    /**
     * Lays the menu out upwards from the key, as many rows as there is room
     * for.
     *
     * Rows rather than the long-press popup's cells because the things on it
     * are addresses and names, not characters — a row of them side by side
     * would be unreadable at any width the screen has.
     */
    private fun openQuickMenu(target: PlacedKey, items: List<String>) {
        quickRowHeight = QUICK_ROW_DP * density
        // Upwards from the key, into the strip's headroom, and no further.
        val available = target.bounds.top + popupHeadroom
        val fits = (available / quickRowHeight).toInt().coerceIn(1, QUICK_MAX_ROWS)
        val visible = minOf(fits, items.size)

        val padding = QUICK_PADDING_DP * density
        val scrollbar = if (items.size > visible) QUICK_SCROLLBAR_DP * density else 0f
        val widest = items.maxOf { quickTextPaint.measureText(it) } + padding * 2 + scrollbar
        val menuWidth = widest.coerceAtMost(width - keyGap * 2)
        // Centred on the key where it can be, shoved inboard where it cannot.
        val left = (target.bounds.centerX() - menuWidth / 2f)
            .coerceIn(keyGap, width - keyGap - menuWidth)

        val bottom = target.bounds.top - keyGap
        quickViewport.set(left, bottom - visible * quickRowHeight, left + menuWidth, bottom)
        quickItems = items
        // Everything below the window is reachable by dragging; nothing is
        // dropped for want of room, which is the whole point of scrolling it.
        quickMaxScroll = (items.size * quickRowHeight - quickViewport.height()).coerceAtLeast(0f)
        quickScroll = 0f
        quickPressed = -1
        quickScrolling = false
        invalidate()
    }

    fun dismissQuickMenu() {
        if (quickItems.isEmpty()) return
        quickItems = emptyList()
        quickPressed = -1
        quickScrolling = false
        quickScroll = 0f
        quickMaxScroll = 0f
        invalidate()
    }

    /** Where row [index] sits right now, given the scroll. */
    private fun quickRowTop(index: Int): Float =
        quickViewport.top - quickScroll + index * quickRowHeight

    private fun quickRowAt(x: Float, y: Float): Int {
        if (!quickViewport.contains(x, y)) return -1
        val index = ((y - quickViewport.top + quickScroll) / quickRowHeight).toInt()
        return if (index in quickItems.indices) index else -1
    }

    private fun scrollQuickMenu(by: Float) {
        val wanted = (quickScroll + by).coerceIn(0f, quickMaxScroll)
        if (wanted == quickScroll) return
        quickScroll = wanted
        invalidate()
    }

    /**
     * The menu's own touch handling, which runs in front of everything else
     * while it is open.
     *
     * Returns whether the event was the menu's. A press on the personal key is
     * deliberately *not* claimed: it dismisses the menu and then carries on as
     * an ordinary press, so it can go on to be the second half of a double tap.
     */
    private fun handleQuickMenu(event: MotionEvent): Boolean {
        if (quickItems.isEmpty()) return false
        // The pointer this event is about, which for a second finger landing is
        // not the first one.
        val index = event.actionIndex
        val x = event.getX(index)
        val y = event.getY(index)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (quickViewport.contains(x, y)) {
                    quickPressed = quickRowAt(x, y)
                    quickDownY = y
                    quickLastY = y
                    quickScrolling = false
                    invalidate()
                    return true
                }
                quickWasOpen = true
                dismissQuickMenu()
                // A press on the key that opened it falls through; a press
                // anywhere else is spent on closing the menu and types nothing.
                return keyAt(x, y)?.key?.action != KeyAction.Personal
            }

            MotionEvent.ACTION_MOVE -> {
                if (quickPressed < 0 && !quickScrolling) return false

                // Past the slop the gesture is a scroll, not a choice, and the
                // row under the finger stops being selected — otherwise letting
                // go at the end of a drag would insert whatever the finger
                // happened to land on.
                if (!quickScrolling && abs(y - quickDownY) > SLOP_DP * density) {
                    quickScrolling = true
                    quickPressed = -1
                }
                if (quickScrolling) {
                    scrollQuickMenu(quickLastY - y)
                    quickLastY = y
                    invalidate()
                    return true
                }

                // Still a press: follow the finger between rows, but only while
                // it stays inside the menu.
                val row = quickRowAt(x, y)
                if (row != quickPressed) {
                    quickPressed = row
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (quickScrolling) {
                    // A drag ends where it ends. The menu stays up, scrolled.
                    quickScrolling = false
                    return true
                }
                if (quickPressed < 0) return false
                val chosen = quickItems.getOrNull(quickPressed)
                dismissQuickMenu()
                chosen?.let { onQuickInsert?.invoke(it) }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                quickWasOpen = false
                dismissQuickMenu()
                return true
            }
        }
        return false
    }

    private fun drawQuickMenu(canvas: Canvas) {
        if (quickItems.isEmpty()) return
        val padding = QUICK_PADDING_DP * density

        // One rounded panel behind the lot, so the menu reads as a single
        // surface the list moves inside rather than as a stack of loose keys
        // that happen to slide together.
        canvas.drawRoundRect(quickViewport, keyRadius, keyRadius, quickPaint)

        canvas.save()
        canvas.clipRect(quickViewport)

        // Only the rows actually in the window, which is what keeps a long list
        // from costing anything to draw.
        val first = (quickScroll / quickRowHeight).toInt().coerceAtLeast(0)
        val last = ((quickScroll + quickViewport.height()) / quickRowHeight).toInt()
            .coerceAtMost(quickItems.lastIndex)

        for (index in first..last) {
            val top = quickRowTop(index)
            if (index == quickPressed) {
                canvas.drawRect(
                    quickViewport.left,
                    top,
                    quickViewport.right,
                    top + quickRowHeight,
                    quickPressedPaint,
                )
            }
            val baseline = top + quickRowHeight / 2f -
                (quickTextPaint.descent() + quickTextPaint.ascent()) / 2f
            // Clipped rather than ellipsised: an address that does not fit is
            // still recognisable from its front, and its front is the part that
            // distinguishes it from the other one on the list.
            canvas.drawText(quickItems[index], quickViewport.left + padding, baseline, quickTextPaint)
        }

        canvas.restore()
        drawQuickScrollbar(canvas)
    }

    /**
     * The bar down the right edge, drawn only when there is somewhere to
     * scroll to.
     *
     * Without it a menu that happens to be exactly full looks identical to one
     * with six more entries below the fold, and nothing else on this keyboard
     * scrolls — so there is no habit to fall back on.
     */
    private fun drawQuickScrollbar(canvas: Canvas) {
        if (quickMaxScroll <= 0f) return
        val total = quickItems.size * quickRowHeight
        val visible = quickViewport.height()
        val trackWidth = QUICK_SCROLLBAR_DP * density
        val inset = trackWidth / 3f

        val thumbHeight = (visible / total * visible).coerceAtLeast(trackWidth * 2f)
        val travel = visible - thumbHeight
        val top = quickViewport.top + travel * (quickScroll / quickMaxScroll)

        quickScrollbarPaint.alpha = QUICK_SCROLLBAR_ALPHA
        canvas.drawRoundRect(
            quickViewport.right - trackWidth + inset,
            top,
            quickViewport.right - inset,
            top + thumbHeight,
            trackWidth,
            trackWidth,
            quickScrollbarPaint,
        )
    }

    // -- swiping (D39) --------------------------------------------------------

    /** Whether [placed] types exactly one letter, and so can be part of a word. */
    private fun isLetter(placed: PlacedKey): Boolean {
        val text = (placed.key.action as? KeyAction.Text)?.text ?: return false
        return text.length == 1 && text[0].isLetter()
    }

    /**
     * Whether this press is the second half of a tap-then-hold on the steering
     * key, and so should steer by line rather than begin a stroke (D39).
     */
    private fun armsSteering(key: Key, now: Long): Boolean =
        key.steersLines && steerArming.arms(key, now)

    private fun rememberTap(key: Key, now: Long) = steerArming.tap(key, now)

    private fun forgetTap() = steerArming.reset()

    private fun beginRecording(pointerId: Int, x: Float, y: Float) {
        glidePointer = pointerId
        gliding = false
        glideCount = 0
        recordPoint(x, y)
    }

    /**
     * Records every sample this move event carries, not just the latest.
     *
     * The digitiser reports far faster than the display refreshes, so Android
     * **batches**: one `ACTION_MOVE` arrives per frame carrying every sample
     * taken since the last one, with all but the newest tucked away in the
     * historical arrays. Reading only the current position throws those away
     * and samples the stroke at frame rate instead of touch rate.
     *
     * That is not a cosmetic loss. A word swiped quickly can be over in a few
     * frames, and what a handful of points does to a path is cut every corner
     * off it — and the corners are the letters. Slow, careful strokes decode
     * fine either way, which is exactly what makes the bug confusing from the
     * outside: it looks like the keyboard is worse at the words you know best.
     */
    private fun recordBatch(event: MotionEvent, index: Int, x: Float, y: Float) {
        for (h in 0 until event.historySize) {
            recordPoint(event.getHistoricalX(index, h), event.getHistoricalY(index, h))
        }
        recordPoint(x, y)
    }

    private fun recordPoint(x: Float, y: Float) {
        if (glideCount >= glideX.size) decimate()
        glideX[glideCount] = x
        glideY[glideCount] = y
        glideCount++
    }

    /**
     * Halves the stored stroke by dropping every other point, so that a very
     * long word keeps its whole shape rather than losing its tail.
     *
     * Truncating instead would be worse than it sounds: the *last* letter is
     * one of the two the search is bounded by, so a stroke cut short is not a
     * blurry answer but a wrong one.
     */
    private fun decimate() {
        var kept = 0
        var i = 0
        while (i < glideCount) {
            glideX[kept] = glideX[i]
            glideY[kept] = glideY[i]
            kept++
            i += 2
        }
        glideCount = kept
    }

    private fun abandonGlide() {
        glidePointer = MotionEvent.INVALID_POINTER_ID
        glideCount = 0
        if (gliding || glideSettled) {
            gliding = false
            glideSettled = false
            invalidate()
        }
    }

    /**
     * Ends the stroke but leaves it on screen, so the finger can be lifted and
     * the result looked at side by side with what produced it.
     */
    private fun finishGlide(): GesturePath? {
        val path = GesturePath.of(glideX, glideY, glideCount)
        glidePointer = MotionEvent.INVALID_POINTER_ID
        gliding = false
        glideSettled = path != null
        if (!glideSettled) glideCount = 0
        return path
    }

    /** Clears a settled stroke once something else happens. */
    private fun clearSettledGlide() {
        if (!glideSettled) return
        glideSettled = false
        glideCount = 0
        invalidate()
    }

    /**
     * The stroke as it is being drawn: one ribbon in the trail's purple, fading
     * out behind the finger.
     *
     * Drawn in a fixed number of chunks rather than segment by segment. The
     * buffer can hold hundreds of points and this runs on every frame of the
     * gesture, which is the one moment the keyboard is doing the most work.
     */
    private fun drawGlide(canvas: Canvas) {
        if (!gliding && !glideSettled) return
        if (glideCount < 2) return
        val chunks = GLIDE_FADE_CHUNKS.coerceAtMost(glideCount - 1)
        val per = (glideCount - 1).toFloat() / chunks

        // A settled stroke is evidence rather than feedback, so it steps back:
        // dimmer overall, and no longer competing with the word it produced.
        val ceiling = if (glideSettled) GLIDE_SETTLED_ALPHA else 255

        glidePaint.style = Paint.Style.STROKE
        glidePaint.strokeWidth = GLIDE_STROKE_DP * density
        for (chunk in 0 until chunks) {
            val from = (chunk * per).toInt()
            val to = ((chunk + 1) * per).toInt().coerceAtMost(glideCount - 1)
            if (to <= from) continue

            glideRender.reset()
            glideRender.moveTo(glideX[from], glideY[from])
            for (i in from + 1..to) glideRender.lineTo(glideX[i], glideY[i])

            // Oldest faintest, so the ribbon reads as a direction rather than
            // as a shape someone has to interpret.
            val share = (chunk + 1).toFloat() / chunks
            glidePaint.alpha = (GLIDE_MIN_ALPHA + (ceiling - GLIDE_MIN_ALPHA) * share).toInt()
            canvas.drawPath(glideRender, glidePaint)
        }

        drawGlideEnds(canvas, ceiling)
    }

    /**
     * Rings the two ends of the stroke.
     *
     * These two points are not decoration: the first and last letters are what
     * bound the whole dictionary search (D39), so if a swipe found the wrong
     * word the first thing to check is whether these rings are sitting on the
     * keys that were meant. The start is drawn hollow and the end filled, so
     * which way the stroke ran is readable from the still picture.
     */
    private fun drawGlideEnds(canvas: Canvas, ceiling: Int) {
        val radius = GLIDE_END_RADIUS_DP * density
        glidePaint.alpha = ceiling

        glidePaint.style = Paint.Style.STROKE
        glidePaint.strokeWidth = GLIDE_END_STROKE_DP * density
        canvas.drawCircle(glideX[0], glideY[0], radius, glidePaint)

        glidePaint.style = Paint.Style.FILL
        canvas.drawCircle(glideX[glideCount - 1], glideY[glideCount - 1], radius, glidePaint)
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

        // -- swiping (D39) ----------------------------------------------------

        /**
         * How far a finger must travel before leaving a letter can begin a
         * word, on top of having to reach a different letter key.
         *
         * The crossing does most of the work; this only rules out the case
         * where the press landed in the sliver of a key's hit area that belongs
         * to its neighbour and never really moved at all.
         */
        const val GLIDE_START_DP = 12f

        /** How many points the stroke buffer holds before it is thinned. */
        const val GLIDE_CAPACITY = 512

        const val GLIDE_STROKE_DP = 5f

        /** Chunks the ribbon is faded across. Enough to look continuous. */
        const val GLIDE_FADE_CHUNKS = 16

        /** How faint the oldest end of the ribbon gets. */
        const val GLIDE_MIN_ALPHA = 40

        /**
         * How strong a stroke stays after the finger has gone. Dimmer than the
         * live one: it is there to be consulted, not to be watched.
         */
        const val GLIDE_SETTLED_ALPHA = 150

        /** The rings on the two ends — the letters that bound the search. */
        const val GLIDE_END_RADIUS_DP = 9f
        const val GLIDE_END_STROKE_DP = 3f

        // -- the quick menu (D40) ---------------------------------------------

        /** Shorter than a key: these rows are read, not aimed at blind. */
        const val QUICK_ROW_DP = 44f
        const val QUICK_PADDING_DP = 12f

        /**
         * How many rows are on screen at once. Not a limit on the menu — it
         * scrolls — only on how much of the keyboard it is allowed to cover
         * while it is up.
         */
        const val QUICK_MAX_ROWS = 6

        const val QUICK_SCROLLBAR_DP = 6f
        const val QUICK_SCROLLBAR_ALPHA = 90

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
