package de.coonabibba.bikeyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo

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
