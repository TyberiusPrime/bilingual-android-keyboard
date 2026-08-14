package de.coonabibba.bikeyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /** Numeric fields open on the number layer (D52), so the check has to be exact. */
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

    // -- addresses, where a full stop is not the end of anything (D54) --------

    /**
     * The double tap on space writes `". "` in prose. Here the same character
     * separates the parts of one token, and the space would break the address
     * in half.
     */
    @Test
    fun `a URL or an email address is an address, not prose`() {
        listOf(
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
        ).forEach { assertTrue("variation $it", FieldPolicy.isAddressField(text(it))) }
    }

    @Test
    fun `ordinary prose fields are not addresses`() {
        assertFalse(FieldPolicy.isAddressField(text()))
        assertFalse(FieldPolicy.isAddressField(multiLine))
        assertFalse(FieldPolicy.isAddressField(text(InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE)))
        assertFalse(FieldPolicy.isAddressField(text(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)))
        assertFalse(FieldPolicy.isAddressField(InputType.TYPE_CLASS_NUMBER))
        assertFalse(FieldPolicy.isAddressField(InputType.TYPE_CLASS_PHONE))
    }

    /**
     * A different question from [FieldPolicy.suggestionsAllowed], which lets a
     * URI field through on purpose: on a phone the address bar is the search
     * bar (D39c), so words are offered there. What one keystroke *writes* is
     * not the same as whether to offer words, and this is a keystroke nobody
     * presses mid-search.
     */
    @Test
    fun `the address bar still gets its suggestions`() {
        val uri = text(InputType.TYPE_TEXT_VARIATION_URI)
        assertTrue(FieldPolicy.isAddressField(uri))
        assertTrue(FieldPolicy.suggestionsAllowed(uri))
    }

    // -- what the enter key is in this field (D49) ----------------------------

    private val multiLine = text(InputType.TYPE_TEXT_FLAG_MULTI_LINE)

    /**
     * The bug this was written for. A multi-line `EditText` — every chat box
     * there is, Telegram's included — is handed to the IME as
     * `IME_ACTION_DONE` *plus* `IME_FLAG_NO_ENTER_ACTION`, because the action
     * belongs to the action button and the enter key belongs to the text.
     * Reading the action and ignoring the flag submitted the field and took the
     * keyboard away instead of starting a new line.
     */
    @Test
    fun `a multi-line field that forbids the action gets a newline`() {
        assertEquals(
            EnterKey.Newline,
            FieldPolicy.enterKey(
                multiLine,
                EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION,
            ),
        )
    }

    @Test
    fun `a multi-line field with no action of its own gets a newline`() {
        assertEquals(EnterKey.Newline, FieldPolicy.enterKey(multiLine, 0))
        assertEquals(
            EnterKey.Newline,
            FieldPolicy.enterKey(multiLine, EditorInfo.IME_ACTION_NONE),
        )
    }

    /**
     * The single-line case the enter key still exists for: it is the only way
     * to submit a search, a login or a web form from this keyboard.
     */
    @Test
    fun `a field with an action performs it`() {
        val search = FieldPolicy.enterKey(text(), EditorInfo.IME_ACTION_SEARCH)
        assertEquals(EnterKey.Action(EditorInfo.IME_ACTION_SEARCH, "→"), search)
        // Multi-line and *not* refusing the action — an email subject, a long
        // message — means the action really was meant for the enter key.
        assertEquals(
            EnterKey.Action(EditorInfo.IME_ACTION_SEND, "→"),
            FieldPolicy.enterKey(multiLine, EditorInfo.IME_ACTION_SEND),
        )
        // Not a text field, but still something to submit.
        assertEquals(
            EnterKey.Action(EditorInfo.IME_ACTION_DONE, "✓"),
            FieldPolicy.enterKey(InputType.TYPE_CLASS_PHONE, EditorInfo.IME_ACTION_DONE),
        )
    }

    /** The key says what it will do, so the glyphs have to differ where the acts do. */
    @Test
    fun `leaving, finishing and moving on look different`() {
        fun label(action: Int) = (FieldPolicy.enterKey(text(), action) as EnterKey.Action).label
        assertEquals("✓", label(EditorInfo.IME_ACTION_DONE))
        assertEquals("⇥", label(EditorInfo.IME_ACTION_NEXT))
        assertEquals("⇤", label(EditorInfo.IME_ACTION_PREVIOUS))
        // Go, search and send are one act: hand the text over and expect the
        // screen to change.
        listOf(
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND,
        ).forEach { assertEquals("→", label(it)) }
        assertTrue(EnterKey.Newline.label.isNotEmpty())
    }

    /**
     * Nothing to insert and nothing to perform. A newline in a single-line
     * field is not a newline — it is a DPAD event the field cannot consume,
     * which is D38's trap and takes the keyboard with it.
     */
    @Test
    fun `a single-line field with nothing for the key to do gets no key`() {
        assertNull(FieldPolicy.enterKey(text(), 0))
        assertNull(FieldPolicy.enterKey(text(), EditorInfo.IME_ACTION_NONE))
        assertNull(
            FieldPolicy.enterKey(
                text(),
                EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_ENTER_ACTION,
            ),
        )
        assertNull(FieldPolicy.enterKey(InputType.TYPE_CLASS_NUMBER, 0))
    }

    /**
     * A password field is single-line and carries an action, so it keeps its
     * key — being unable to submit a login is not an improvement.
     */
    @Test
    fun `a password field can still be submitted`() {
        assertEquals(
            EnterKey.Action(EditorInfo.IME_ACTION_GO, "→"),
            FieldPolicy.enterKey(
                text(InputType.TYPE_TEXT_VARIATION_PASSWORD),
                EditorInfo.IME_ACTION_GO,
            ),
        )
    }

}
