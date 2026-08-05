package de.coonabibba.bikeyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class EditDistanceTest {

    @Test
    fun `a word is no distance from itself`() {
        assertEquals(0, EditDistance.between("hello", "hello", 2))
    }

    @Test
    fun `one insertion, deletion or substitution is one`() {
        assertEquals(1, EditDistance.between("hello", "helo", 2))
        assertEquals(1, EditDistance.between("helo", "hello", 2))
        assertEquals(1, EditDistance.between("hello", "hallo", 2))
    }

    /** The reason this is not plain Levenshtein: `teh` is one slip, not two. */
    @Test
    fun `a transposition is one edit`() {
        assertEquals(1, EditDistance.between("the", "teh", 2))
        assertEquals(1, EditDistance.between("hello", "hlelo", 2))
    }

    @Test
    fun `two edits are two`() {
        assertEquals(2, EditDistance.between("hello", "hxllx", 2))
        assertEquals(2, EditDistance.between("keyboard", "kyboad", 2))
    }

    @Test
    fun `anything past the limit is reported as past it, not computed`() {
        assertEquals(3, EditDistance.between("hello", "xxxxx", 2))
        assertEquals(2, EditDistance.between("hello", "hxllx", 1))
        assertEquals(1, EditDistance.between("hello", "hallo", 0))
    }

    @Test
    fun `a length difference bigger than the limit is refused immediately`() {
        assertEquals(2, EditDistance.between("a", "abcd", 1))
    }

    @Test
    fun `it does not care which way round the words come`() {
        assertEquals(
            EditDistance.between("straße", "strasse", 2),
            EditDistance.between("strasse", "straße", 2),
        )
    }

    @Test
    fun `empty words are handled`() {
        assertEquals(0, EditDistance.between("", "", 1))
        assertEquals(1, EditDistance.between("", "a", 1))
        assertEquals(2, EditDistance.between("", "ab", 1))
    }
}
