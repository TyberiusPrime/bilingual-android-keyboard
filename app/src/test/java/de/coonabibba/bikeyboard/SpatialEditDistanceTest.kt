package de.coonabibba.bikeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialEditDistanceTest {

    /** Typed exactly, with nothing nearby. */
    private fun clean(word: String) = word.map { TypedTouch(it, emptyMap()) }

    /** Typed with the thumb sitting between [char] and [neighbour]. */
    private fun grazing(word: String, index: Int, neighbour: Char, cost: Float = 0.1f) =
        word.mapIndexed { i, char ->
            if (i == index) TypedTouch(char, mapOf(neighbour to cost)) else TypedTouch(char, emptyMap())
        }

    @Test
    fun `the word that was typed costs nothing`() {
        assertEquals(0f, SpatialEditDistance.between(clean("hello"), "hello", 2f), 0.001f)
        assertEquals(0f, SpatialEditDistance.between(clean("hello"), "HELLO", 2f), 0.001f)
    }

    /**
     * The distinction the whole feature rests on: the same edit costs a
     * fraction when the thumb was on the border and full price when it was not.
     */
    @Test
    fun `a slip onto a neighbouring key is cheap, a random substitution is not`() {
        val grazed = SpatialEditDistance.between(grazing("hallo", 1, 'e'), "hello", 2f)
        val random = SpatialEditDistance.between(clean("hallo"), "hello", 2f)
        assertEquals(0.1f, grazed, 0.001f)
        assertEquals(1f, random, 0.001f)
        assertTrue(grazed < random)
    }

    /** D5's cheap win: the umlaut costs a long-press, so skipping it is nearly free. */
    @Test
    fun `a missing umlaut is almost no distance at all`() {
        val cost = SpatialEditDistance.between(clean("uber"), "über", 2f)
        assertTrue("cost was $cost", cost in 0.05f..0.2f)
    }

    @Test
    fun `insertions and deletions cost a whole one`() {
        assertEquals(1f, SpatialEditDistance.between(clean("helo"), "hello", 2f), 0.001f)
        assertEquals(1f, SpatialEditDistance.between(clean("helllo"), "hello", 2f), 0.001f)
    }

    @Test
    fun `a transposition is less than two substitutions`() {
        val cost = SpatialEditDistance.between(clean("teh"), "the", 2f)
        assertTrue("cost was $cost", cost < 1f)
    }

    @Test
    fun `anything past the budget is reported as past it`() {
        val cost = SpatialEditDistance.between(clean("hello"), "xxxxx", 1.5f)
        assertTrue(cost > 1.5f)
    }

    @Test
    fun `a length difference bigger than the budget is refused immediately`() {
        assertTrue(SpatialEditDistance.between(clean("a"), "abcd", 1f) > 1f)
    }

    @Test
    fun `an empty word is the length of the candidate away`() {
        assertEquals(2f, SpatialEditDistance.between(emptyList(), "ab", 3f), 0.001f)
    }
}
