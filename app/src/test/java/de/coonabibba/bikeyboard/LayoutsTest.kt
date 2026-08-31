package de.coonabibba.bikeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * D17: umlauts win over digits on the keys they share. D32: they win over
     * the other European accents on the same keys too, by being *first* — the
     * corner hint and what a plain hold commits.
     */
    @Test
    fun `umlaut keys lead with the umlaut and carry no digit`() {
        val byLabel = Layouts.letters.rows.flatten().associateBy { it.label }
        mapOf("a" to "ä", "o" to "ö", "u" to "ü", "s" to "ß").forEach { (key, expected) ->
            val alternates = byLabel.getValue(key).longPress
            assertEquals("long-press on $key leads with", expected, alternates.first())
            assertTrue(
                "long-press on $key carries a digit",
                alternates.none { it.length == 1 && it[0].isDigit() },
            )
        }
    }

    /**
     * D32: an accent added to a key must not displace the digit that was
     * already there, or D17's promise that every digit is one hold away becomes
     * "one hold and a slide away".
     */
    @Test
    fun `a key with a digit still leads with it`() {
        Layouts.letters.rows.flatten().forEach { key ->
            val digit = key.longPress.firstOrNull { it.length == 1 && it[0].isDigit() }
                ?: return@forEach
            assertEquals("long-press on ${key.label} leads with", digit, key.longPress.first())
        }
    }

    /**
     * The popup is one row of cells clamped to the screen. Six is what fits
     * beside the narrowest key on a phone; more would be silently unreachable.
     */
    @Test
    fun `no key has more alternates than the popup can show`() {
        Layer.entries.forEach { layer ->
            Layouts.forLayer(layer).rows.flatten().forEach { key ->
                assertTrue(
                    "${key.label} on $layer has ${key.longPress.size} alternates",
                    key.longPress.size <= MAX_ALTERNATES,
                )
            }
        }
    }

    /** Two keys offering the same alternate means one of them is a dead end. */
    @Test
    fun `no alternate appears on two different letter keys`() {
        val seen = mutableMapOf<String, String>()
        Layouts.letters.rows.flatten().forEach { key ->
            key.longPress.forEach { alternate ->
                val other = seen.put(alternate, key.label)
                assertEquals("$alternate is on both $other and ${key.label}", null, other)
            }
        }
    }

    /** Everything the popups can type folds to a letter of the base alphabet. */
    @Test
    fun `every letter alternate folds to something typable`() {
        Layouts.letters.rows.flatten().forEach { key ->
            key.longPress
                .filter { it.length == 1 && it[0].isLetter() }
                .forEach {
                    val folded = Folding.fold(it)
                    assertTrue(
                        "$it on ${key.label} folds to $folded",
                        folded.all { char -> char in 'a'..'z' },
                    )
                }
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
            // First, so a plain hold gives the punctuation: `n` also carries ñ
            // (D32), and the mark is the common case by a wide margin.
            assertEquals("long-press on $key", expected, byLabel.getValue(key).longPress.first())
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

    // -- the three layers and the one key that reaches them (D16, D52, D53) ---

    private fun toggleKeyOn(layer: Layer): Key =
        Layouts.forLayer(layer).rows.flatten().first { it.action == KeyAction.ToggleLayer }

    /**
     * A tap is the two-way toggle it always was. The numbers are not in this
     * cycle: a ring of three put the symbols two presses from the letters, and
     * that is the trip made dozens of times a day.
     */
    @Test
    fun `a tap swaps letters and symbols, and leaves the numbers`() {
        assertEquals(Layer.SYMBOLS, Layouts.next(Layer.LETTERS))
        assertEquals(Layer.LETTERS, Layouts.next(Layer.SYMBOLS))
        assertEquals(Layer.LETTERS, Layouts.next(Layer.NUMBERS))
    }

    /** A hold is a switch: it reaches the numbers from anywhere, and leaves them. */
    @Test
    fun `a hold reaches the numbers and gets back out`() {
        assertEquals(Layer.NUMBERS, Layouts.held(Layer.LETTERS))
        assertEquals(Layer.NUMBERS, Layouts.held(Layer.SYMBOLS))
        assertEquals(Layer.LETTERS, Layouts.held(Layer.NUMBERS))
        // Never inert, wherever it is pressed: a hold that did nothing on the
        // third layer would be the dead control this project has shipped twice
        // already (D38, D40).
        Layer.entries.forEach { layer ->
            assertTrue("the hold does nothing on $layer", Layouts.held(layer) != layer)
        }
    }

    /** Every layer is reachable, or one of them may as well not exist. */
    @Test
    fun `every layer is one gesture away from the letters`() {
        assertEquals(
            Layer.entries.toSet(),
            setOf(Layer.LETTERS, Layouts.next(Layer.LETTERS), Layouts.held(Layer.LETTERS)),
        )
    }

    /** The label names where a tap goes, on every layer, or it is decoration. */
    @Test
    fun `the layer key says where a tap leads`() {
        val expected = mapOf(
            Layer.LETTERS to Layouts.TO_LETTERS_LABEL,
            Layer.SYMBOLS to Layouts.TO_SYMBOLS_LABEL,
            Layer.NUMBERS to Layouts.TO_NUMBERS_LABEL,
        )
        Layer.entries.forEach { layer ->
            assertEquals(
                "the key on $layer",
                expected.getValue(Layouts.next(layer)),
                toggleKeyOn(layer).label,
            )
        }
    }

    /**
     * And the corner says where a hold goes, in the same place every other key
     * on this board advertises its hold (D17) — except where the hold would
     * only repeat the tap, which is nothing worth advertising.
     */
    @Test
    fun `the layer key advertises the numbers in its corner`() {
        listOf(Layer.LETTERS, Layer.SYMBOLS).forEach { layer ->
            assertEquals("the corner on $layer", Layouts.TO_NUMBERS_LABEL, toggleKeyOn(layer).holdHint)
        }
        assertEquals(null, toggleKeyOn(Layer.NUMBERS).holdHint)
    }

    /**
     * The hold has to survive the trip through the view, which only starts its
     * timer for a key with something at the end of it.
     */
    @Test
    fun `the keys with a hold are the ones that say they have one`() {
        assertTrue(KeyAction.ToggleLayer.hasHold())
        assertTrue(KeyAction.Personal.hasHold())
        assertFalse(KeyAction.Space.hasHold())
        assertFalse(KeyAction.Shift.hasHold())
        assertFalse(KeyAction.Text("a").hasHold())
    }

    /**
     * A hold on this key never opens a popup, so a corner hint must not be an
     * alternate as well — the two would race and the popup would win.
     */
    @Test
    fun `the layer key carries no alternates to open`() {
        Layer.entries.forEach { layer ->
            assertEquals("alternates on $layer", emptyList<String>(), toggleKeyOn(layer).longPress)
        }
    }

    // -- the number layer (D52) -----------------------------------------------

    private val numbers = Layouts.forLayer(Layer.NUMBERS).rows.flatten()

    @Test
    fun `every digit is a key of its own on the number layer`() {
        val typed = numbers.mapNotNull { (it.action as? KeyAction.Text)?.text }
        ('0'..'9').forEach { digit ->
            assertEquals("digit $digit", 1, typed.count { it == digit.toString() })
        }
    }

    /** The arithmetic, which is the reason for the layer rather than a bonus. */
    @Test
    fun `the calculator symbols are all here`() {
        val typed = numbers.mapNotNull { (it.action as? KeyAction.Text)?.text }.toSet()
        listOf("+", "-", "×", "÷", "=", "%", "(", ")").forEach {
            assertTrue("$it is not on the number layer", it in typed)
        }
        // D2: the two languages disagree about which of these splits a number,
        // so neither can be the one behind a long-press.
        assertTrue("no decimal point", "." in typed)
        assertTrue("no decimal comma", "," in typed)
    }

    /** What a spreadsheet wants is one hold behind what arithmetic looks like. */
    @Test
    fun `the ASCII operators are a hold away`() {
        val byLabel = numbers.associateBy { it.label }
        assertEquals("*", byLabel.getValue("×").longPress.first())
        assertEquals("/", byLabel.getValue("÷").longPress.first())
        // And the minus is the other way round: the hyphen is what a date range
        // and a phone number want, so it is the tap.
        assertEquals("−", byLabel.getValue("-").longPress.first())
    }

    /**
     * Three rows of seven, so the digits form a block instead of a staircase.
     * A number layer whose columns do not line up is just the symbol layer with
     * different characters on it.
     */
    @Test
    fun `the number rows are one grid`() {
        val rows = Layouts.forLayer(Layer.NUMBERS).rows.dropLast(1)
        assertEquals(3, rows.size)
        assertEquals(listOf(7, 7, 7), rows.map { it.size })
        rows.forEach { row ->
            row.forEach { assertEquals("${it.label} is not grid-width", 1f, it.widthWeight, 0f) }
        }
    }

    @Test
    fun `the number layer can delete, in the corner it always is`() {
        val rows = Layouts.forLayer(Layer.NUMBERS).rows
        // Last key of the last row above the space bar, as on the other two.
        listOf(Layer.LETTERS, Layer.SYMBOLS, Layer.NUMBERS).forEach { layer ->
            val above = Layouts.forLayer(layer).rows.dropLast(1).last()
            assertEquals("$layer", KeyAction.Backspace, above.last().action)
        }
        assertTrue(rows.flatten().count { it.action == KeyAction.Backspace } == 1)
    }

    // -- the cursor-steering key and the trail toggle (D38) -------------------

    /**
     * One key steers, so the gesture has one home. More than one and a vertical
     * drag would mean different things in different places.
     *
     * The letter layer only: the symbol layer has no `h`, and hanging the
     * gesture on whatever happens to occupy that position instead would be
     * arbitrary. Moving the cursor is something you do while writing words.
     */
    @Test
    fun `exactly one letter key steers the cursor by lines`() {
        val steering = Layouts.letters.rows.flatten().filter { it.steersLines }
        assertEquals(1, steering.size)
        assertEquals(Layouts.LINE_STEERING_KEY.toString(), steering.single().label)
    }

    @Test
    fun `no other layer claims the gesture`() {
        assertTrue(Layouts.symbols.rows.flatten().none { it.steersLines })
    }

    /**
     * The gesture is a vertical drag on a letter, so the key must not also be
     * carrying a long-press: the hold timer and the drag would race.
     */
    @Test
    fun `the steering key has no long-press of its own`() {
        val steering = Layouts.letters.rows.flatten().first { it.steersLines }
        assertEquals(emptyList<String>(), steering.longPress)
    }

    // -- the key beside the layer toggle (D38, D40) ---------------------------

    private fun positionOf(inPassword: Boolean, action: KeyAction): Pair<Int, Int> {
        val rows = Layer.entries.map { Layouts.forLayer(it, inPassword).rows }
        val positions = rows.map { layer ->
            val rowIndex = layer.indexOfFirst { row -> row.any { it.action == action } }
            assertTrue("$action is missing", rowIndex >= 0)
            assertEquals(
                "$action appears more than once",
                1,
                layer.flatten().count { it.action == action },
            )
            rowIndex to layer[rowIndex].indexOfFirst { it.action == action }
        }
        assertEquals("$action moves between layers", 1, positions.distinct().size)
        return positions.first()
    }

    /**
     * D40: the position next to the layer toggle carries the personal key in an
     * ordinary field and the trail toggle in a password one. Exactly one of
     * them, never both, and always in the same place — the same rule D16 gives
     * the layer toggle, for the same reason.
     */
    @Test
    fun `the personal key and the trail toggle share one position and never both appear`() {
        val personal = positionOf(inPassword = false, KeyAction.Personal)
        val trail = positionOf(inPassword = true, KeyAction.ToggleTrail)
        assertEquals("the two keys sit in different places", personal, trail)

        Layer.entries.forEach { layer ->
            assertTrue(
                "the trail toggle is on an ordinary $layer layout",
                Layouts.forLayer(layer, inPassword = false).rows.flatten()
                    .none { it.action == KeyAction.ToggleTrail },
            )
            assertTrue(
                "the personal key is on a password $layer layout",
                Layouts.forLayer(layer, inPassword = true).rows.flatten()
                    .none { it.action == KeyAction.Personal },
            )
        }
    }

    /**
     * Swapping one key for another must not change anything else about the
     * board. A layout that shifted under the thumb when focus moved to a
     * password box would break D16's promise by the back door.
     */
    @Test
    fun `a password layout differs from an ordinary one in exactly one key`() {
        Layer.entries.forEach { layer ->
            val ordinary = Layouts.forLayer(layer, inPassword = false).rows.flatten()
            val password = Layouts.forLayer(layer, inPassword = true).rows.flatten()
            assertEquals("layer $layer has a different shape", ordinary.size, password.size)
            val differences = ordinary.indices.count { ordinary[it] != password[it] }
            assertEquals("layer $layer differs in $differences keys", 1, differences)
            // And the one that differs is the same width, so nothing moves.
            val index = ordinary.indices.first { ordinary[it] != password[it] }
            assertEquals(ordinary[index].widthWeight, password[index].widthWeight, 0f)
        }
    }

    /** Both of its faces have to be something, or the key goes blank when toggled. */
    @Test
    fun `the trail toggle has a label for either state`() {
        assertTrue(Layouts.TRAIL_ON_LABEL.isNotEmpty())
        assertTrue(Layouts.TRAIL_OFF_LABEL.isNotEmpty())
        assertTrue(Layouts.TRAIL_ON_LABEL != Layouts.TRAIL_OFF_LABEL)
    }

    /**
     * The personal key has no long-press alternates, because holding it means
     * something of its own (D40) and the popup would race the hold.
     */
    @Test
    fun `the personal key carries no alternates`() {
        Layer.entries.forEach { layer ->
            val personal = Layouts.forLayer(layer, inPassword = false).rows.flatten()
                .first { it.action == KeyAction.Personal }
            assertEquals(emptyList<String>(), personal.longPress)
            assertTrue(personal.label.isNotEmpty())
        }
    }

    // -- the enter key, which belongs to the field rather than to us (D49) ----

    private fun enterKeyOf(layout: KeyboardLayout): Key? =
        layout.rows.flatten().singleOrNull { it.action == KeyAction.Enter }

    /**
     * The key says what it is about to do, because on a single-line field it is
     * not going to add a line and a newline glyph there is a lie.
     */
    @Test
    fun `the enter key wears the label the field gave it`() {
        Layer.entries.forEach { layer ->
            assertEquals(
                "layer $layer",
                EnterKey.Newline.label,
                enterKeyOf(Layouts.forLayer(layer, enter = EnterKey.Newline))?.label,
            )
            val send = EnterKey.Action(id = 4, label = "→")
            assertEquals(
                "layer $layer",
                "→",
                enterKeyOf(Layouts.forLayer(layer, enter = send))?.label,
            )
        }
    }

    /**
     * A field that will neither take a newline nor perform an action leaves the
     * key with nothing to do, and a key that does nothing is worse than a gap.
     * The width goes to the space bar rather than to a hole in the row.
     */
    @Test
    fun `a field with no use for the enter key gets none, and the space bar takes the room`() {
        Layer.entries.forEach { layer ->
            val withKey = Layouts.forLayer(layer, enter = EnterKey.Newline)
            val without = Layouts.forLayer(layer, enter = null)
            assertEquals("layer $layer", null, enterKeyOf(without))

            val before = rowContainingSpace(withKey)
            val after = rowContainingSpace(without)
            assertEquals("layer $layer lost a key other than enter", before.size - 1, after.size)
            assertEquals(
                "layer $layer changed width",
                before.sumOf { it.widthWeight.toDouble() },
                after.sumOf { it.widthWeight.toDouble() },
                1e-6,
            )
            // D6 still holds: what the space bar now borders is the row's edge.
            val spaceIndex = after.indexOfFirst { it.action == KeyAction.Space }
            assertEquals("something followed the space bar", after.size - 1, spaceIndex)
        }
    }

    /**
     * Everything else about the board is the field's business no more than the
     * enter key is ours: the rest of the row must not move when it changes.
     */
    @Test
    fun `nothing but the enter key changes with the field`() {
        Layer.entries.forEach { layer ->
            val newline = Layouts.forLayer(layer, enter = EnterKey.Newline).rows.flatten()
            val action = Layouts.forLayer(layer, enter = EnterKey.Action(id = 3, label = "→"))
                .rows.flatten()
            assertEquals("layer $layer has a different shape", newline.size, action.size)
            val differences = newline.indices.filter { newline[it] != action[it] }
            assertEquals("layer $layer differs in $differences", 1, differences.size)
            assertEquals(KeyAction.Enter, newline[differences.single()].action)
        }
    }

    private companion object {
        const val MAX_ALTERNATES = 6
    }
}
