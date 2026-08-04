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
}
