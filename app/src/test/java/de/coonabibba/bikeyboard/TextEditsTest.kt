package de.coonabibba.bikeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextEditsTest {

    @Test
    fun `deletes the word right before the cursor`() {
        assertEquals("world".length, TextEdits.wordDeletionCount("hello world"))
    }

    /** Deleting from just after a word must take the word, not only the gap. */
    @Test
    fun `deletes trailing whitespace together with the word`() {
        assertEquals("world  ".length, TextEdits.wordDeletionCount("hello world  "))
    }

    @Test
    fun `deletes the whole buffer when it is a single word`() {
        assertEquals(5, TextEdits.wordDeletionCount("hello"))
    }

    @Test
    fun `deletes nothing when there is nothing before the cursor`() {
        assertEquals(0, TextEdits.wordDeletionCount(""))
    }

    @Test
    fun `deletes only whitespace when that is all there is`() {
        assertEquals(3, TextEdits.wordDeletionCount("   "))
    }

    @Test
    fun `treats a newline as a word boundary`() {
        assertEquals("second".length, TextEdits.wordDeletionCount("first\nsecond"))
    }

    @Test
    fun `double space ends a sentence after a word`() {
        assertTrue(TextEdits.endsSentenceOnDoubleSpace("hi "))
        assertTrue(TextEdits.endsSentenceOnDoubleSpace("ende "))
        assertTrue(TextEdits.endsSentenceOnDoubleSpace("was 2024 "))
    }

    @Test
    fun `double space does not end a sentence without a word before it`() {
        assertFalse("nothing at all", TextEdits.endsSentenceOnDoubleSpace(null))
        assertFalse("too short", TextEdits.endsSentenceOnDoubleSpace(" "))
        assertFalse("run of spaces", TextEdits.endsSentenceOnDoubleSpace("  "))
        assertFalse("already punctuated", TextEdits.endsSentenceOnDoubleSpace(". "))
        assertFalse("after a newline", TextEdits.endsSentenceOnDoubleSpace("\n "))
        assertFalse("cursor not after a space", TextEdits.endsSentenceOnDoubleSpace("hi"))
    }

    // -- the word the cursor landed in (D23) ---------------------------------

    @Test
    fun `the word at the cursor is both halves of it`() {
        val word = TextEdits.wordAtCursor("hello wor", "ld and more")
        assertEquals("wor", word.before)
        assertEquals("ld", word.after)
        assertEquals("world", word.text)
    }

    @Test
    fun `at the end of a word there is nothing after it`() {
        val word = TextEdits.wordAtCursor("hello world", " and more")
        assertEquals("world", word.before)
        assertEquals("", word.after)
    }

    @Test
    fun `between words there is no word at all`() {
        val word = TextEdits.wordAtCursor("hello ", " world")
        assertEquals("", word.text)
    }

    @Test
    fun `punctuation bounds the word but an apostrophe does not`() {
        assertEquals("world", TextEdits.wordAtCursor("(hello, world", ")").text)
        assertEquals("don't", TextEdits.wordAtCursor("I don't", " think").text)
    }

    @Test
    fun `a read that came back empty is no word`() {
        assertEquals("", TextEdits.wordAtCursor(null, null).text)
        assertEquals("word", TextEdits.wordAtCursor("word", null).text)
        assertEquals("word", TextEdits.wordAtCursor(null, "word").text)
    }
}
