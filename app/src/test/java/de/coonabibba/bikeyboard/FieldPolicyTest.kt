package de.coonabibba.bikeyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldPolicyTest {

    private fun text(vararg bits: Int): Int =
        bits.fold(InputType.TYPE_CLASS_TEXT) { acc, bit -> acc or bit }

    @Test
    fun `an ordinary text field takes suggestions`() {
        assertTrue(FieldPolicy.suggestionsAllowed(text()))
        assertTrue(FieldPolicy.suggestionsAllowed(text(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)))
        assertTrue(FieldPolicy.suggestionsAllowed(text(InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE)))
    }

    /** An obligation, not a courtesy: no prediction into a password field. */
    @Test
    fun `password fields take no suggestions`() {
        listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        ).forEach { assertFalse(FieldPolicy.suggestionsAllowed(text(it))) }

        assertFalse(
            FieldPolicy.suggestionsAllowed(
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            ),
        )
    }

    @Test
    fun `a field asking for no suggestions gets none`() {
        assertFalse(FieldPolicy.suggestionsAllowed(text(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)))
    }

    @Test
    fun `addresses and search filters are not prose`() {
        listOf(
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_FILTER,
        ).forEach { assertFalse(FieldPolicy.suggestionsAllowed(text(it))) }
    }

    /**
     * But a URI field is, most of the time. On a phone the address bar *is* the
     * search bar, so refusing to help there withholds suggestions from a great
     * deal of ordinary prose to avoid interfering with the occasional
     * hand-typed URL — and people paste those.
     *
     * The hazard of mangling an address turns out to be self-limiting: a
     * correction only fires on space (D28), and a space in the address bar is
     * exactly the signal that this is a search rather than a hostname.
     */
    @Test
    fun `the address bar is a search bar and gets suggestions`() {
        assertTrue(FieldPolicy.suggestionsAllowed(text(InputType.TYPE_TEXT_VARIATION_URI)))
        // A browser that genuinely wants no help can still say so.
        assertFalse(
            FieldPolicy.suggestionsAllowed(
                text(InputType.TYPE_TEXT_VARIATION_URI) or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            ),
        )
    }

    @Test
    fun `non-text fields have no words to correct`() {
        listOf(
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME,
        ).forEach { assertFalse(FieldPolicy.suggestionsAllowed(it)) }
    }

    @Test
    fun `passwords are recognised across classes`() {
        assertTrue(FieldPolicy.isPassword(text(InputType.TYPE_TEXT_VARIATION_PASSWORD)))
        assertTrue(FieldPolicy.isPassword(text(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)))
        assertTrue(FieldPolicy.isPassword(text(InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)))
        assertTrue(
            FieldPolicy.isPassword(
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            ),
        )
        assertFalse(FieldPolicy.isPassword(text()))
        assertFalse(FieldPolicy.isPassword(InputType.TYPE_CLASS_NUMBER))
    }

    /** D8: the store is the only thing that learns, so this is the whole gate. */
    @Test
    fun `a field that asks not to be learned from is not learned from`() {
        assertTrue(FieldPolicy.learningAllowed(text(), 0))
        assertFalse(
            FieldPolicy.learningAllowed(text(), EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING),
        )
    }

    @Test
    fun `secrets and non-text fields are never learned from`() {
        assertFalse(FieldPolicy.learningAllowed(text(InputType.TYPE_TEXT_VARIATION_PASSWORD), 0))
        assertFalse(
            FieldPolicy.learningAllowed(text(InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD), 0),
        )
        assertFalse(
            FieldPolicy.learningAllowed(text(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD), 0),
        )
        assertFalse(FieldPolicy.learningAllowed(InputType.TYPE_CLASS_NUMBER, 0))
        assertFalse(FieldPolicy.learningAllowed(InputType.TYPE_CLASS_PHONE, 0))
    }

    /**
     * Learning and suggesting are different questions, and answering the first
     * with the second made it impossible to remember an email address while
     * standing in an email field — the one field an email address is typed
     * into (D40).
     *
     * These fields refuse *suggestions* because their contents are not prose
     * and word-level correction there is noise. That is no reason to refuse a
     * deliberate request to remember something: under D8 nothing is absorbed
     * silently, so every write to the store is already someone asking for it.
     */
    @Test
    fun `a field that refuses suggestions may still be learned from on request`() {
        listOf(
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_FILTER,
        ).forEach { variation ->
            assertFalse("suggestions in $variation", FieldPolicy.suggestionsAllowed(text(variation)))
            assertTrue("learning in $variation", FieldPolicy.learningAllowed(text(variation), 0))
        }
        // And the same for the flag that only ever meant "do not suggest".
        val noSuggestions = text() or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        assertFalse(FieldPolicy.suggestionsAllowed(noSuggestions))
        assertTrue(FieldPolicy.learningAllowed(noSuggestions, 0))
    }

    /** The app's own "do not remember this" still wins everywhere. */
    @Test
    fun `no-personalized-learning overrides the request even in an email field`() {
        assertFalse(
            FieldPolicy.learningAllowed(
                text(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            ),
        )
    }

    /** Numeric fields open on the symbol layer, so the check has to be exact. */
    @Test
    fun `numeric fields are recognised and text fields are not`() {
        assertTrue(FieldPolicy.isNumeric(InputType.TYPE_CLASS_NUMBER))
        assertTrue(FieldPolicy.isNumeric(InputType.TYPE_CLASS_PHONE))
        assertTrue(FieldPolicy.isNumeric(InputType.TYPE_CLASS_DATETIME))
        assertFalse(FieldPolicy.isNumeric(text()))
    }

    // -- multi-line, for the line-steering gesture (D38) ----------------------

    @Test
    fun `a multi-line text field has lines to move between`() {
        assertTrue(
            FieldPolicy.isMultiLine(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            ),
        )
        assertTrue(
            FieldPolicy.isMultiLine(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE,
            ),
        )
    }

    /**
     * The important direction. A DPAD event a single-line field cannot consume
     * takes the focus away with it, so the gesture must never fire here.
     */
    @Test
    fun `a single-line field has none`() {
        assertFalse(FieldPolicy.isMultiLine(InputType.TYPE_CLASS_TEXT))
        assertFalse(
            FieldPolicy.isMultiLine(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            ),
        )
    }

    @Test
    fun `a field that is not text has none either`() {
        assertFalse(FieldPolicy.isMultiLine(InputType.TYPE_CLASS_NUMBER))
        assertFalse(FieldPolicy.isMultiLine(InputType.TYPE_CLASS_PHONE))
        // The flag's bit means something else entirely outside a text field.
        assertFalse(
            FieldPolicy.isMultiLine(
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            ),
        )
    }
}
