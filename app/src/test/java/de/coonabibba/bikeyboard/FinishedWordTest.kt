package de.coonabibba.bikeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reaching back over the space to the word just finished (D48).
 *
 * The shift swipe re-cases the word the cursor is in, which left the gesture
 * useless at the moment it is most wanted: once the word is out and the missing
 * capital is visible. For a swiped word it was useless full stop, because a
 * swipe commits the space along with the word.
 */
class FinishedWordTest {

    private fun found(before: String?) = TextEdits.finishedWordBefore(before)

    @Test
    fun `a word behind a space`() {
        val finished = found("Hallo Welt ")!!
        assertEquals("Welt", finished.word)
        assertEquals(" ", finished.tail)
        assertEquals(6, finished.start)
        assertEquals(5, finished.span)
    }

    /** The tail is put back verbatim, so it has to be captured whole. */
    @Test
    fun `punctuation counts as finishing the word`() {
        assertEquals("hallo" to ". ", found("hallo. ").let { it!!.word to it.tail })
        assertEquals("hallo" to ".", found("hallo.").let { it!!.word to it.tail })
        assertEquals("hallo" to ", ", found("hallo, ").let { it!!.word to it.tail })
        assertEquals("hallo" to "!?  ", found("hallo!?  ").let { it!!.word to it.tail })
    }

    /** An apostrophe is inside the word, not after it (D27). */
    @Test
    fun `a contraction is one word`() {
        assertEquals("don't", found("I don't ")?.word)
        assertEquals("geht's", found("das geht's ")?.word)
    }

    /** Still inside a word: the caller's own tracked word is the better answer. */
    @Test
    fun `nothing is finished mid-word`() {
        assertNull(found("Hallo Wel"))
        assertNull(found("Hallo"))
    }

    @Test
    fun `nothing to find`() {
        assertNull(found(""))
        assertNull(found(null))
        assertNull(found("   "))
        assertNull(found("... "))
    }

    /**
     * **Deliberately stops at a newline.** A gesture that silently edits the
     * line above, out of sight of the cursor, is not one anybody asked for.
     */
    @Test
    fun `a newline is not crossed`() {
        assertNull(found("Hallo\n"))
        assertNull(found("Hallo\n  "))
        // But a newline further back is no obstacle.
        assertEquals("Welt", found("Hallo\nWelt ")?.word)
    }

    @Test
    fun `the first word of a field is reachable`() {
        val finished = found("hallo ")!!
        assertEquals("hallo", finished.word)
        assertEquals(0, finished.start)
    }

    /** What the gesture actually does with it, end to end. */
    @Test
    fun `re-casing a finished word cycles`() {
        var text = "vielen dank "
        val cycled = (1..3).map {
            val finished = TextEdits.finishedWordBefore(text)!!
            val head = text.dropLast(finished.span)
            text = head + TextCase.cycle(finished.word) + finished.tail
            text
        }
        assertEquals(
            listOf("vielen Dank ", "vielen DANK ", "vielen dank "),
            cycled,
        )
    }

    @Test
    fun `the tail survives the re-casing`() {
        val finished = TextEdits.finishedWordBefore("das war schoen. ")!!
        val rebuilt = "das war schoen. ".dropLast(finished.span) +
            TextCase.cycle(finished.word) + finished.tail
        assertEquals("das war Schoen. ", rebuilt)
    }
}
