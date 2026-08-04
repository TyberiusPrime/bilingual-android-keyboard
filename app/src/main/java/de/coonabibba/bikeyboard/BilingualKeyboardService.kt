package de.coonabibba.bikeyboard

import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
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
            onRepeat = ::handleRepeat
            onCursorStep = ::moveCursor
            onDeleteWord = ::handleDeleteWord
        }

        // The keys live inside a container that carries the navigation-bar
        // inset as bottom padding. targetSdk 35 means edge-to-edge is mandatory
        // and the system no longer insets the IME window for us, so without
        // this the system's own hide-keyboard chevron, IME-switcher globe and
        // gesture pill are drawn on top of the bottom row — and swallow taps
        // meant for it.
        val root = FrameLayout(this).apply {
            // Also opaque, so the navigation-bar padding below the keys is part
            // of the keyboard rather than a window onto the app.
            setBackgroundColor(ContextCompat.getColor(context, R.color.keyboard_background))
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
                if (isDoubleTap(key)) sentenceEnd(ic, key) else insertSpace(ic, key)
            }

            // No double tap here: two quick taps are what you do when you want
            // two letters gone, so it fired constantly by accident. Deleting a
            // word is a leftward swipe instead (D20).
            KeyAction.Backspace -> deleteOne(ic)

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

    /** Leftward swipe on backspace, one call per word. */
    private fun handleDeleteWord() {
        val ic = currentInputConnection ?: return
        deleteWordBefore(ic)
    }

    /** Auto-repeat ticks from a held key. Never counts towards a double tap. */
    private fun handleRepeat(key: Key) {
        val ic = currentInputConnection ?: return
        if (key.action == KeyAction.Backspace) deleteOne(ic)
    }

    // -- space and backspace -------------------------------------------------

    private fun insertSpace(ic: InputConnection, key: Key) {
        ic.commitText(" ", 1)
        noteInsertion(key, alternate = false, length = 1)
    }

    /**
     * Double-tapping space ends the sentence: the space just typed becomes
     * `". "`, and capitalisation re-arms (D6).
     *
     * Only when a word actually precedes the space — after punctuation, a
     * newline, or nothing at all, a second space is just a space.
     */
    private fun sentenceEnd(ic: InputConnection, key: Key) {
        if (!TextEdits.endsSentenceOnDoubleSpace(ic.getTextBeforeCursor(2, 0))) {
            insertSpace(ic, key)
            return
        }

        ic.beginBatchEdit()
        ic.deleteSurroundingText(1, 0)
        ic.commitText(". ", 1)
        ic.endBatchEdit()

        if (expectedCursor >= 0) expectedCursor += 1
        popTrail()
        trail.addFirst(TrailEntry(key, alternate = false))
        publishTrail()

        shifted = true
        keyboardView.shifted = true
    }

    private fun deleteOne(ic: InputConnection) {
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

    /**
     * Removes the word before the cursor. The press that began the swipe has
     * already taken one character, so what is left is everything back to the
     * preceding whitespace — trailing whitespace first, so deleting from just
     * after a word does not merely eat the gap.
     */
    private fun deleteWordBefore(ic: InputConnection) {
        val before = ic.getTextBeforeCursor(WORD_LOOKBEHIND, 0)
        if (before.isNullOrEmpty()) return

        val count = TextEdits.wordDeletionCount(before)
        if (count <= 0) return

        ic.deleteSurroundingText(count, 0)
        if (expectedCursor >= count) expectedCursor -= count else expectedCursor = -1
        repeat(count) { trail.removeFirstOrNull() }
        publishTrail()
    }

    /** Space-bar drag. One step per character, in either direction. */
    private fun moveCursor(direction: Int) {
        val ic = currentInputConnection ?: return

        // A DPAD event the text field cannot consume — because the cursor is
        // already at the end, or at the start — is not swallowed. It falls
        // through to focus navigation, focus leaves the field, the input
        // connection ends and the keyboard disappears. So check there is
        // somewhere to move to before asking to move.
        val canMove = if (direction > 0) {
            !ic.getTextAfterCursor(1, 0).isNullOrEmpty()
        } else {
            !ic.getTextBeforeCursor(1, 0).isNullOrEmpty()
        }
        if (!canMove) return

        val code = if (direction > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        // The resulting onUpdateSelection will not match expectedCursor, which
        // clears the trail — correct per D19, since the run of typing is over.
    }

    // -- double tap ----------------------------------------------------------

    private var lastTapKey: Key? = null
    private var lastTapAt = 0L

    private fun isDoubleTap(key: Key): Boolean {
        val now = SystemClock.uptimeMillis()
        val isRepeat = key == lastTapKey && now - lastTapAt <= DOUBLE_TAP_MS
        // Consumed either way, so a third tap starts a fresh pair rather than
        // chaining another word deletion off the same gesture.
        lastTapKey = if (isRepeat) null else key
        lastTapAt = now
        return isRepeat
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

        /** Window for a second tap to count as a double tap rather than a new one. */
        const val DOUBLE_TAP_MS = 350L

        /** How far back to read when deleting a word. Longer than any real word. */
        const val WORD_LOOKBEHIND = 64
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
