package de.coonabibba.bikeyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * What the enter key does in the field being typed into, and what it says while
 * it is there (D49).
 *
 * The key is the one key on the board whose job is decided by the app rather
 * than by this keyboard, so it is the one key that has to be told what it is
 * before it can be drawn.
 */
sealed interface EnterKey {

    /** The glyph on the key. */
    val label: String

    /** Put a line break in the text. */
    data object Newline : EnterKey {
        override val label: String get() = "↵"
    }

    /**
     * Perform the field's editor action — go, search, send, done, next — with
     * a glyph that says which.
     */
    data class Action(val id: Int, override val label: String) : EnterKey
}

/**
 * What the field being typed into permits, decided from its `inputType` alone.
 *
 * Pure integer logic, kept out of the service so it can be tested without an
 * `EditorInfo`.
 */
object FieldPolicy {

    fun isPassword(inputType: Int): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD

            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /**
     * Whether the field holds more than one line, and so has lines to move
     * between (D38).
     *
     * Asked because moving the cursor vertically is done with DPAD events, and
     * a DPAD event a single-line field cannot consume does not stop there: it
     * falls through to focus navigation, focus leaves the field, and the
     * keyboard disappears. The same trap the space-bar drag already guards
     * against, with a worse failure mode.
     */
    fun isMultiLine(inputType: Int): Boolean {
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0 ||
            inputType and InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE != 0
    }

    /**
     * What the enter key should do here, or `null` for "nothing, so do not draw
     * one" (D49).
     *
     * Three inputs and, in this order, three answers:
     *
     * - **`IME_FLAG_NO_ENTER_ACTION` means the enter key must not perform the
     *   action**, whatever action the field also advertises for its button. The
     *   flag reads like an oddity and is not: `TextView` sets it on every
     *   multi-line field while *still* filling in `IME_ACTION_DONE` or
     *   `IME_ACTION_NEXT`, because the action belongs to the action button and
     *   the enter key belongs to the text. Reading the action and ignoring the
     *   flag is what made enter in a chat box — Telegram's, and every other
     *   app whose message field is an ordinary multi-line `EditText` — submit
     *   the field and take the keyboard away instead of starting a new line.
     * - **Otherwise a real action is performed**, and the key wears its glyph
     *   rather than a newline symbol it is not going to produce.
     * - **Otherwise a newline**, but only where there are lines to hold one.
     *
     * A single-line field that forbids the action *and* cannot hold a newline
     * has nothing left for the key to do, so it does not get one. Note how
     * narrow that is: the key stays on ordinary single-line fields, because
     * there it is the only way to submit a search, a login or a web form, and
     * because sending `KEYCODE_ENTER` is how a field with an editor-action
     * listener hears about it. What changes there is the label.
     */
    fun enterKey(inputType: Int, imeOptions: Int): EnterKey? {
        val newlineOrNothing = if (isMultiLine(inputType)) EnterKey.Newline else null
        if (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return newlineOrNothing
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        return when (action) {
            EditorInfo.IME_ACTION_NONE, EditorInfo.IME_ACTION_UNSPECIFIED -> newlineOrNothing
            else -> EnterKey.Action(action, actionLabel(action))
        }
    }

    /**
     * A glyph per editor action, all of them from the arrow and dingbat blocks
     * every Android font since forever has covered.
     *
     * Words would say more — "Send", "Suchen" — and are not an option on a key
     * this size, quite apart from D2 leaving no one language to write them in.
     * So the distinctions kept are the ones a thumb needs: *this leaves the
     * field* (→), *this finishes* (✓), and *this moves between fields* (⇥, ⇤).
     * Go, search and send are one glyph between them because they are one act:
     * hand the text over and expect the screen to change.
     */
    private fun actionLabel(action: Int): String = when (action) {
        EditorInfo.IME_ACTION_DONE -> "✓"
        EditorInfo.IME_ACTION_NEXT -> "⇥"
        EditorInfo.IME_ACTION_PREVIOUS -> "⇤"
        else -> "→"
    }

    fun isNumeric(inputType: Int): Boolean =
        when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME,
            -> true

            else -> false
        }

    /**
     * Whether the strip may offer anything for this field.
     *
     * The strip itself stays visible either way — D9 fixes the keyboard height,
     * so it must not appear and disappear with the field — it simply stays
     * empty. Refused for:
     *
     * - **passwords and `NO_SUGGESTIONS`**, which the API notes list as an
     *   obligation rather than a courtesy: no prediction, no learning, no
     *   logging;
     * - **email addresses and filter/search-within-a-list fields**, whose
     *   contents are not prose and where word-level correction is noise;
     * - **anything that is not a text field**. Numbers, phone numbers and dates
     *   have no words to correct.
     *
     * **A URI field is not on that list, though it was.** On a phone the
     * address bar is the search bar — Firefox has one box for both — so
     * refusing to help there withholds suggestions from a good deal of ordinary
     * prose in order to avoid interfering with the occasional hand-typed URL.
     * That is the wrong way round: people type far more searches into that box
     * than addresses, and the ones who type an address usually paste it.
     *
     * The obvious worry is auto-correction mangling a URL, and it turns out to
     * be self-limiting. A correction only ever fires on **space** (D28), and a
     * space in the address bar is precisely the signal that this is a search
     * and not an address — nobody types a space inside a hostname. So the
     * destructive half only reaches the text in the case where it is wanted,
     * without anything having to detect which mode the box is in.
     *
     * Swiping follows suggestions (D39), so it comes back here too, which is
     * the larger part of the benefit: searches are exactly the sort of throwaway
     * prose a swipe is for.
     *
     * Not consulted here: `IME_FLAG_NO_PERSONALIZED_LEARNING`, which lives in
     * `imeOptions` and bars *learning* from the field rather than suggesting
     * into it. That is [learningAllowed]'s business.
     */
    fun suggestionsAllowed(inputType: Int): Boolean {
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        if (inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) return false
        return when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_FILTER,
            -> false

            else -> true
        }
    }

    /**
     * Whether something typed here may be put in the personal store on purpose.
     *
     * **A different question from [suggestionsAllowed], and it used to be
     * answered by it.** That bundled two things that only look alike: whether
     * the keyboard should volunteer completions into a field, and whether the
     * user may deliberately tell it to remember something typed there. An email
     * address field refuses the first because addresses are not prose and
     * word-level correction there is noise — and then refused the second, which
     * made it impossible to remember an email address while standing in the one
     * field an email address is typed into. Same for URIs, and for the search
     * boxes that carry `NO_SUGGESTIONS`.
     *
     * Under D8 nothing is ever absorbed silently: the store changes only when
     * somebody holds a key and asks. There is no case for second-guessing that
     * request in a field whose only sin is not containing sentences. So this
     * refuses exactly three things, and each for a reason of its own:
     *
     * - **Passwords.** The text is a secret, and D18 aside, a secret written to
     *   a plain file in the app's data directory is a secret no longer.
     * - **`IME_FLAG_NO_PERSONALIZED_LEARNING`.** The app has said not to
     *   remember what is typed here, which is what incognito and private modes
     *   set, and honouring it is not optional.
     * - **Anything that is not a text field.** Numbers, dates and phone numbers
     *   have nothing in them worth a place in a vocabulary.
     */
    fun learningAllowed(inputType: Int, imeOptions: Int): Boolean {
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        if (isPassword(inputType)) return false
        return imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0
    }
}
