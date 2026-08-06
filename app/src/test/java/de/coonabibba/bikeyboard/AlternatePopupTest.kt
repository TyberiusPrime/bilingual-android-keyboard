package de.coonabibba.bikeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D36: the first alternate belongs under the finger.
 *
 * These are the cases that went wrong on the phone — `u` and `n` switching away
 * from `ü` and `!` on the slightest drift — expressed as arithmetic so they
 * cannot come back.
 */
class AlternatePopupTest {

    private val width = 1080f
    private val cell = 108f
    private val gap = 6f

    private fun lefts(count: Int, keyCentre: Float) =
        AlternatePopup.cellLefts(count, keyCentre, cell, width, gap).toList()

    /** Whether the cell for [index] contains [x]. */
    private fun holds(lefts: List<Float>, index: Int, x: Float): Boolean =
        x >= lefts[index] && x <= lefts[index] + cell

    /** The whole point. Wherever the key is, a hold without a drag gives cell zero. */
    @Test
    fun `the first cell is under the key, for every key on the board`() {
        listOf(1, 2, 3, 4, 6).forEach { count ->
            var centre = cell / 2f
            while (centre < width) {
                val row = lefts(count, centre)
                assertTrue(
                    "count $count at $centre: cell 0 is ${row[0]}..${row[0] + cell}",
                    holds(row, 0, centre),
                )
                centre += cell
            }
        }
    }

    /** `u`, on the right of the board: ü under the thumb, the rest to its left. */
    @Test
    fun `a key on the right extends leftwards`() {
        val row = lefts(4, keyCentre = 700f)
        assertEquals(646f, row[0], 0.5f)
        assertTrue("later cells should be further left: $row", row == row.sortedDescending())
    }

    /** `a`, on the left: ä under the thumb, the rest to its right. */
    @Test
    fun `a key on the left extends rightwards`() {
        val row = lefts(6, keyCentre = 60f)
        assertTrue("later cells should be further right: $row", row == row.sorted())
        assertTrue("cell 0 holds the finger: $row", holds(row, 0, 60f))
    }

    /** Cells abut without overlapping, whichever way the row runs. */
    @Test
    fun `cells tile without gaps or overlaps`() {
        listOf(60f, 540f, 1020f).forEach { centre ->
            val row = lefts(5, centre).sorted()
            row.zipWithNext { a, b -> assertEquals(cell, b - a, 0.01f) }
        }
    }

    @Test
    fun `the row stays on screen at either edge`() {
        listOf(0f, cell / 2f, width - cell / 2f, width).forEach { centre ->
            val row = lefts(6, centre)
            assertTrue("$centre: ${row.min()} is off the left", row.min() >= gap - 0.01f)
            assertTrue("$centre: ${row.max() + cell} is off the right", row.max() + cell <= width - gap + 0.01f)
        }
    }

    /**
     * A single alternate is the old behaviour and must stay it: centred on the
     * key, which is where every key with one alternate has always drawn it.
     */
    @Test
    fun `one alternate is centred on the key`() {
        assertEquals(540f - cell / 2f, lefts(1, 540f).single(), 0.01f)
    }

    /** Degenerate input is arithmetic, not a crash. */
    @Test
    fun `no alternates is no cells`() {
        assertTrue(lefts(0, 540f).isEmpty())
        assertTrue(AlternatePopup.cellLefts(3, 540f, 0f, width, gap).isEmpty())
    }

    /**
     * More cells than the screen can hold should not be re-ordered to fit — the
     * near ones stay reachable and the far ones run off, which is the failure
     * that keeps the first alternate working.
     */
    @Test
    fun `an over-wide row keeps its near cells reachable`() {
        val row = AlternatePopup.cellLefts(20, 540f, cell, width, gap)
        assertTrue("cell 0 should still be on screen: ${row[0]}", row[0] in 0f..width - cell)
    }
}
