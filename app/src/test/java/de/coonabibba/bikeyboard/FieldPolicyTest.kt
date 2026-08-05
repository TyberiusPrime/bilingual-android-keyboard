package de.coonabibba.bikeyboard

import android.text.InputType
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
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_FILTER,
        ).forEach { assertFalse(FieldPolicy.suggestionsAllowed(text(it))) }
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

    /** Numeric fields open on the symbol layer, so the check has to be exact. */
    @Test
    fun `numeric fields are recognised and text fields are not`() {
        assertTrue(FieldPolicy.isNumeric(InputType.TYPE_CLASS_NUMBER))
        assertTrue(FieldPolicy.isNumeric(InputType.TYPE_CLASS_PHONE))
        assertTrue(FieldPolicy.isNumeric(InputType.TYPE_CLASS_DATETIME))
        assertFalse(FieldPolicy.isNumeric(text()))
    }
}
