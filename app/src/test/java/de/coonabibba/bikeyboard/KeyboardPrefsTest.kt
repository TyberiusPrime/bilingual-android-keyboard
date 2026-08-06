package de.coonabibba.bikeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The slider definitions, checked without a `Context`.
 *
 * Worth testing because a bad one is not a wrong number on screen: the lookup
 * by key throws when a key is missing, and `coerceIn` throws when a range is
 * inverted — and both of those happen inside a view constructor, which means
 * the keyboard does not appear at all.
 */
class KeyboardPrefsTest {

    private val all = KeyboardPrefs.TIMINGS + KeyboardPrefs.FLASH

    @Test
    fun `every range is usable`() {
        all.forEach { range ->
            assertTrue("${range.key}: min above max", range.min <= range.max)
            assertTrue(
                "${range.key}: default ${range.default} outside ${range.min}..${range.max}",
                range.default in range.min..range.max,
            )
        }
    }

    /**
     * Lookup is by string key and returns the first match, so a duplicate would
     * silently shadow whichever definition came second.
     */
    @Test
    fun `keys are unique across both lists`() {
        val keys = all.map { it.key }
        assertEquals(keys.size, keys.distinct().size)
    }

    /**
     * Every key a view looks up by name has to be in one of the lists, or the
     * lookup throws where nothing can catch it.
     */
    @Test
    fun `every key looked up by name is defined`() {
        listOf(
            KeyboardPrefs.KEY_LONG_PRESS_MS,
            KeyboardPrefs.SUGGESTION_LONG_PRESS_MS,
            KeyboardPrefs.REPEAT_DELAY_MS,
            KeyboardPrefs.REPEAT_INTERVAL_MS,
            KeyboardPrefs.DOUBLE_TAP_MS,
            KeyboardPrefs.FLASH_MS,
            KeyboardPrefs.FLASH_ALPHA,
            KeyboardPrefs.FLASH_HOLD,
            KeyboardPrefs.FLASH_REACH,
            KeyboardPrefs.FLASH_SOFTNESS,
        ).forEach { key ->
            assertTrue("$key is not in TIMINGS or FLASH", all.any { it.key == key })
        }
    }

    @Test
    fun `every range has a label`() {
        all.forEach { assertTrue("${it.key} has no label", it.label != 0) }
    }

    /** Setting the flash to zero is how it is switched off, so zero must be reachable. */
    @Test
    fun `the flash can be turned off`() {
        assertEquals(0, KeyboardPrefs.FLASH.first { it.key == KeyboardPrefs.FLASH_MS }.min)
    }

    /** The correction threshold is a percentage and must stay one. */
    @Test
    fun `the confidence range is a sane percentage`() {
        assertTrue(KeyboardPrefs.MIN_CONFIDENCE in 1..99)
        assertTrue(KeyboardPrefs.MAX_CONFIDENCE in KeyboardPrefs.MIN_CONFIDENCE..99)
        assertTrue(
            KeyboardPrefs.DEFAULT_AUTO_CORRECT_CONFIDENCE in
                KeyboardPrefs.MIN_CONFIDENCE..KeyboardPrefs.MAX_CONFIDENCE,
        )
    }

    @Test
    fun `the suggestion size range holds its default`() {
        assertTrue(
            KeyboardPrefs.DEFAULT_TEXT_SP in KeyboardPrefs.MIN_TEXT_SP..KeyboardPrefs.MAX_TEXT_SP,
        )
    }
}
