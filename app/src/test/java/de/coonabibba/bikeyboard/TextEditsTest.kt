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

    /**
     * D55: an emoji is two chars, and looking at only the second of them found
     * half a surrogate pair — which is not a letter, so the gesture refused.
     * `hi 🙂` is a finished sentence in every way that matters.
     */
    @Test
    fun `double space ends a sentence after an emoji`() {
        assertEquals("plain", 1, TextEdits.spacesBeforeSentenceEnd("hi 🙂 "))
        assertEquals("skin tone", 1, TextEdits.spacesBeforeSentenceEnd("👍🏽 "))
        assertEquals("variation selector", 1, TextEdits.spacesBeforeSentenceEnd("☀️ "))
        assertEquals("keycap", 1, TextEdits.spacesBeforeSentenceEnd("1️⃣ "))
        assertEquals("flag", 1, TextEdits.spacesBeforeSentenceEnd("🇩🇪 "))
        assertEquals("joined family", 1, TextEdits.spacesBeforeSentenceEnd("👨‍👩‍👧 "))
        // And it repeats from there like anything else does.
        assertEquals("repeat", 2, TextEdits.spacesBeforeSentenceEnd("🙂.  "))
    }

    /**
     * The window the service reads has to be wide enough to hold the widest
     * thing this looks at: two swallowed spaces and a two-char code point.
     */
    @Test
    fun `an emoji is still whole in the last few characters of the field`() {
        val emoji = "🙂"
        assertEquals("an emoji is two chars", 2, emoji.length)
        // What the service passes in: the last SENTENCE_LOOKBEHIND characters.
        val window = "hallo $emoji ".takeLast(4)
        assertEquals(1, TextEdits.spacesBeforeSentenceEnd(window))
    }

    /** Any script, since the test is a code point and not an ASCII range. */
    @Test
    fun `double space ends a sentence after a letter of any script`() {
        assertEquals("greek", 1, TextEdits.spacesBeforeSentenceEnd("λόγος "))
        assertEquals("cyrillic", 1, TextEdits.spacesBeforeSentenceEnd("слово "))
        assertEquals("umlaut", 1, TextEdits.spacesBeforeSentenceEnd("Straße "))
    }

    /**
     * The whole rule in one place, because "where does this actually fire?" is
     * a question worth being able to answer by reading one test (D55).
     */
    @Test
    fun `the full list of what a double tap will put a stop after`() {
        val fires = listOf(
            "hallo", "WORD", "2024", // letters and digits
            "Straße", "café", "λόγος", "слово", // any script, accents and all
            "🙂", "👍🏽", "☀️", "1️⃣", "🇩🇪", "👨‍👩‍👧", "🏴󠁧󠁢󠁳󠁣󠁴󠁿", // every shape of emoji
            "20°", "©", "™", // the other pictographs
            "ende.", // a stop already there: this is what repeats into ...
        )
        val refuses = listOf(
            "oh!", "was?", "hm…", // sentence marks: !. is not a thing
            "erstens,", "so:", "dann;", // the marks a sentence carries on after
            "(beiseite)", "\"zitat\"", "ende”", // closing brackets and quotes
            "50%", "12€", "5$", "c#", "a/", "x=", "1+", "e-", "a_", // and the rest
        )

        fires.forEach {
            assertEquals("a stop belongs after $it", 1, TextEdits.spacesBeforeSentenceEnd("$it "))
        }
        refuses.forEach {
            assertEquals("no stop after $it", 0, TextEdits.spacesBeforeSentenceEnd("$it "))
        }
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


    // -- what the personal key remembers (D40) --------------------------------

    private fun token(before: String, after: String = "") =
        TextEdits.tokenAtCursor(before, after)

    /**
     * The complaint this exists for. The word tokeniser stops at the first
     * character that is not a letter, so it answers `de` — and an address is
     * one of the very things somebody most wants remembered.
     */
    @Test
    fun `an email address is one token`() {
        assertEquals("john@coonabibba.de", token("mail me at john@coonabibba.de"))
        assertEquals("de", TextEdits.wordAtCursor("mail me at john@coonabibba.de", "").text)
    }

    @Test
    fun `the token spans the cursor rather than ending at it`() {
        assertEquals("john@coonabibba.de", token("mail me at john@coona", "bibba.de and ask"))
    }

    @Test
    fun `whitespace is the only delimiter`() {
        assertEquals("+49-30-1234", token("call +49-30-1234"))
        assertEquals("C:\\Users\\john", token("C:\\Users\\john"))
        assertEquals("Rindfleisch-Etikettierung", token("Rindfleisch-Etikettierung"))
    }

    @Test
    fun `a cursor in open space has nothing to remember`() {
        assertEquals("", token(""))
        assertEquals("", token("finished the sentence "))
        assertEquals("", token("a line\n", "\nanother"))
    }

    /** Framing punctuation is the sentence's, not the thing's. */
    @Test
    fun `surrounding brackets and quotes are trimmed`() {
        assertEquals("john@coonabibba.de", token("write to (john@coonabibba.de)"))
        assertEquals("Fairphone", token("the \u201eFairphone\u201c"))
        assertEquals("Fairphone", token("bought a Fairphone,"))
        assertEquals("really", token("really?!"))
    }

    /**
     * The full stop is deliberately kept. German abbreviates with one and a
     * domain is nothing but full stops, so trimming would break far more than
     * it fixed — at the cost of keeping a sentence's own stop when somebody
     * remembers a word after typing it, which the launcher screen can undo.
     */
    @Test
    fun `a trailing full stop survives because abbreviations need it`() {
        assertEquals("z.B.", token("zum Beispiel z.B."))
        assertEquals("d.h.", token("d.h."))
        assertEquals("coonabibba.de", token("coonabibba.de"))
    }

    /** Nothing to trim, nothing trimmed. */
    @Test
    fun `an ordinary word is returned unchanged`() {
        assertEquals("Fairphone", token("my Fairphone"))
        assertEquals("don't", token("don't"))
    }

    // -- where a sentence begins (D42) ----------------------------------------

    /**
     * Shift used to be set once, when focus arrived, and never again — and the
     * only thing that turned it back on was the double-space full stop. Since
     * `?` and `!` are reachable only by long-press, every question and every
     * exclamation was followed by a lowercase letter.
     */
    @Test
    fun `a sentence mark and a space open a new sentence`() {
        assertTrue(TextEdits.startsSentence("Hello. "))
        assertTrue(TextEdits.startsSentence("Really? "))
        assertTrue(TextEdits.startsSentence("Stop! "))
        assertTrue(TextEdits.startsSentence("Well\u2026 "))
    }

    @Test
    fun `an empty field and a fresh line start one too`() {
        assertTrue(TextEdits.startsSentence(""))
        assertTrue(TextEdits.startsSentence(null))
        assertTrue(TextEdits.startsSentence("a line\n"))
        assertTrue(TextEdits.startsSentence("   "))
    }

    /**
     * Not on the mark itself. `e.g.` and `3.14` are typed tight, and
     * capitalising between the dot and the next character would fight both.
     */
    @Test
    fun `a mark with nothing after it is still mid-sentence`() {
        assertFalse(TextEdits.startsSentence("Hello."))
        assertFalse(TextEdits.startsSentence("e.g."))
        assertFalse(TextEdits.startsSentence("3."))
    }

    @Test
    fun `an ordinary word and space does not`() {
        assertFalse(TextEdits.startsSentence("hello world "))
        assertFalse(TextEdits.startsSentence("a comma, "))
        assertFalse(TextEdits.startsSentence("a colon: "))
    }

    /** The mark can hide behind a closing quote or bracket. */
    @Test
    fun `quotes and brackets between the mark and the space are stepped over`() {
        assertTrue(TextEdits.startsSentence("He said \"Stop.\" "))
        assertTrue(TextEdits.startsSentence("(Ask him.) "))
        assertTrue(TextEdits.startsSentence("\u201eHalt!\u201c "))
    }
}
