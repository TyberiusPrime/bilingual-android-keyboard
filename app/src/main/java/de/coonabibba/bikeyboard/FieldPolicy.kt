package de.coonabibba.bikeyboard

import android.text.InputType

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
     * - **email addresses, URIs and filter/search-within-a-list fields**, whose
     *   contents are not prose and where word-level correction is noise;
     * - **anything that is not a text field**. Numbers, phone numbers and dates
     *   have no words to correct.
     *
     * Not consulted here: `IME_FLAG_NO_PERSONALIZED_LEARNING`, which lives in
     * `imeOptions` and bars *learning* from the field, not suggesting into it.
     * Nothing learns yet — under D8 only an explicit add-word ever teaches the
     * keyboard anything — so it has nothing to gate until step 4.
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
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_FILTER,
            -> false

            else -> true
        }
    }
}
