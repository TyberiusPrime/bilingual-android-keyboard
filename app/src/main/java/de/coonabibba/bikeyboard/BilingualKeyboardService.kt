package de.coonabibba.bikeyboard

import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * The IME. Everything the keyboard is allowed to know about the app it is
 * typing into arrives through [EditorInfo] (once, at start) and the
 * `InputConnection` (a live, asynchronous channel to the text field).
 */
class BilingualKeyboardService : InputMethodService() {

    private lateinit var keyboardView: KeyboardView
    private var inputRoot: FrameLayout? = null
    private var layer = Layer.LETTERS
    private var shifted = false

    override fun onCreateInputView(): View {
        keyboardView = KeyboardView(this).apply {
            layout = Layouts.forLayer(layer)
            onKey = ::handleKey
            onAlternate = ::handleAlternate
        }

        // The keys live inside a container that carries the navigation-bar
        // inset as bottom padding. targetSdk 35 means edge-to-edge is mandatory
        // and the system no longer insets the IME window for us, so without
        // this the system's own hide-keyboard chevron, IME-switcher globe and
        // gesture pill are drawn on top of the bottom row — and swallow taps
        // meant for it.
        val root = FrameLayout(this).apply {
            addView(
                keyboardView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            view.updatePadding(bottom = insets.navigationBarBottom())
            insets
        }
        inputRoot = root
        return root
    }

    /**
     * The inset listener above is not reliably dispatched to an IME's input
     * view, so the value is also read directly whenever input starts.
     */
    private fun applyNavigationBarInset() {
        val root = inputRoot ?: return
        val insets = window?.window?.decorView?.rootWindowInsets ?: return
        root.updatePadding(bottom = WindowInsetsCompat.toWindowInsetsCompat(insets).navigationBarBottom())
    }

    private fun WindowInsetsCompat.navigationBarBottom(): Int =
        getInsets(WindowInsetsCompat.Type.navigationBars()).bottom

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        applyNavigationBarInset()
        // A password field must never reach prediction, logging or a learned
        // dictionary. Recorded here so later stages can honour it.
        val isPassword = isPasswordField(info)
        layer = if (isNumericField(info)) Layer.SYMBOLS else Layer.LETTERS
        shifted = !isPassword && shouldAutoCapitalise(info)
        keyboardView.layout = Layouts.forLayer(layer)
        keyboardView.shifted = shifted

        // A new field is a new context: nothing typed here yet.
        expectedCursor = info.initialSelEnd
        clearTrail()
    }

    private fun handleKey(key: Key) {
        val ic = currentInputConnection ?: return
        when (val action = key.action) {
            is KeyAction.Text -> {
                val text = if (shifted) action.text.uppercase() else action.text
                ic.commitText(text, 1)
                noteInsertion(key, alternate = false, length = text.length)
                if (shifted) {
                    shifted = false
                    keyboardView.shifted = false
                }
            }

            KeyAction.Space -> {
                ic.commitText(" ", 1)
                noteInsertion(key, alternate = false, length = 1)
            }

            KeyAction.Backspace -> {
                val selected = ic.getSelectedText(0)
                if (selected.isNullOrEmpty()) {
                    ic.deleteSurroundingText(1, 0)
                    if (expectedCursor > 0) expectedCursor -= 1
                } else {
                    ic.commitText("", 1)
                    expectedCursor = -1
                }
                popTrail()
            }

            KeyAction.Enter -> {
                val action1 = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
                if (action1 != null && action1 != EditorInfo.IME_ACTION_NONE &&
                    action1 != EditorInfo.IME_ACTION_UNSPECIFIED
                ) {
                    ic.performEditorAction(action1)
                } else {
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                }
            }

            KeyAction.Shift -> {
                shifted = !shifted
                keyboardView.shifted = shifted
            }

            KeyAction.ToggleLayer -> {
                layer = Layouts.other(layer)
                keyboardView.layout = Layouts.forLayer(layer)
            }

            KeyAction.NextInputMethod -> switchToNextInputMethod(false)
        }
    }

    /**
     * A long-press alternate. The view has already applied shift, so this
     * commits verbatim — but it still consumes a one-shot shift, so holding
     * `a` for `Ä` does not leave the next letter capitalised too.
     */
    private fun handleAlternate(key: Key, text: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
        noteInsertion(key, alternate = true, length = text.length)
        if (shifted) {
            shifted = false
            keyboardView.shifted = false
        }
    }

    // -- recent keypress trail ----------------------------------------------

    /**
     * The last [TRAIL_CAPACITY] insertions, most recent first.
     *
     * Only insertions go on the stack: backspace pops it rather than pushing,
     * and modifiers (shift, layer toggle, globe) are not things you "typed".
     * Enter is excluded too — it usually submits the field rather than adding
     * to the text you are looking at.
     */
    private val trail = ArrayDeque<TrailEntry>()

    private fun noteInsertion(key: Key, alternate: Boolean, length: Int) {
        trail.addFirst(TrailEntry(key, alternate))
        while (trail.size > TRAIL_CAPACITY) trail.removeLast()
        if (expectedCursor >= 0) expectedCursor += length
        publishTrail()
    }

    private fun popTrail() {
        trail.removeFirstOrNull()
        publishTrail()
    }

    private fun clearTrail() {
        if (trail.isEmpty()) return
        trail.clear()
        publishTrail()
    }

    private fun publishTrail() {
        keyboardView.trail = trail.toList()
    }

    /**
     * Where the cursor should be if the only thing that moved it was us.
     * -1 means "unknown", in which case no self-edit claim can be made.
     *
     * This is the smallest useful piece of the editor-I/O bookkeeping the
     * design document defers to roadmap step 3, and it is deliberately
     * conservative: anything it cannot account for clears the trail.
     */
    private var expectedCursor = -1

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )
        val ourOwnEdit = newSelStart == newSelEnd && newSelStart == expectedCursor
        if (!ourOwnEdit) clearTrail()
        expectedCursor = newSelEnd
    }

    private fun isPasswordField(info: EditorInfo): Boolean {
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD

            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun isNumericField(info: EditorInfo): Boolean =
        when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> true
            else -> false
        }

    private companion object {
        /**
         * Deeper than the five steps the trail actually colours, so that
         * backspacing past the visible gradient keeps revealing older presses
         * instead of running out.
         */
        const val TRAIL_CAPACITY = 10
    }

    private fun shouldAutoCapitalise(info: EditorInfo): Boolean {
        if (info.inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        val caps = info.inputType and (
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_CAP_WORDS or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            )
        if (caps == 0) return false
        return currentInputConnection?.getCursorCapsMode(info.inputType)?.let { it != 0 } ?: false
    }
}
