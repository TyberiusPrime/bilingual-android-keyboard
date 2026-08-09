package de.coonabibba.bikeyboard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the keyboard thinks the last word was (D46).
 *
 * Prediction is only as good as this, and this is deliberately narrow: the
 * context is what the keyboard watched itself type, and [Preceding.Unknown]
 * everywhere it cannot vouch for that. Same posture as D21's refusal to guess
 * the word in progress, for the same reason — a prediction made from a guess
 * about the preceding word would be a guess squared.
 */
class PrecedingWordTest {

    private fun word(build: WordInProgress.() -> Unit) = WordInProgress().apply(build)

    @Test
    fun `nothing has been typed yet`() {
        assertEquals(Preceding.Unknown, WordInProgress().preceding)
    }

    @Test
    fun `a space finishes a word and starts the context`() {
        val word = word { insert("Hallo"); insert(" ") }
        assertEquals(Preceding.Word("Hallo"), word.preceding)
        assertEquals("", word.text.toString())
    }

    @Test
    fun `typing on does not change what came before`() {
        val word = word { insert("Hallo"); insert(" "); insert("We") }
        assertEquals(Preceding.Word("Hallo"), word.preceding)
    }

    @Test
    fun `each word replaces the last`() {
        val word = word { insert("one"); insert(" "); insert("two"); insert(" ") }
        assertEquals(Preceding.Word("two"), word.preceding)
    }

    /** A sentence mark outranks the word before it: the next word opens a sentence. */
    @Test
    fun `a full stop means the next word starts a sentence`() {
        listOf(".", "!", "?", "\n").forEach { mark ->
            val word = word { insert("fertig"); insert(mark); insert(" ") }
            assertEquals("after $mark", Preceding.SentenceStart, word.preceding)
        }
    }

    /** A comma is not the end of anything; the word before it still counts. */
    @Test
    fun `a comma keeps the word as context`() {
        assertEquals(Preceding.Word("also"), word { insert("also"); insert(",") }.preceding)
    }

    /** A separator with nothing in front of it leaves the context standing. */
    @Test
    fun `a second space changes nothing`() {
        val word = word { insert("Hallo"); insert(" "); insert(" ") }
        assertEquals(Preceding.Word("Hallo"), word.preceding)
    }

    /** Backspacing into the previous word means it is no longer that word. */
    @Test
    fun `deleting past the start forgets the context`() {
        val word = word { insert("Hallo"); insert(" "); deleteOne() }
        assertEquals(Preceding.Unknown, word.preceding)
    }

    @Test
    fun `deleting within the current word keeps the context`() {
        val word = word { insert("Hallo"); insert(" "); insert("Wel"); deleteOne() }
        assertEquals(Preceding.Word("Hallo"), word.preceding)
    }

    /** The backspace swipe leaves the keyboard on ground it did not survey. */
    @Test
    fun `a word deletion forgets the context`() {
        val word = word { insert("Hallo"); insert(" "); insert("Welt"); deleteWord(complete = true) }
        assertEquals(Preceding.Unknown, word.preceding)
    }

    /** A word read back after a cursor jump says nothing about what precedes it. */
    @Test
    fun `adopting a word forgets the context`() {
        val word = word { insert("Hallo"); insert(" "); adopt(WordAtCursor("Wel", "t")) }
        assertEquals(Preceding.Unknown, word.preceding)
    }

    /**
     * The default: a reset that keeps [WordInProgress.known] is a re-adoption
     * of text the keyboard just committed, so the context survives it. That is
     * what accepting a suggestion and applying a correction both do.
     */
    @Test
    fun `a reset that keeps track keeps the context`() {
        val word = word { insert("Hallo"); insert(" "); insert("Wlt"); reset(known = true) }
        assertEquals(Preceding.Word("Hallo"), word.preceding)
    }

    @Test
    fun `losing track loses the context with it`() {
        val word = word { insert("Hallo"); insert(" "); reset(known = false) }
        assertEquals(Preceding.Unknown, word.preceding)
    }

    /** And a caller that knows better can say so. */
    @Test
    fun `a caller may hand the context over`() {
        val word = word { reset(known = true, preceding = Preceding.Word("weil")) }
        assertEquals(Preceding.Word("weil"), word.preceding)
    }
}
