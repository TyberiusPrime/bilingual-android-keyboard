package de.coonabibba.bikeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutsTest {

    private fun rowContainingSpace(layout: KeyboardLayout): List<Key> =
        layout.rows.first { row -> row.any { it.action == KeyAction.Space } }

    @Test
    fun `every layer has exactly one space key`() {
        Layer.entries.forEach { layer ->
            val spaces = Layouts.forLayer(layer).rows.flatten()
                .filter { it.action == KeyAction.Space }
            assertEquals("layer $layer", 1, spaces.size)
        }
    }

    /**
     * The original complaint: a thumb aimed at the space bar lands on the period
     * next to it. On no layer may anything that inserts text border the space
     * bar. (D6)
     */
    @Test
    fun `no text key is adjacent to the space bar on any layer`() {
        Layer.entries.forEach { layer ->
            val row = rowContainingSpace(Layouts.forLayer(layer))
            val spaceIndex = row.indexOfFirst { it.action == KeyAction.Space }
            listOfNotNull(row.getOrNull(spaceIndex - 1), row.getOrNull(spaceIndex + 1))
                .forEach {
                    assertTrue(
                        "${it.label} sits next to the space bar on $layer",
                        it.action !is KeyAction.Text,
                    )
                }
        }
    }

    @Test
    fun `space bar is the widest key in its row on every layer`() {
        Layer.entries.forEach { layer ->
            val row = rowContainingSpace(Layouts.forLayer(layer))
            val space = row.first { it.action == KeyAction.Space }
            assertEquals("layer $layer", space, row.maxByOrNull { it.widthWeight })
        }
    }

    /**
     * D16: the layer toggle must not move under the thumb. Same row index, same
     * position in the row, same width, on every layer.
     */
    @Test
    fun `layer toggle occupies an identical position and width on every layer`() {
        val positions = Layer.entries.map { layer ->
            val rows = Layouts.forLayer(layer).rows
            val rowIndex = rows.indexOfFirst { row ->
                row.any { it.action == KeyAction.ToggleLayer }
            }
            assertTrue("layer $layer has no layer toggle", rowIndex >= 0)
            val row = rows[rowIndex]
            val keyIndex = row.indexOfFirst { it.action == KeyAction.ToggleLayer }
            Triple(rowIndex, keyIndex, row[keyIndex].widthWeight)
        }
        assertEquals("toggle position differs between layers", 1, positions.distinct().size)
    }

    @Test
    fun `there is exactly one layer toggle per layer`() {
        Layer.entries.forEach { layer ->
            val toggles = Layouts.forLayer(layer).rows.flatten()
                .filter { it.action == KeyAction.ToggleLayer }
            assertEquals("layer $layer", 1, toggles.size)
        }
    }

    /** D17: all ten digits reachable by long-press on the letter layer. */
    @Test
    fun `every digit is reachable by long-press from the letter layer`() {
        val reachable = Layouts.letters.rows.flatten()
            .flatMap { it.longPress }
            .toSet()
        ('0'..'9').forEach { digit ->
            assertTrue("digit $digit is not on any long-press", "$digit" in reachable)
        }
    }

    /** D17: umlauts win over digits on the keys they share. */
    @Test
    fun `umlaut keys carry umlauts and not digits`() {
        val byLabel = Layouts.letters.rows.flatten().associateBy { it.label }
        mapOf("a" to "ä", "o" to "ö", "u" to "ü", "s" to "ß").forEach { (key, expected) ->
            val alternates = byLabel.getValue(key).longPress
            assertEquals("long-press on $key", listOf(expected), alternates)
        }
    }

    /**
     * Punctuation is reachable from the letter layer by long-press only. D6
     * bars it as a tap target beside the space bar; a long-press cannot be hit
     * by accident, so it does not reopen complaint 3.
     */
    @Test
    fun `punctuation is on long-press but never a tap target on the letter layer`() {
        val byLabel = Layouts.letters.rows.flatten().associateBy { it.label }
        mapOf("v" to "'", "b" to ",", "n" to "!", "m" to "?").forEach { (key, expected) ->
            assertEquals("long-press on $key", listOf(expected), byLabel.getValue(key).longPress)
        }

        val tapped = Layouts.letters.rows.flatten()
            .mapNotNull { (it.action as? KeyAction.Text)?.text }
        listOf(".", ",", "!", "?", "'").forEach {
            assertTrue("$it is a tap target on the letter layer", it !in tapped)
        }
    }

    @Test
    fun `long-press alternates are never empty strings`() {
        Layer.entries.forEach { layer ->
            Layouts.forLayer(layer).rows.flatten().forEach { key ->
                key.longPress.forEach {
                    assertTrue("empty alternate on ${key.label} in $layer", it.isNotEmpty())
                }
            }
        }
    }

    /** Backspace is the only key that should machine-gun when held. */
    @Test
    fun `backspace repeats and nothing else does`() {
        Layer.entries.forEach { layer ->
            Layouts.forLayer(layer).rows.flatten().forEach { key ->
                assertEquals(
                    "${key.label} on $layer",
                    key.action == KeyAction.Backspace,
                    key.repeats,
                )
            }
        }
    }

    @Test
    fun `every layer can be reached and every row has keys`() {
        Layer.entries.forEach { layer ->
            val rows = Layouts.forLayer(layer).rows
            assertTrue("layer $layer has no rows", rows.isNotEmpty())
            rows.forEach { assertTrue("empty row in $layer", it.isNotEmpty()) }
        }
    }

    @Test
    fun `toggling twice returns to the starting layer`() {
        Layer.entries.forEach { layer ->
            assertEquals(layer, Layouts.other(Layouts.other(layer)))
        }
    }
}
