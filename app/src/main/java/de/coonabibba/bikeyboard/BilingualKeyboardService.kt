package de.coonabibba.bikeyboard

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import java.io.File
import java.util.concurrent.Executors

/**
 * The IME. Everything the keyboard is allowed to know about the app it is
 * typing into arrives through [EditorInfo] (once, at start) and the
 * `InputConnection` (a live, asynchronous channel to the text field).
 */
class BilingualKeyboardService : InputMethodService() {

    private lateinit var keyboardView: KeyboardView
    private lateinit var suggestionStrip: SuggestionStripView
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
        suggestionStrip = SuggestionStripView(this).apply { onPick = ::pickEntry }

        // Strip above keys. Child clipping is off so that a long-press popup on
        // the top row, drawn by the keyboard view at a negative y, overhangs
        // into the strip's band instead of being cut off at the top row — and
        // the keyboard view is added last so it draws over the strip rather
        // than under it.
        val stack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(
                suggestionStrip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                keyboardView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
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
            clipChildren = false
            clipToPadding = false
            addView(
                stack,
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
        val isPassword = FieldPolicy.isPassword(info.inputType)
        layer = if (FieldPolicy.isNumeric(info.inputType)) Layer.SYMBOLS else Layer.LETTERS
        shifted = !isPassword && shouldAutoCapitalise(info)
        keyboardView.layout = Layouts.forLayer(layer)
        keyboardView.shifted = shifted

        // A new field is a new context: nothing typed here yet.
        expectedCursor = info.initialSelEnd
        clearTrail()

        suggestionsAllowed = FieldPolicy.suggestionsAllowed(info.inputType)
        learningAllowed = FieldPolicy.learningAllowed(info.inputType, info.imeOptions)
        // Focus can land in the middle of existing text, and what precedes the
        // cursor there is text this keyboard did not type. Only a cursor at
        // position zero means there is nothing in front of it to be wrong
        // about; -1, which is what the field reports when it does not know
        // where the cursor is, is not that.
        word.reset(known = info.initialSelStart == 0)
        spaceGesture.otherInput()
        refreshSuggestions()
    }

    private fun handleKey(key: Key) {
        val ic = currentInputConnection ?: return
        when (val action = key.action) {
            is KeyAction.Text -> {
                val text = if (shifted) action.text.uppercase() else action.text
                ic.commitText(text, 1)
                noteInsertion(key, alternate = false, text = text)
                if (shifted) {
                    shifted = false
                    keyboardView.shifted = false
                }
            }

            KeyAction.Space -> {
                if (spaceGesture.tap(SystemClock.uptimeMillis())) {
                    sentenceEnd(ic, key)
                } else {
                    insertSpace(ic, key)
                }
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
                spaceGesture.otherInput()
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
        noteInsertion(key, alternate = true, text = text)
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
        noteInsertion(key, alternate = false, text = " ")
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

        // The space that was there is gone and a full stop and space stand in
        // its place; either way the word ended.
        word.insert(". ")
        refreshSuggestions()

        shifted = true
        keyboardView.shifted = true
    }

    private fun deleteOne(ic: InputConnection) {
        val selected = ic.getSelectedText(0)
        if (selected.isNullOrEmpty()) {
            ic.deleteSurroundingText(1, 0)
            if (expectedCursor > 0) expectedCursor -= 1
            word.deleteOne()
        } else {
            ic.commitText("", 1)
            expectedCursor = -1
            // A selection can span anything at all; what is left in front of
            // the cursor is not ours to describe.
            word.reset(known = false)
        }
        popTrail()
        spaceGesture.otherInput()
        refreshSuggestions()
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

        // Deleting back to a whitespace boundary leaves no word in progress —
        // unless the read hit its limit, in which case a longer word may still
        // be standing and we no longer know what is in front of the cursor.
        word.deleteWord(complete = count < before.length)
        spaceGesture.otherInput()
        refreshSuggestions()
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
        spaceGesture.otherInput()
        // The resulting onUpdateSelection will not match expectedCursor, which
        // clears the trail — correct per D19, since the run of typing is over.
    }

    // -- space taps ----------------------------------------------------------

    /**
     * When a tap on space ends the sentence instead of inserting one: a second
     * quick tap, or the first tap after a suggestion put a space there. See
     * [SpaceGesture].
     */
    private val spaceGesture = SpaceGesture()

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

    private fun noteInsertion(key: Key, alternate: Boolean, text: String) {
        trail.addFirst(TrailEntry(key, alternate))
        while (trail.size > TRAIL_CAPACITY) trail.removeLast()
        if (expectedCursor >= 0) expectedCursor += text.length
        publishTrail()
        word.insert(text)
        // Anything that is not a space breaks up a run of them. The space bar's
        // own taps are recorded by the gesture itself, before it gets here.
        if (text != " ") spaceGesture.otherInput()
        refreshSuggestions()
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

    // -- suggestions ---------------------------------------------------------

    /**
     * The word being typed, tracked from our own edits rather than read back
     * per keystroke. See [WordInProgress] for why, and for what happens when it
     * loses track.
     */
    private val word = WordInProgress()

    /** False in password, no-suggestion and non-prose fields (see [FieldPolicy]). */
    private var suggestionsAllowed = true

    /** False additionally in fields that ask not to be learned from (D8). */
    private var learningAllowed = true

    /**
     * The shipped wordlists plus the personal store, once they have been read.
     * [NoSuggestions] until then, which is a moment at the start of a session
     * and never again — the strip is empty rather than absent, per D9.
     */
    @Volatile
    private var suggestionSource: SuggestionSource = NoSuggestions

    /** The user's own words. The only thing that ever teaches this keyboard (D8). */
    private val personalStore by lazy { PersonalStore(File(filesDir, PERSONAL_WORDS_FILE)) }

    /** Disk work — reading the wordlists, appending a word — never on the typing thread. */
    private val diskThread = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bikeyboard-disk").apply { priority = Thread.MIN_PRIORITY }
    }

    override fun onCreate() {
        super.onCreate()
        // Once per service, not once per field: this is the whole reason
        // dictionaries are loaded here rather than in onStartInput.
        diskThread.execute {
            personalStore.load()
            val source = DictionarySuggestions(
                lexicons = listOf(
                    Wordlists.load(assets::open, Wordlists.GERMAN),
                    Wordlists.load(assets::open, Wordlists.ENGLISH),
                ),
                personal = personalStore,
            )
            suggestionSource = source
            mainHandler.post(::refreshSuggestions)
        }
    }

    override fun onDestroy() {
        diskThread.shutdown()
        super.onDestroy()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun refreshSuggestions() {
        if (!::suggestionStrip.isInitialized) return
        if (!suggestionsAllowed || !word.known) {
            suggestionStrip.slots = emptyList()
            return
        }

        val source = suggestionSource
        val candidates = source.suggest(word.text).map { StripEntry.Word(it) }
        val offer = addWordOffer(source)

        // The add-word offer keeps the rightmost slot to itself, in the same
        // place whether or not there are candidates beside it. A feedback
        // channel that moves around is one that gets mis-tapped, and under D8
        // it is the only one there is.
        suggestionStrip.slots = if (offer == null) {
            candidates
        } else {
            List(SuggestionSlots.CAPACITY - 1) { candidates.getOrNull(it) } + offer
        }
    }

    /**
     * The word in progress, if it is worth offering to remember.
     *
     * Only for a word nothing recognises, only where the field permits learning,
     * and only once it is long enough to be a word rather than the start of one.
     */
    private fun addWordOffer(source: SuggestionSource): StripEntry.AddWord? {
        if (!learningAllowed) return null
        val typed = word.text.toString()
        if (typed.length < MIN_ADDABLE_LENGTH) return null
        if (source.knows(typed)) return null
        return StripEntry.AddWord(typed)
    }

    private fun pickEntry(entry: StripEntry) {
        when (entry) {
            is StripEntry.Word -> pickSuggestion(entry.suggestion)
            is StripEntry.AddWord -> addWord(entry.word)
        }
    }

    /**
     * Remembers a word. The text is not touched — the word is already typed;
     * what was missing is the keyboard knowing it.
     */
    private fun addWord(text: String) {
        if (!learningAllowed) return
        if (!personalStore.add(text)) return
        diskThread.execute { personalStore.persist() }
        refreshSuggestions()
    }

    /**
     * A suggestion was tapped: it replaces the word in progress, followed by a
     * space, because accepting a word is also finishing it.
     *
     * The characters to remove are the ones this keyboard believes it typed —
     * [WordInProgress] refuses to claim a word it cannot account for, and no
     * suggestion is offered while it does, so there is nothing to count
     * backwards through here.
     */
    private fun pickSuggestion(suggestion: Suggestion) {
        val ic = currentInputConnection ?: return
        val replaced = word.text.length
        val text = if (shifted) suggestion.text.replaceFirstChar { it.uppercase() } else suggestion.text
        val committed = "$text "

        ic.beginBatchEdit()
        if (replaced > 0) ic.deleteSurroundingText(replaced, 0)
        ic.commitText(committed, 1)
        ic.endBatchEdit()

        if (expectedCursor >= 0) expectedCursor += committed.length - replaced
        if (shifted) {
            shifted = false
            keyboardView.shifted = false
        }
        // A word that arrived from the strip was not typed on the keys, so
        // there is nothing for the trail to colour (D19).
        clearTrail()
        word.reset(known = true)
        // The space just committed is the first half of the double-space full
        // stop, so one more tap on space ends the sentence (D6).
        spaceGesture.suggestionAccepted()
        refreshSuggestions()
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
        if (!ourOwnEdit) {
            clearTrail()
            // The cursor is somewhere we did not put it, so the text in front
            // of it is not the word we were tracking.
            word.reset(known = false)
            spaceGesture.otherInput()
            refreshSuggestions()
        }
        expectedCursor = newSelEnd
    }

    private companion object {
        /**
         * Deeper than the five steps the trail actually colours, so that
         * backspacing past the visible gradient keeps revealing older presses
         * instead of running out.
         */
        const val TRAIL_CAPACITY = 10

        /** How far back to read when deleting a word. Longer than any real word. */
        const val WORD_LOOKBEHIND = 64

        /** Where the personal store lives, in ordinary credential-encrypted storage (D18). */
        const val PERSONAL_WORDS_FILE = "personal-words.txt"

        /**
         * Below this, the word in progress is the start of typing rather than a
         * word, and offering to remember it would fire on every second letter.
         */
        const val MIN_ADDABLE_LENGTH = 3
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
