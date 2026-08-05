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
import androidx.core.graphics.ColorUtils

/**
 * The suggestion strip (D9).
 *
 * Permanently present, fixed height, so the keyboard never changes size while
 * typing. It occupies the band that used to be reserved as a plain gutter above
 * the keys, which is why adding it costs no height: the space was spent in
 * advance for exactly this.
 *
 * Three things are drawn per slot, and each of them means something: the word,
 * a wash of colour saying which language it came from (D4), and purple text
 * when the candidate holds most of the matching mass. The last of those is
 * appearance only — D3's auto-replace threshold does not exist yet, and this is
 * not it.
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
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    // A TextPaint rather than a Paint because ellipsizing wants one.
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        // Nearly the size of a key label. A suggestion you have to squint at is
        // slower to read than retyping the word.
        textSize = 19f * density
    }

    private val surface = ContextCompat.getColor(context, R.color.keyboard_background)

    /**
     * A wash of colour behind a candidate saying which language it came from.
     *
     * This is D4's language indicator: subtle, non-interactive, per candidate
     * rather than a mode — because per D2 the language belongs to the word and
     * not to the keyboard. Gold for German and blue for English is a mnemonic
     * from the flags and nothing deeper; what matters is that it stays the same.
     *
     * Kept at [TINT_STRENGTH] of the way from the keyboard surface to the hue,
     * so it reads as a tint rather than as a highlight — the highlight means
     * something else here.
     */
    private fun tintFor(language: Language?): Int = when (language) {
        Language.GERMAN -> ColorUtils.blendARGB(surface, GERMAN_HUE, TINT_STRENGTH)
        Language.ENGLISH -> ColorUtils.blendARGB(surface, ENGLISH_HUE, TINT_STRENGTH)
        // The personal store belongs to no language, and looking different is
        // the honest thing for it to do.
        null -> surface
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
            } else if (entry is StripEntry.Word) {
                tintPaint.color = tintFor(entry.suggestion.language)
                if (tintPaint.color != surface) {
                    canvas.drawRect(left, 0f, right, height.toFloat(), tintPaint)
                }
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

            textPaint.color = when {
                // Not a word to insert, so it does not look like one.
                entry is StripEntry.AddWord -> ADD_WORD_FG
                // A candidate holding most of the matching mass. Purple is the
                // keyboard's "this came from the machine" colour, the same one
                // the keypress trail uses.
                entry is StripEntry.Word && entry.suggestion.confidence >= HIGH_CONFIDENCE ->
                    CONFIDENT_FG

                else -> Color.WHITE
            }

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

        /**
         * Where a candidate stops being one option among several and starts
         * looking like the answer. Purely how it is drawn — **not** D3's
         * auto-replace threshold, which does not exist yet and will be a
         * calibrated number rather than this unigram share. A guess, to be
         * moved once there is a week of typing to move it against.
         */
        const val HIGH_CONFIDENCE = 0.5f

        /** How far from the keyboard surface towards a language's hue. Slight, deliberately. */
        const val TINT_STRENGTH = 0.10f

        val PRESSED_BG = Color.parseColor("#3A3A3C")
        val DIVIDER = Color.parseColor("#3A3A3C")

        /** Purple, as on the keypress trail: the keyboard's own voice. */
        val CONFIDENT_FG = Color.parseColor("#B79CF8")

        /** Meta rather than text, so it reads as an action and not as a word. */
        val ADD_WORD_FG = Color.parseColor("#9A9A9E")

        val GERMAN_HUE = Color.parseColor("#F2B233")
        val ENGLISH_HUE = Color.parseColor("#3B82F6")
    }
}
