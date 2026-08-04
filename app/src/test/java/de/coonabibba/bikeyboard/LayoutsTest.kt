package de.coonabibba.bikeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutsTest {

    @Test
    fun `letter layer has exactly one space key`() {
        val spaces = Layouts.letters.rows.flatten().filter { it.action == KeyAction.Space }
        assertEquals(1, spaces.size)
    }

    /**
     * The original complaint: a thumb aimed at the space bar lands on the period
     * next to it. On the letter layer nothing that inserts text may border the
     * space bar.
     */
    @Test
    fun `no text key is adjacent to the space bar on the letter layer`() {
        val row = Layouts.letters.rows.first { row -> row.any { it.action == KeyAction.Space } }
        val spaceIndex = row.indexOfFirst { it.action == KeyAction.Space }
        listOfNotNull(row.getOrNull(spaceIndex - 1), row.getOrNull(spaceIndex + 1)).forEach {
            assertTrue(
                "${it.label} sits next to the space bar",
                it.action !is KeyAction.Text,
            )
        }
    }

    @Test
    fun `space bar is the widest key in its row`() {
        val row = Layouts.letters.rows.first { row -> row.any { it.action == KeyAction.Space } }
        val space = row.first { it.action == KeyAction.Space }
        assertEquals(space, row.maxByOrNull { it.widthWeight })
    }

    @Test
    fun `every layer can be reached and every row has keys`() {
        Layer.entries.forEach { layer ->
            val rows = Layouts.forLayer(layer).rows
            assertTrue("layer $layer has no rows", rows.isNotEmpty())
            rows.forEach { assertTrue("empty row in $layer", it.isNotEmpty()) }
        }
    }
}
