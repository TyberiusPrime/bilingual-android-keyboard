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
    fun `double space ends a sentence after a word, swallowing its space`() {
        assertEquals(1, TextEdits.spacesBeforeSentenceEnd("hi "))
        assertEquals(1, TextEdits.spacesBeforeSentenceEnd("ende "))
        assertEquals(1, TextEdits.spacesBeforeSentenceEnd("was 2024 "))
    }

    /**
     * The repeat: a previous double tap left `. `, so the next one has two
     * spaces in front of it and has to take both. Three of them spell `...`.
     */
    @Test
    fun `after a full stop it swallows both spaces, so the gesture repeats`() {
        assertEquals(2, TextEdits.spacesBeforeSentenceEnd("word.  "))
        assertEquals(2, TextEdits.spacesBeforeSentenceEnd("word..  "))
    }

    @Test
    fun `double space does not end a sentence without a word before it`() {
        assertEquals("nothing at all", 0, TextEdits.spacesBeforeSentenceEnd(null))
        assertEquals("too short", 0, TextEdits.spacesBeforeSentenceEnd(" "))
        assertEquals("run of spaces", 0, TextEdits.spacesBeforeSentenceEnd("  "))
        assertEquals("deliberate run", 0, TextEdits.spacesBeforeSentenceEnd("hi   "))
        assertEquals("other punctuation", 0, TextEdits.spacesBeforeSentenceEnd("hi!  "))
        assertEquals("after a newline", 0, TextEdits.spacesBeforeSentenceEnd("\n "))
        assertEquals("cursor not after a space", 0, TextEdits.spacesBeforeSentenceEnd("hi"))
    }

    // -- the space after an accepted suggestion (D23) -------------------------

    @Test
    fun `a suggestion at the end of the text gets its space`() {
        assertTrue(TextEdits.needsTrailingSpace(null))
        assertTrue("in front of another word", TextEdits.needsTrailingSpace('w'))
    }

    @Test
    fun `a suggestion in finished text does not double the space`() {
        assertFalse(TextEdits.needsTrailingSpace(' '))
        assertFalse(TextEdits.needsTrailingSpace(','))
        assertFalse(TextEdits.needsTrailingSpace('.'))
        assertFalse(TextEdits.needsTrailingSpace('\n'))
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
    fun `sentence punctuation hugs the word before it`() {
        listOf(",", ".", "!", "?", ";", ":", "'", "…").forEach {
            assertTrue("$it should hug", TextEdits.hugsPreviousWord(it))
        }
    }

    @Test
    fun `closing brackets hug and opening ones do not`() {
        listOf(")", "]", "}").forEach {
            assertTrue("$it should hug", TextEdits.hugsPreviousWord(it))
        }
        listOf("(", "[", "{", "„", "¿", "¡").forEach {
            assertFalse("$it should not hug", TextEdits.hugsPreviousWord(it))
        }
    }

    @Test
    fun `letters digits and the space itself never hug`() {
        listOf("a", "Ä", "7", " ", "-", "/", "@").forEach {
            assertFalse("$it should not hug", TextEdits.hugsPreviousWord(it))
        }
    }

    /** Only single characters, so a pasted or composed run is left alone. */
    @Test
    fun `a multi-character insertion never hugs`() {
        assertFalse(TextEdits.hugsPreviousWord(". "))
        assertFalse(TextEdits.hugsPreviousWord("..."))
        assertFalse(TextEdits.hugsPreviousWord(""))
    }

    @Test
    fun `a read that came back empty is no word`() {
        assertEquals("", TextEdits.wordAtCursor(null, null).text)
        assertEquals("word", TextEdits.wordAtCursor("word", null).text)
        assertEquals("word", TextEdits.wordAtCursor(null, "word").text)
    }
}
