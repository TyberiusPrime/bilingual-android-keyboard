package de.coonabibba.bikeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat

/**
 * The suggestion strip (D9).
 *
 * Permanently present, fixed height, so the keyboard never changes size while
 * typing. It occupies the band that used to be reserved as a plain gutter above
 * the keys, which is why adding it costs no height: the space was spent in
 * advance for exactly this.
 *
 * It is still the keyboard's tallest piece of nothing — until roadmap step 4
 * there are no dictionaries, so [suggestions] stays empty and the strip renders
 * as bare surface. What is real here is the mechanism: fixed slots, a tap that
 * commits the slot it started on, and a callback the service turns into an
 * edit.
 *
 * A long-press popup from the top row of keys is drawn *over* this view. The
 * keyboard view is the later child of a container with child clipping switched
 * off, so its popups overhang upwards into this band rather than being clipped
 * or covering the key under the finger.
 */
class SuggestionStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Called when a slot is chosen. */
    var onPick: ((StripEntry) -> Unit)? = null

    /**
     * What each slot holds, `null` for an empty one.
     *
     * Always exactly [SuggestionSlots.CAPACITY] long, padded here if the caller
     * gives fewer. Slots are addressed rather than filled left to right so that
     * the add-word offer can keep the rightmost one to itself and stay in the
     * same place whether there are two candidates beside it or none.
     */
    var slots: List<StripEntry?> = List(SuggestionSlots.CAPACITY) { null }
        set(value) {
            val padded = List(SuggestionSlots.CAPACITY) { value.getOrNull(it) }
            if (padded == field) return
            field = padded
            // The finger is still down on a slot whose contents just changed
            // underneath it; committing what is there now is not what was
            // aimed at.
            pressedSlot = -1
            invalidate()
        }

    private var pressedSlot = -1

    private val density = resources.displayMetrics.density
    private val stripHeight = resources.getDimension(R.dimen.suggestion_strip_height)

    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PRESSED_BG }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DIVIDER }
    // A TextPaint rather than a Paint because ellipsizing wants one.
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 16f * density
    }

    init {
        // Nothing paints behind an IME window; without an opaque surface the app
        // being typed into shows through. Same reason the keyboard view has one.
        setBackgroundColor(ContextCompat.getColor(context, R.color.keyboard_background))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), stripHeight.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val baseline = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f

        slots.forEachIndexed { index, entry ->
            if (entry == null) return@forEachIndexed
            val left = SuggestionSlots.left(w, index)
            val right = SuggestionSlots.right(w, index)

            if (index == pressedSlot) {
                canvas.drawRect(left, 0f, right, height.toFloat(), pressedPaint)
            }

            // A divider only between two occupied slots, never trailing off
            // into the empty part of the strip.
            if (index > 0 && slots[index - 1] != null) {
                canvas.drawRect(
                    left,
                    height * 0.25f,
                    left + DIVIDER_WIDTH_DP * density,
                    height * 0.75f,
                    dividerPaint,
                )
            }

            // The add-word offer is not a word to insert, so it does not look
            // like one.
            textPaint.color = if (entry is StripEntry.AddWord) ADD_WORD_FG else Color.WHITE

            val room = right - left - 2f * SLOT_PADDING_DP * density
            val label = TextUtils.ellipsize(
                entry.label,
                textPaint,
                room,
                TextUtils.TruncateAt.END,
            )
            canvas.drawText(label, 0, label.length, (left + right) / 2f, baseline, textPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val slot = SuggestionSlots.indexAt(width.toFloat(), event.x)
                pressedSlot = if (slot >= 0 && slots[slot] != null) slot else -1
                if (pressedSlot >= 0) invalidate()
            }

            MotionEvent.ACTION_UP -> {
                // Commits the slot the press started on, not the one under the
                // release point — the same rule the keys follow, for the same
                // reason: a thumb that rolls sideways meant the thing it landed
                // on.
                val chosen = slots.getOrNull(pressedSlot)
                if (pressedSlot >= 0) {
                    pressedSlot = -1
                    invalidate()
                }
                chosen?.let { onPick?.invoke(it) }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (pressedSlot >= 0) {
                    pressedSlot = -1
                    invalidate()
                }
            }
        }
        return true
    }

    private companion object {
        const val DIVIDER_WIDTH_DP = 1f
        const val SLOT_PADDING_DP = 8f
        val PRESSED_BG = Color.parseColor("#3A3A3C")
        val DIVIDER = Color.parseColor("#3A3A3C")

        /** The trail's purple, so "this one is not a word to insert" reads at a glance. */
        val ADD_WORD_FG = Color.parseColor("#B79CF8")
    }
}
