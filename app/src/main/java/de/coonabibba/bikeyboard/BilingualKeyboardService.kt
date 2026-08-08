package de.coonabibba.bikeyboard

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
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
import java.io.FileInputStream
import java.io.IOException
import java.nio.channels.FileChannel
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

    /**
     * Shift latched on, until it is tapped off (D23). [shifted] stays true the
     * whole time it is, so everything that asks "should this letter be
     * capitalised" keeps asking one question.
     */
    private var capsLock = false

    /** Double-tapping shift latches it; see [DoubleTap]. */
    private var shiftTaps = DoubleTap()

    /**
     * Which revision of the settings the current input view was built for. The
     * views bake sizes and timings in when they are made, so a change means
     * making them again (D30).
     */
    private var viewsBuiltForRevision = -1

    /**
     * Null until the input view is built, and deliberately not a placeholder
     * instance.
     *
     * A `Service` field initialiser runs before `attachBaseContext`, so anything
     * built here is handed a context that cannot answer questions yet. A
     * placeholder object that merely *happens* not to ask any is one refactor
     * away from crashing the keyboard on launch — which is exactly how it
     * crashed once.
     */
    private var haptics: Haptics? = null

    /**
     * Whether this field wants sentences capitalised (D42).
     *
     * Read once from the `EditorInfo`, but *applied* after every edit rather
     * than only when focus arrives. It used to be applied once and never again,
     * so the first sentence began with a capital and no other did — and since
     * the only thing that ever turned shift back on was the double-space full
     * stop, every question and every exclamation was followed by a lowercase
     * letter, `?` and `!` being reachable only by long-press.
     */
    private var autoCapitalise = false

    /**
     * Whether the keypress trail is being drawn, and which of the two settings
     * that answer came from (D19, D38).
     *
     * A password field selects the other setting rather than overruling the
     * toggle. The first version did overrule it, which made the key inert in
     * exactly the field where somebody might most want to press it — and a
     * control that silently does nothing is worse than one that is absent.
     */
    private var showTrail = KeyboardPrefs.DEFAULT_SHOW_TRAIL
    private var passwordField = false

    /** Whether this field has lines to move between at all — see [moveCursorByLine]. */
    private var lineSteeringAllowed = false

    private var autoCorrectEnabled = KeyboardPrefs.DEFAULT_AUTO_CORRECT
    private var autoCorrectConfidence = KeyboardPrefs.DEFAULT_AUTO_CORRECT_CONFIDENCE / 100f

    override fun onCreateInputView(): View {
        viewsBuiltForRevision = KeyboardPrefs.revision(this)
        haptics = Haptics.fromPrefs(this)
        autoCorrectEnabled = KeyboardPrefs.autoCorrect(this)
        autoCorrectConfidence = KeyboardPrefs.autoCorrectConfidence(this)
        // The dictionaries are read once per service, so the falloff is pushed
        // into the scorer rather than being a reason to reload them (D34).
        applyFalloff()
        spaceGesture = SpaceGesture(KeyboardPrefs.timing(this, KeyboardPrefs.DOUBLE_TAP_MS))
        shiftTaps = DoubleTap(KeyboardPrefs.timing(this, KeyboardPrefs.DOUBLE_TAP_MS))

        keyboardView = KeyboardView(this).apply {
            layout = Layouts.forLayer(layer)
            onKey = ::handleKey
            onAlternate = ::handleAlternate
            onRepeat = ::handleRepeat
            onCursorStep = ::moveCursor
            onDeleteWord = ::handleDeleteWord
            onShiftSwipeUp = ::cycleWordCase
            onLineStep = ::moveCursorByLine
            onGlide = ::handleGlide
            onRetractSteerTap = ::retractSteerTap
            onPersonalMenu = ::quickWords
            onQuickInsert = ::insertQuickWord
            onPersonalHold = ::learnCurrentWord
            onPersonalSettings = ::openSetup
            onPress = { haptics?.keyPress(this) }
        }
        suggestionStrip = SuggestionStripView(this).apply {
            onPick = ::pickEntry
            onPress = { haptics?.keyPress(this) }
        }

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
        // Sizes and timings are settings, and the views bake them in when they
        // are built. Focusing a field is the natural moment to notice they have
        // been changed.
        if (viewsBuiltForRevision != KeyboardPrefs.revision(this)) {
            setInputView(onCreateInputView())
        }
        applyNavigationBarInset()
        // A password field must never reach prediction, logging or a learned
        // dictionary. Recorded here so later stages can honour it.
        val isPassword = FieldPolicy.isPassword(info.inputType)
        layer = if (FieldPolicy.isNumeric(info.inputType)) Layer.SYMBOLS else Layer.LETTERS
        autoCapitalise = !isPassword && shouldAutoCapitalise(info)
        shifted = autoCapitalise
        capsLock = false
        shiftTaps.reset()
        keyboardView.layout = Layouts.forLayer(layer, inPassword = isPassword)
        // A menu left open across a change of field would be offering to type
        // somebody's address into whatever has the focus now.
        keyboardView.dismissQuickMenu()
        keyboardView.shifted = shifted
        keyboardView.capsLocked = false

        // The trail is a picture of the last five keys pressed, which in a
        // password field is a picture of part of the password. That is why the
        // two kinds of field remember the answer separately and why one of them
        // defaults to off — not a reason to take the choice away.
        passwordField = isPassword
        showTrail = KeyboardPrefs.showTrail(this, isPassword)
        lineSteeringAllowed = FieldPolicy.isMultiLine(info.inputType)
        applyTrailVisibility()

        // A new field is a new context: nothing typed here yet.
        expectedCursor = info.initialSelEnd
        clearTrail()

        suggestionsAllowed = FieldPolicy.suggestionsAllowed(info.inputType)
        learningAllowed = FieldPolicy.learningAllowed(info.inputType, info.imeOptions)
        // A stroke is decoded against the dictionaries or not at all, so
        // wherever there are no suggestions there is no swiping either (D39).
        keyboardView.glideEnabled = suggestionsAllowed
        // Focus can land in the middle of existing text, and what precedes the
        // cursor there is text this keyboard did not type. Only a cursor at
        // position zero means there is nothing in front of it to be wrong
        // about; -1, which is what the field reports when it does not know
        // where the cursor is, is not that.
        // A cursor at zero means the field is empty in front of it, which is
        // both "we can account for what is there" and "this is the start of a
        // sentence" — so the strip can predict an opening word before a key has
        // been pressed (D46). Anywhere else, there is text this keyboard did
        // not type and no context worth guessing at.
        val atStart = info.initialSelStart == 0
        word.reset(
            known = atStart,
            preceding = if (atStart) Preceding.SentenceStart else Preceding.Unknown,
        )
        spaceGesture.otherInput()
        pendingUndo = null
        reverted = null
        // The launcher screen can take words back out of the store while this
        // service is alive. A stat per focus, and a read only when the file
        // really did change under us.
        personalStore.reloadIfChanged()
        refreshSuggestions()
    }

    private fun handleKey(key: Key, touch: TypedTouch?) {
        val ic = currentInputConnection ?: return
        when (val action = key.action) {
            is KeyAction.Text -> {
                val text = if (shifted) action.text.uppercase() else action.text
                commitTyped(ic, key, text, alternate = false, touch = touch)
            }

            KeyAction.Space -> {
                // The word is finished, which is the only moment the keyboard
                // knows enough to replace it (D28). Before the space, so the
                // space lands after whatever the word turned out to be.
                val corrected = autoCorrect(ic)
                if (spaceGesture.tap(SystemClock.uptimeMillis())) {
                    sentenceEnd(ic, key)
                } else {
                    insertSpace(ic, key)
                }
                // After the space, not before: inserting one is ordinary input
                // and ordinary input is what closes this window. The space is
                // part of the same gesture, so it does not count.
                pendingUndo = corrected
                applyAutoShift(ic)
            }

            // No double tap here: two quick taps are what you do when you want
            // two letters gone, so it fired constantly by accident. Deleting a
            // word is a leftward swipe instead (D20).
            KeyAction.Backspace -> {
                // The one keystroke where backspace is not a deletion (D14).
                val undo = pendingUndo
                when {
                    undo != null && undoCorrection(ic, undo) -> Unit
                    deleteGlidedWord(ic) -> Unit
                    else -> deleteOne(ic)
                }
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
                spaceGesture.otherInput()
            }

            KeyAction.Shift -> {
                when {
                    // Two quick taps latch it. A third, later tap unlatches.
                    shiftTaps.tap(SystemClock.uptimeMillis()) -> {
                        capsLock = true
                        shifted = true
                    }

                    capsLock -> {
                        capsLock = false
                        shifted = false
                    }

                    else -> shifted = !shifted
                }
                keyboardView.shifted = shifted
                keyboardView.capsLocked = capsLock
            }

            KeyAction.ToggleLayer -> {
                layer = Layouts.other(layer)
                keyboardView.layout = Layouts.forLayer(layer, inPassword = passwordField)
            }

            KeyAction.ToggleTrail -> {
                showTrail = !showTrail
                KeyboardPrefs.setShowTrail(this, passwordField, showTrail)
                applyTrailVisibility()
            }

            // Handled entirely in the view, which owns the menu (D40). It never
            // reaches here, but the branch has to exist for the `when` to be
            // exhaustive — and an exhaustive `when` is what will catch the next
            // key action somebody adds and forgets to wire up.
            KeyAction.Personal -> Unit
        }
    }

    /**
     * A long-press alternate. The view has already applied shift, so this
     * commits verbatim — but it still consumes a one-shot shift, so holding
     * `a` for `Ä` does not leave the next letter capitalised too.
     */
    private fun handleAlternate(key: Key, text: String) {
        val ic = currentInputConnection ?: return
        // A long-press alternate came from a popup rather than from a key, so
        // there is no near-miss to record for it.
        commitTyped(ic, key, text, alternate = true, touch = null)
    }

    /**
     * Puts typed text into the field, taking back an auto-inserted space first
     * if the text is punctuation that should hug the word before it (D31).
     *
     * The two callers are a tap and a long-press alternate, and both need the
     * retraction: `,` is a long-press on `b` and `'` one on `v`, so the case the
     * whole thing exists for arrives through the second one.
     */
    private fun commitTyped(
        ic: InputConnection,
        key: Key,
        text: String,
        alternate: Boolean,
        touch: TypedTouch?,
    ) {
        val retract = retractsAutoSpace(ic, text)
        // One batch, so the field sees a replacement rather than a deletion
        // followed by an insertion — and so the cursor arrives once, where
        // expectedCursor says it should.
        if (retract) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(1, 0)
            if (expectedCursor > 0) expectedCursor -= 1
        }
        ic.commitText(text, 1)
        if (retract) ic.endBatchEdit()
        noteInsertion(key, alternate, text, touch)
        consumeShift()

        applyAutoShift(ic, text)

        if (retract) {
            // The apostrophe is a word character (D27), so retracting the space
            // in front of one does not end a word — it joins the punctuation to
            // the accepted suggestion, and `let` + `'` is now the single word
            // `let'`. The tracked word was emptied when the suggestion was
            // accepted and cannot say that, so this is one of the cursor-jump
            // cases: ask the field (D23). Rare enough to pay an IPC round trip.
            recoverWordAtCursor()
            refreshSuggestions()
        }
    }

    /**
     * Whether the space in front of the cursor was put there by accepting a
     * suggestion and should give way to [text].
     *
     * The field is asked what is actually there rather than trusted to still
     * hold what we committed, because between the two the app may have done
     * anything at all.
     */
    private fun retractsAutoSpace(ic: InputConnection, text: String): Boolean =
        spaceGesture.afterAcceptedSuggestion &&
            TextEdits.hugsPreviousWord(text) &&
            ic.getTextBeforeCursor(1, 0)?.toString() == " "

    /**
     * Turns shift on when the cursor has arrived at the start of a sentence
     * (D42).
     *
     * Only ever *on*. Turning it off again would be second-guessing a shift the
     * typist pressed on purpose, and [consumeShift] already spends it on the
     * next letter.
     *
     * The field is only asked when the edit could plausibly have ended a
     * sentence — a space, a newline or a mark — which keeps a round trip off
     * every keystroke.
     */
    private fun applyAutoShift(ic: InputConnection, inserted: String = " ") {
        if (!autoCapitalise || shifted || capsLock) return
        if (inserted.none { it.isWhitespace() || it in TextEdits.SENTENCE_MARKS }) return
        if (!TextEdits.startsSentence(ic.getTextBeforeCursor(SENTENCE_START_LOOKBEHIND, 0))) return
        shifted = true
        keyboardView.shifted = true
    }

    /**
     * Spends a one-shot shift. A latched one is not spent — that is the whole
     * difference between them.
     */
    private fun consumeShift() {
        shiftTaps.reset()
        if (!shifted || capsLock) return
        shifted = false
        keyboardView.shifted = false
    }

    /**
     * Pushes the trail's on-ness into the view, and throws away anything
     * already recorded when it goes off.
     *
     * Clearing rather than merely hiding: what is in the trail is a record of
     * what was typed, and the point of turning it off in a hurry is that the
     * record should not exist.
     */
    private fun applyTrailVisibility() {
        keyboardView.trailEnabled = showTrail
        // One switch over both pictures of what was just typed (D40). The
        // swiped stroke is the franker of the two — the trail says which five
        // keys, the stroke draws the word's shape and leaves it on screen — so
        // hiding one without the other would make the switch a half-truth.
        keyboardView.strokeVisible = showTrail
        if (!showTrail) clearTrail()
    }

    // -- the personal key (D40) -----------------------------------------------

    /**
     * The words tagged for the quick menu.
     *
     * Re-read if the file has changed, because the launcher screen may have
     * been in front of this keyboard a second ago adding one — that is the
     * usual way a word gets onto this menu, and a menu that needed the keyboard
     * restarted to notice would be useless.
     */
    private fun quickWords(): List<String> {
        personalStore.reloadIfChanged()
        return personalStore.quick()
    }

    /**
     * Inserts a whole tagged string — an address, a name — at the cursor.
     *
     * Committed as it stands, with no trailing space and no shift applied. What
     * is on this menu is exact by construction: somebody typed it once and said
     * "remember precisely that", and capitalising it because a one-shot shift
     * happened to be pending would be the keyboard second-guessing the one
     * thing it was told for certain.
     */
    private fun insertQuickWord(text: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
        if (expectedCursor >= 0) expectedCursor += text.length
        consumeShift()
        clearTrail()
        word.reset(known = true)
        word.insert(text)
        glideWord = null
        glideAlternates = emptyList()
        reverted = null
        pendingUndo = null
        spaceGesture.otherInput()
        haptics?.keyPress(keyboardView)
        refreshSuggestions()
    }

    /**
     * Remembers the word in front of the cursor (D8, D40).
     *
     * The whole point of the key. Under D8 the personal store is the only thing
     * that ever teaches this keyboard anything, so how easily a word gets into
     * it sets the ceiling on how good the keyboard becomes — and until now the
     * only way in was an offer in the suggestion strip, which appeared only
     * when there was a slot going spare. A word the keyboard *nearly* knows
     * produces three confident suggestions and no room to say "no, the thing I
     * actually typed".
     */
    private fun learnCurrentWord() {
        val text = tokenToLearn()
        if (text.isEmpty()) {
            // Nothing to learn is not nothing to do: say so with the same
            // double tick a correction uses, so the hold is never silent.
            haptics?.correction()
            return
        }
        if (!learningAllowed || !addWord(text)) {
            haptics?.correction()
            return
        }
        // The word is known now, so the strip's own add-word offer goes away
        // and the completions change. The wave falls *into* the keys rather
        // than rising off the space bar: a correction is the keyboard deciding
        // something and this is the keyboard being told, which is the same
        // event from the other end (D40).
        keyboardView.flashLearned()
        haptics?.correction()
    }

    /**
     * What the personal key would remember: everything between the spaces
     * around the cursor (D40).
     *
     * Read back from the field rather than taken from [word], which tracks a
     * *word* and therefore stops at the first character that is not a letter.
     * That is right for suggesting and correcting and useless here — asking it
     * for `john@coonabibba.de` returns `de`, and an address is one of the very
     * things somebody most wants remembered.
     */
    private fun tokenToLearn(): String {
        val ic = currentInputConnection ?: return ""
        val token = TextEdits.tokenAtCursor(
            before = ic.getTextBeforeCursor(LEARN_REACH, 0),
            after = ic.getTextAfterCursor(LEARN_REACH, 0),
        )
        // A run this long with no space in it is pasted text, not something
        // anybody typed meaning to keep.
        return if (token.length > MAX_LEARNED_LENGTH) "" else token
    }

    /** Opens the launcher screen, which is where the menu's words are managed. */
    private fun openSetup() {
        startActivity(
            Intent(this, SetupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    // -- swiping (D39) --------------------------------------------------------

    /**
     * The words the last stroke could also have been, for as long as they are
     * still the answer to the question on screen.
     *
     * The strip carries these rather than completions of what was just
     * committed, and it is the whole safety net under the feature. Two
     * ambiguities in swipe typing are permanent — a doubled letter is one place
     * on the keyboard, and no accent can be traced at all — so `das` and `dass`
     * are the same stroke, and so are `wurde` and `würde`. Frequency picks the
     * commoner every time, which is right rather more often than not, and the
     * other has to be one tap away for the times it is not.
     */
    private var glideAlternates: List<Suggestion> = emptyList()

    /**
     * The word the stroke committed, which is what makes [glideAlternates] a
     * single invariant rather than a flag to be cleared from a dozen places.
     *
     * The runners-up are the answer to "what else could *that* have been", so
     * they stand exactly as long as *that* is still the word in front of the
     * cursor. Typing on, backspacing, pressing space, moving the cursor,
     * changing field — every one of them changes the word in progress and
     * retires the alternates by doing so, with nothing to remember.
     */
    private var glideWord: String? = null

    /**
     * A word traced in one stroke (D39).
     *
     * Committed **without a trailing space**, and left standing as the word in
     * progress. That is what makes everything after it work with no special
     * cases: tapping an alternate in the strip replaces it exactly the way
     * tapping a suggestion always has, typing `s` after swiping `dog` gives
     * `dogs`, and backspace eats it a letter at a time. The next stroke puts
     * the space in front of itself.
     */
    private fun handleGlide(path: GesturePath, keys: KeyGeometry) {
        val ic = currentInputConnection ?: return
        // A field with no suggestions has no dictionary to answer from, so
        // there is nothing a stroke could mean. Better to do nothing than to
        // guess at a password.
        if (!suggestionsAllowed) return

        val candidates = suggestionSource.candidatesForGesture(path, keys).suggestions
        val best = candidates.firstOrNull() ?: return
        val text = if (shifted) best.text.replaceFirstChar { it.uppercase() } else best.text

        // Never swallow what was already typed. A half-typed word in front of a
        // stroke is far likelier to be a word the typist wants than a mistake
        // they wanted overwritten, and under D14 a replacement that cannot be
        // undone is not one to make quietly.
        val separator = if (word.text.isNotEmpty()) " " else ""

        ic.beginBatchEdit()
        ic.commitText("$separator$text", 1)
        ic.endBatchEdit()
        if (expectedCursor >= 0) expectedCursor += separator.length + text.length

        consumeShift()
        // Nothing here was typed on the keys, so there is no trail to show for
        // it (D19) — and a ribbon that has just been drawn over them all would
        // leave every letter of the word lit up.
        clearTrail()

        // Whatever sat on the far side of the cursor is still there (D23); the
        // stroke went in front of it, not over it. Rebuilding what the word now
        // is out of two halves is guesswork, so this is one of the cases where
        // the field gets asked instead — the same escape hatch [commitTyped]
        // uses, and rare enough to pay an IPC round trip for.
        val midWord = word.suffix.isNotEmpty()
        word.reset(known = true)
        word.insert(text)
        reverted = null
        pendingUndo = null
        spaceGesture.otherInput()
        if (midWord) recoverWordAtCursor()

        // Offer the runners-up only when the stroke stands alone. Tapping one
        // replaces the word in front of the cursor, and with the far half of
        // another word attached that is not the word they are alternatives to.
        glideAlternates = if (midWord) emptyList() else candidates.drop(1)
        glideWord = text
        haptics?.keyPress(keyboardView)
        refreshSuggestions()
    }

    /**
     * Takes back the letter typed by the tap that armed the line-steering
     * gesture (D38, D39).
     *
     * `h` carries both a letter and a gesture, and since a plain drag off a
     * letter now traces a word, the gesture has to be reached by tapping first
     * and pressing again. That first tap types an `h` nobody wanted. It is
     * removed only once the drag actually starts, so a plain double tap still
     * types both letters and `withhold` survives.
     */
    private fun retractSteerTap() {
        val ic = currentInputConnection ?: return
        // Ask the field rather than assume: between the tap and the drag the app
        // may have done anything, and deleting a character that is not the one
        // we put there would be worse than leaving ours behind.
        val before = ic.getTextBeforeCursor(1, 0)?.singleOrNull() ?: return
        if (!before.equals(Layouts.LINE_STEERING_KEY, ignoreCase = true)) return

        ic.deleteSurroundingText(1, 0)
        if (expectedCursor > 0) expectedCursor -= 1
        word.deleteOne()
        popTrail()
        refreshSuggestions()
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
        noteInsertion(key, alternate = false, text = " ", touch = null)
    }

    /**
     * Double-tapping space ends the sentence: the space just typed becomes
     * `". "`, and capitalisation re-arms (D6).
     *
     * Only when a word actually precedes the space — after punctuation, a
     * newline, or nothing at all, a second space is just a space.
     */
    private fun sentenceEnd(ic: InputConnection, key: Key) {
        val spaces = TextEdits.spacesBeforeSentenceEnd(ic.getTextBeforeCursor(SENTENCE_LOOKBEHIND, 0))
        if (spaces == 0) {
            insertSpace(ic, key)
            return
        }

        ic.beginBatchEdit()
        ic.deleteSurroundingText(spaces, 0)
        ic.commitText(SENTENCE_END, 1)
        ic.endBatchEdit()

        if (expectedCursor >= 0) expectedCursor += SENTENCE_END.length - spaces
        popTrail()
        if (keyboardView.trailEnabled) {
            trail.addFirst(TrailEntry(key, alternate = false))
            publishTrail()
        }

        // The spaces that were there are gone and a full stop and space stand
        // in their place; either way the word ended.
        word.insert(SENTENCE_END)
        refreshSuggestions()

        shifted = true
        keyboardView.shifted = true
    }

    /**
     * Backspace straight after a swipe removes the whole word (D39).
     *
     * A stroke is one act, so undoing it should be one act too. Taking a
     * letter at a time off a word nobody typed a letter of is busywork: the
     * word was wrong as a whole, and the next thing to happen is always either
     * swiping it again or typing it out.
     *
     * Only while the swiped word is still exactly what stands in front of the
     * cursor — the same invariant that keeps the alternates in the strip. Once
     * a letter has been added or the cursor has moved, backspace is an ordinary
     * backspace again.
     */
    private fun deleteGlidedWord(ic: InputConnection): Boolean {
        val swiped = glideWord ?: return false
        if (word.full != swiped || word.suffix.isNotEmpty()) return false
        if (!ic.getSelectedText(0).isNullOrEmpty()) return false
        // Ask the field rather than assume, as everywhere else that deletes
        // more than it can see (D23).
        if (ic.getTextBeforeCursor(swiped.length, 0)?.toString() != swiped) return false

        ic.deleteSurroundingText(swiped.length, 0)
        if (expectedCursor >= 0) expectedCursor = (expectedCursor - swiped.length).coerceAtLeast(0)
        word.reset(known = true)
        glideWord = null
        glideAlternates = emptyList()
        clearTrail()
        spaceGesture.otherInput()
        pendingUndo = null
        reverted = null
        refreshSuggestions()
        return true
    }

    private fun deleteOne(ic: InputConnection) {
        val selected = ic.getSelectedText(0)
        if (selected.isNullOrEmpty()) {
            ic.deleteSurroundingText(1, 0)
            if (expectedCursor == 0) {
                // Nothing in front of the cursor to delete, so nothing was: the
                // word is empty and known to be. Treating this as a loss of
                // tracking is what left the strip dead after backspacing a
                // field clear — which is exactly when the next word starts.
                word.reset(known = true)
            } else {
                if (expectedCursor > 0) expectedCursor -= 1
                word.deleteOne()
                // Backspacing past the start of what we were tracking used to
                // silence the strip until the next space — which is exactly
                // when someone deletes a word and starts retyping it. Ask the
                // field what is there instead (D23).
                if (!word.known) recoverWordAtCursor()
            }
        } else {
            ic.commitText("", 1)
            expectedCursor = -1
            // A selection can span anything at all; what is left in front of
            // the cursor is not ours to describe.
            word.reset(known = false)
        }
        popTrail()
        spaceGesture.otherInput()
        pendingUndo = null
        reverted = null
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
        if (!word.known) recoverWordAtCursor()
        spaceGesture.otherInput()
        refreshSuggestions()
    }

    /**
     * Swipe up on shift: the word the cursor is in cycles through lower case,
     * capitalised and shouted (D23).
     *
     * Works on a word that was jumped back to as much as on one being typed —
     * if the word is not one we were tracking, it is read back from the field
     * first, which is the same recovery a cursor jump does.
     */
    private fun cycleWordCase() {
        val ic = currentInputConnection ?: return
        if (!word.known) recoverWordAtCursor()

        val current = word.full
        if (current.isEmpty()) return
        val recased = TextCase.cycle(current)
        if (recased == current) return

        val before = word.text.length
        val after = word.suffix.length

        ic.beginBatchEdit()
        ic.deleteSurroundingText(before, after)
        ic.commitText(recased, 1)
        ic.endBatchEdit()

        if (expectedCursor >= 0) expectedCursor += recased.length - before
        // The word is whole again, and the cursor is at the end of it.
        word.reset(known = true)
        word.insert(recased)
        // Nothing about this arrived from a key, so the trail no longer
        // describes the text in front of it (D19).
        clearTrail()
        spaceGesture.otherInput()
        refreshSuggestions()
    }

    /**
     * Asks the field what word the cursor is sitting in (D23).
     *
     * The one place this keyboard reads text back rather than remembering it,
     * and it happens on a cursor jump rather than per keystroke — which is the
     * difference between one IPC round trip now and again, and one per key.
     * Both halves of the word are kept: correcting it means replacing all of
     * it, not the half in front of the cursor.
     */
    private fun recoverWordAtCursor() {
        val ic = currentInputConnection
        if (ic == null) {
            word.reset(known = false)
            return
        }
        val before = ic.getTextBeforeCursor(WORD_LOOKBEHIND, 0)
        val after = ic.getTextAfterCursor(WORD_LOOKAHEAD, 0)
        if (before == null && after == null) {
            // A read that fails is the app refusing to say, which happens; it
            // is not a licence to guess.
            word.reset(known = false)
            return
        }
        word.adopt(TextEdits.wordAtCursor(before, after))
    }

    // -- auto-correction (D3, D28) -------------------------------------------

    /**
     * What was replaced, and with what, while backspace still means "no".
     *
     * Null unless a correction was applied by the immediately preceding
     * keystroke. D14 is emphatic about the window being unambiguous: a
     * backspace that sometimes deletes a character and sometimes restores a
     * word is worse than either, so anything at all that is not that backspace
     * closes it.
     */
    private var pendingUndo: Correction? = null

    /**
     * A word that was just put back by an undo, offered to the personal store.
     *
     * This is D14's "keep this word": the keyboard was wrong, the typist said
     * so, and under D8 the add-word tap is the only way that ever teaches it
     * anything.
     */
    private var reverted: String? = null

    /**
     * What pressing space would substitute, recomputed after every keystroke.
     *
     * One decision, in one place, read by two things: the strip paints it purple
     * and the space bar applies it (D33). They used to be separate calculations
     * against separate thresholds, which meant the purple was an *impression* of
     * what the keyboard would do rather than a statement of it — and the only
     * way to find out which was right was to press space.
     */
    private var pendingCorrection: Correction? = null

    /**
     * Asks the corrector what it would do with the word as it stands.
     *
     * All the gates live here rather than at the point of use, so that the
     * answer shown in the strip and the answer acted on by space cannot come
     * apart. Null means space will leave the word alone.
     */
    /** Whether the source's answer is one this keyboard is allowed to act on (D3, D28). */
    private fun mayApply(correction: Correction?): Boolean {
        if (correction == null || !autoCorrectEnabled) return false
        // Replacing a word means deleting exactly what we believe is there, and
        // the far half of a word the cursor was dropped into is not that.
        if (word.suffix.isNotEmpty()) return false
        // D28's other hard gate, and the reason it cannot be folded into the
        // source: [WordInProgress.fullTouches] hands the search untouched
        // placeholders when the keyboard did not see the word typed, which is
        // right for *offering* a candidate and never right for replacing one.
        // Without real touches there is no way to tell a slip from a decision.
        if (word.touches.isEmpty()) return false
        return correction.confidence >= autoCorrectConfidence
    }

    /**
     * Replaces the word in front of the cursor if the keyboard is sure enough
     * (D28). Called when space is pressed, which is the moment the word is
     * finished and the last moment it is cheap to change.
     *
     * Applies the decision already made and already shown in the strip; it does
     * not make a new one.
     */
    private fun autoCorrect(ic: InputConnection): Correction? {
        val correction = pendingCorrection ?: return null
        val typed = correction.original

        ic.beginBatchEdit()
        ic.deleteSurroundingText(typed.length, 0)
        ic.commitText(correction.text, 1)
        ic.endBatchEdit()

        if (expectedCursor >= 0) expectedCursor += correction.text.length - typed.length
        word.reset(known = true)
        word.insert(correction.text)
        // The trail describes keys that were pressed, and these letters were
        // not (D19).
        clearTrail()

        // Both of these exist because the typist is looking at the text and not
        // at the keyboard: the flash is caught out of the corner of an eye, and
        // the double tick is felt without looking at all (D28, D29).
        keyboardView.flashCorrection()
        haptics?.correction()
        return correction
    }

    /**
     * Backspace immediately after a correction puts back what was typed (D14).
     *
     * Exactly what was typed, and the space with it, so the text is where it
     * would have been had the keyboard kept quiet — and the strip then offers
     * to remember the word, which is the only way it learns anything (D8).
     */
    private fun undoCorrection(ic: InputConnection, correction: Correction): Boolean {
        val corrected = correction.text
        // The space that followed the correction is part of what gets undone;
        // without it the cursor would end up inside the restored word.
        val before = ic.getTextBeforeCursor(corrected.length + 1, 0) ?: return false
        if (!before.endsWith("$corrected ")) return false

        ic.beginBatchEdit()
        ic.deleteSurroundingText(corrected.length + 1, 0)
        ic.commitText("${correction.original} ", 1)
        ic.endBatchEdit()

        if (expectedCursor >= 0) {
            expectedCursor += correction.original.length - corrected.length
        }
        pendingUndo = null
        reverted = correction.original
        // The space came back with the word, so the cursor is at a boundary and
        // what precedes it is the spelling the typist meant all along — not the
        // one the keyboard had just put there (D46).
        word.reset(known = true, preceding = Preceding.Word(correction.original))
        clearTrail()
        spaceGesture.otherInput()
        refreshSuggestions()
        return true
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
        step(ic, code)
    }

    /**
     * Vertical drag on the steering key. One line per step, either way (D38).
     *
     * The same gesture as the space bar's, turned ninety degrees, and with the
     * same hazard doubled. A DPAD event the field cannot consume falls through
     * to focus navigation and the keyboard vanishes — and where "can it move
     * left" is answerable by asking for one character, "can it move up" is not:
     * a wrapped line has no character in it that says so.
     *
     * Two guards, and they do not cover everything. The field must say it holds
     * more than one line, and there must be text on the side being moved
     * towards. What is left is the first line of a genuine multi-line field with
     * something focusable above it, where the keyboard may still be dismissed —
     * recoverable by tapping the field, and not worth the alternative, which is
     * reading the whole text back and counting newlines on every step.
     */
    private fun moveCursorByLine(direction: Int) {
        if (!lineSteeringAllowed) return
        val ic = currentInputConnection ?: return

        val canMove = if (direction > 0) {
            !ic.getTextAfterCursor(1, 0).isNullOrEmpty()
        } else {
            !ic.getTextBeforeCursor(1, 0).isNullOrEmpty()
        }
        if (!canMove) return

        val code = if (direction > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
        step(ic, code)
    }

    private fun step(ic: InputConnection, keyCode: Int) {
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
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
    private var spaceGesture = SpaceGesture()

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

    private fun noteInsertion(key: Key, alternate: Boolean, text: String, touch: TypedTouch?) {
        // Not recorded at all when it is not being shown, so that turning it
        // off in a password field is a matter of the keys never being written
        // down rather than of a list that happens not to be drawn.
        if (keyboardView.trailEnabled) {
            trail.addFirst(TrailEntry(key, alternate))
            while (trail.size > TRAIL_CAPACITY) trail.removeLast()
        }
        if (expectedCursor >= 0) expectedCursor += text.length
        publishTrail()
        word.insert(text, touch)
        // Any ordinary input closes the window in which backspace means "no,
        // put that back" (D14).
        if (text != " ") reverted = null
        pendingUndo = null
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
    private val personalStore by lazy { PersonalStore(File(filesDir, PersonalStore.FILE_NAME)) }

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
            val german = Wordlists.load(assets::open, Wordlists.GERMAN, Language.GERMAN)
            val english = Wordlists.load(assets::open, Wordlists.ENGLISH, Language.ENGLISH)
            val source = DictionarySuggestions(
                lexicons = listOf(german, english),
                personal = personalStore,
                bigrams = mapOf(
                    Language.GERMAN to mapBigrams(BigramStore.GERMAN, german.size),
                    Language.ENGLISH to mapBigrams(BigramStore.ENGLISH, english.size),
                ),
            )
            suggestionSource = source
            mainHandler.post {
                applyFalloff()
                refreshSuggestions()
            }
        }
    }

    /**
     * Maps a bigram store straight out of the APK (D46).
     *
     * **Mapped, not read.** The two stores are eighteen megabytes against the
     * wordlists' one, and an input method holding that on the heap is one the
     * system kills mid-sentence. Mapping costs no allocation and no parse; the
     * pages arrive as they are touched, and the ones for words nobody types
     * never arrive at all.
     *
     * This is why `build.gradle.kts` keeps `.bigrams` uncompressed in the APK:
     * `openFd` throws for a compressed asset, which is the loud failure worth
     * having. Any failure at all leaves the strip as it was yesterday rather
     * than taking the keyboard down — prediction is the one feature here that
     * is pure gain, so it must also be pure to lose.
     */
    private fun mapBigrams(path: String, words: Int): BigramStore = try {
        assets.openFd(path).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { stream ->
                stream.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    descriptor.startOffset,
                    descriptor.length,
                ).let { BigramStore.read(it, words) }
            }
        }
    } catch (error: IOException) {
        Log.w(TAG, "no bigram store at $path; predictions are off", error)
        BigramStore.NONE
    }

    override fun onDestroy() {
        diskThread.shutdown()
        super.onDestroy()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Hands the current falloff setting to the scorer (D34).
     *
     * Called both when the input view is rebuilt and when the dictionaries
     * finish loading, because either can be the later of the two.
     */
    private fun applyFalloff() {
        (suggestionSource as? DictionarySuggestions)?.confidenceDecay =
            KeyboardPrefs.confidenceFalloff(this)
    }

    private fun refreshSuggestions() {
        val source = suggestionSource
        // One question, asked once (D37). The strip and the space bar both want
        // to know what else this word could be, and asking separately was two
        // scans per keystroke and two chances to disagree.
        val query = if (suggestionsAllowed && word.known) {
            source.candidatesFor(word.full, word.fullTouches)
        } else {
            Candidates.NONE
        }
        // Before the view guard, because the space bar reads this whether or
        // not there is a strip to draw it on.
        pendingCorrection = query.correction?.takeIf(::mayApply)

        if (!::suggestionStrip.isInitialized) return
        if (!suggestionsAllowed || !word.known) {
            suggestionStrip.slots = emptyList()
            return
        }

        // The runners-up from a stroke displace the ordinary candidates while
        // they last, because they answer a better question. After swiping
        // `das`, completions of `das` are of no use to anybody; `dass`, which
        // was traced along the very same path, is the one thing worth offering
        // (D39).
        val alternates = glideAlternates
        if (alternates.isNotEmpty() && word.full == glideWord) {
            suggestionStrip.slots = StripEntry.mark(alternates, correction = null)
            return
        }

        val justReverted = reverted
        if (justReverted != null && learningAllowed && !source.knows(justReverted)) {
            // D14: the moment after a correction is taken back is exactly when
            // the word is worth remembering, and the strip is where the offer
            // goes.
            suggestionStrip.slots = List(SuggestionSlots.CAPACITY - 1) { null } +
                StripEntry.AddWord(justReverted)
            return
        }

        // Between words there is no prefix to complete, so the strip asks the
        // other question instead: not what else this word could be, but what
        // word comes next (D9, D46). This is the slot that has been empty since
        // the strip was built — after every space, and at every sentence start.
        val shown = query.suggestions.ifEmpty {
            if (word.full.isEmpty()) source.predict(word.preceding) else emptyList()
        }

        val candidates = StripEntry.mark(shown, pendingCorrection)
        val offer = addWordOffer(source, candidates)

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
     * Only where the field permits learning, only once the word is long enough
     * to be one, only if nothing recognises it — and **only when there is
     * nothing else to say about it**. Half-typed words are unrecognised nearly
     * all of the time, so without that last condition the offer is on screen
     * almost always and means nothing when it is. Silence from both dictionaries
     * is the moment of annoyance D8 wants this attached to.
     */
    private fun addWordOffer(
        source: SuggestionSource,
        candidates: List<StripEntry.Word>,
    ): StripEntry.AddWord? {
        if (!learningAllowed || candidates.isNotEmpty()) return null
        val typed = word.full
        if (typed.length < MIN_ADDABLE_LENGTH) return null
        if (source.knows(typed)) return null
        return StripEntry.AddWord(typed)
    }

    private fun pickEntry(entry: StripEntry, style: PickStyle) {
        when (entry) {
            is StripEntry.Word -> pickSuggestion(entry.suggestion, style)
            // Remembering a word is the same act however long the finger stays
            // down; there is no second meaning for it to have.
            is StripEntry.AddWord -> addWord(entry.word)
        }
    }

    /**
     * Remembers a word. The text is not touched — the word is already typed;
     * what was missing is the keyboard knowing it.
     */
    /** Returns whether anything was learned — the personal key reports it (D40). */
    private fun addWord(text: String): Boolean {
        if (!learningAllowed) return false
        if (!personalStore.add(text)) return false
        diskThread.execute { personalStore.persist() }
        refreshSuggestions()
        return true
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
    private fun pickSuggestion(suggestion: Suggestion, style: PickStyle) {
        val ic = currentInputConnection ?: return
        val replaced = word.text.length
        // Whatever of the word sits on the far side of the cursor goes too: the
        // suggestion replaces the word, not the half of it that was typed most
        // recently (D23).
        val replacedAfter = word.suffix.length
        val text = if (shifted) suggestion.text.replaceFirstChar { it.uppercase() } else suggestion.text
        // What follows the word decides whether a space is wanted: at the end
        // of the text yes, in front of an existing space or comma no. Without
        // this, correcting a word in a finished sentence doubles its space.
        // A long press says the word is half of a compound, and skips it
        // regardless (D26).
        val following = ic.getTextAfterCursor(replacedAfter + 1, 0)
        val next = following?.getOrNull(replacedAfter)
        val spaced = style == PickStyle.SPACED && TextEdits.needsTrailingSpace(next)
        val committed = if (spaced) "$text " else text

        ic.beginBatchEdit()
        if (replaced > 0 || replacedAfter > 0) ic.deleteSurroundingText(replaced, replacedAfter)
        ic.commitText(committed, 1)
        ic.endBatchEdit()

        if (expectedCursor >= 0) expectedCursor += committed.length - replaced
        consumeShift()
        // A word that arrived from the strip was not typed on the keys, so
        // there is nothing for the trail to colour (D19).
        clearTrail()
        // A spaced pick finishes the word without any separator passing through
        // [WordInProgress.insert], so the context has to be handed over here or
        // the next prediction would still be working from the word before this
        // one (D46).
        word.reset(known = true, preceding = if (spaced) Preceding.Word(text) else word.preceding)
        // Joined on, the word is still in progress: what comes next is more of
        // it, and the strip should be completing `Haus` + `tür` as one.
        if (!spaced) word.insert(text)
        // A space just committed is the first half of the double-space full
        // stop, so one more tap on space ends the sentence (D6). If no space
        // was added, there is nothing to be the first half of.
        if (committed.endsWith(" ")) spaceGesture.suggestionAccepted() else spaceGesture.otherInput()
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
        expectedCursor = newSelEnd
        if (ourOwnEdit) return

        clearTrail()
        // The cursor is somewhere we did not put it. Rather than going quiet,
        // ask the field what word it landed in — jumping back to an earlier
        // word is exactly when a correction is wanted (D23). A selection is
        // different: there is no one word it is sitting in.
        if (newSelStart == newSelEnd) recoverWordAtCursor() else word.reset(known = false)
        spaceGesture.otherInput()
        refreshSuggestions()
    }

    private companion object {
        const val TAG = "BiKeyboard"

        /**
         * Deeper than the five steps the trail actually colours, so that
         * backspacing past the visible gradient keeps revealing older presses
         * instead of running out.
         */
        const val TRAIL_CAPACITY = 10

        /** What a double tap on space writes. */
        const val SENTENCE_END = ". "

        /**
         * Enough to see the spaces a double tap should swallow and the
         * character in front of them.
         */
        const val SENTENCE_LOOKBEHIND = 3

        /**
         * Enough to see a sentence mark, any closing quotes after it, and the
         * spaces after those.
         */
        const val SENTENCE_START_LOOKBEHIND = 8

        /** How far back to read when deleting a word. Longer than any real word. */
        const val WORD_LOOKBEHIND = 64

        /**
         * How far either side of the cursor to read when remembering something
         * by hand (D40). Longer than a word, because the things worth
         * remembering deliberately are addresses and long compounds.
         */
        const val LEARN_REACH = 96

        /** Past this it is pasted text rather than something typed to be kept. */
        const val MAX_LEARNED_LENGTH = 64

        /** How far forward to read when recovering the word the cursor landed in. */
        const val WORD_LOOKAHEAD = 64

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
