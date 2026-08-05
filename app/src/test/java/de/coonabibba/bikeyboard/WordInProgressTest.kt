package de.coonabibba.bikeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordInProgressTest {

    private fun typing(text: String) = WordInProgress().apply { text.forEach { insert(it.toString()) } }

    @Test
    fun `letters build up the word`() {
        assertEquals("guten", typing("guten").text.toString())
    }

    @Test
    fun `a space ends the word`() {
        assertEquals("", typing("guten ").text.toString())
        assertEquals("tag", typing("guten tag").text.toString())
    }

    @Test
    fun `punctuation ends the word but an apostrophe does not`() {
        assertEquals("", typing("hallo,").text.toString())
        assertEquals("don't", typing("don't").text.toString())
    }

    @Test
    fun `the sentence-ending double space clears the word`() {
        val word = typing("ende")
        word.insert(". ")
        assertEquals("", word.text.toString())
        assertTrue(word.known)
    }

    @Test
    fun `umlauts and digits are word characters`() {
        assertEquals("über2", typing("über2").text.toString())
    }

    @Test
    fun `backspace walks the word back`() {
        val word = typing("hallo")
        word.deleteOne()
        assertEquals("hall", word.text.toString())
        assertTrue(word.known)
    }

    /**
     * Deleting past the start of what we typed puts the cursor into text this
     * keyboard never saw, so it must stop claiming to know the word there.
     */
    @Test
    fun `backspacing past the start of the tracked word gives up`() {
        val word = typing("hi ")
        word.deleteOne()
        assertFalse(word.known)
        assertEquals("", word.text.toString())
    }

    @Test
    fun `crossing a boundary makes the word known again`() {
        val word = WordInProgress()
        word.reset(known = false)
        word.insert("a")
        assertFalse("still inside unknown text", word.known)
        word.insert(" ")
        assertTrue("a separator we typed ourselves is a fresh start", word.known)
        word.insert("los")
        assertEquals("los", word.text.toString())
        assertTrue(word.known)
    }

    @Test
    fun `deleting a word leaves nothing in progress`() {
        val word = typing("guten tag")
        word.deleteWord(complete = true)
        assertEquals("", word.text.toString())
        assertTrue(word.known)
    }

    /** A truncated word deletion may have left part of a word standing. */
    @Test
    fun `an incomplete word deletion gives up`() {
        val word = typing("guten tag")
        word.deleteWord(complete = false)
        assertEquals("", word.text.toString())
        assertFalse(word.known)
    }

    @Test
    fun `reset clears the word and sets what is known`() {
        val word = typing("hallo")
        word.reset(known = false)
        assertEquals("", word.text.toString())
        assertFalse(word.known)

        word.reset(known = true)
        assertTrue(word.known)
    }

    // -- a word the cursor was put into (D23) --------------------------------

    @Test
    fun `an adopted word keeps both halves`() {
        val word = WordInProgress()
        word.adopt(WordAtCursor("wor", "ld"))
        assertEquals("wor", word.text.toString())
        assertEquals("ld", word.suffix)
        assertEquals("world", word.full)
        assertTrue("read back from the field is knowing", word.known)
    }

    @Test
    fun `typing into an adopted word extends the half in front of the cursor`() {
        val word = WordInProgress()
        word.adopt(WordAtCursor("wor", "ld"))
        word.insert("l")
        assertEquals("worl", word.text.toString())
        assertEquals("the far half is untouched", "ld", word.suffix)
        assertEquals("worlld", word.full)
    }

    @Test
    fun `a separator drops the far half too`() {
        val word = WordInProgress()
        word.adopt(WordAtCursor("wor", "ld"))
        word.insert(" ")
        assertEquals("", word.full)
        assertTrue(word.known)
    }

    @Test
    fun `resetting drops both halves`() {
        val word = WordInProgress()
        word.adopt(WordAtCursor("wor", "ld"))
        word.reset(known = false)
        assertEquals("", word.full)
        assertFalse(word.known)
    }
}
