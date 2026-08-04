package de.coonabibba.bikeyboard

/**
 * A key on the visual keyboard.
 *
 * [widthWeight] is relative within its row: a row's keys are laid out
 * proportionally to their weights, so a space bar of weight 5f next to two
 * weight-1f keys takes 5/7 of the row.
 *
 * [longPress] holds the alternates reachable by holding the key. An empty list
 * means the key has no long-press behaviour at all.
 */
data class Key(
    val label: String,
    val action: KeyAction,
    val widthWeight: Float = 1f,
    val longPress: List<String> = emptyList(),
)

sealed interface KeyAction {
    /** Insert [text] at the cursor. */
    data class Text(val text: String) : KeyAction

    data object Backspace : KeyAction
    data object Enter : KeyAction
    data object Shift : KeyAction
    data object Space : KeyAction

    /**
     * Switch between the letter and symbol layers.
     *
     * Deliberately a toggle rather than "switch to layer X": there is exactly
     * one layer-toggle key, in exactly one position, and encoding it this way
     * makes a second toggle elsewhere unrepresentable rather than merely
     * discouraged. See D16.
     */
    data object ToggleLayer : KeyAction

    /** Hand over to the next IME on the device (globe key). */
    data object NextInputMethod : KeyAction
}

enum class Layer { LETTERS, SYMBOLS }

data class KeyboardLayout(val rows: List<List<Key>>)

/**
 * Layout definitions.
 *
 * Standing constraints, all enforced by `LayoutsTest`:
 *  - The layer toggle occupies the identical position and width on every layer
 *    (D16), so it never moves under your thumb.
 *  - No key that inserts text borders the space bar, on any layer (D6). This is
 *    the original complaint: a thumb aimed at space must not produce a period.
 *  - Digits 0-9 are all reachable by long-press (D17), and the umlaut keys
 *    carry umlauts rather than digits.
 *  - One letter layer, regardless of language (D5). Language awareness is a
 *    matter of prediction, not of layout.
 */
object Layouts {

    /**
     * Digits sit on the top row in their positional places — q=1, w=2, … p=0 —
     * matching a number row, except where an umlaut has claimed the key.
     *
     * `u` and `o` would otherwise carry 7 and 9. Umlauts win there (D17), so
     * those two digits move down to the keys nearest below them on a staggered
     * QWERTY: 7 to `j`, 9 to `l`. This is the one arbitrary bit of the layout
     * and the most likely thing here to want changing after a week of use.
     */
    private val letterAlternates: Map<Char, List<String>> = mapOf(
        'q' to listOf("1"),
        'w' to listOf("2"),
        'e' to listOf("3"),
        'r' to listOf("4"),
        't' to listOf("5"),
        'y' to listOf("6"),
        'u' to listOf("ü"),
        'i' to listOf("8"),
        'o' to listOf("ö"),
        'p' to listOf("0"),
        'a' to listOf("ä"),
        's' to listOf("ß"),
        'j' to listOf("7"),
        'l' to listOf("9"),
        // Punctuation reachable without the symbol layer. This does not
        // reopen complaint 3: D6 bars punctuation as a *tap target* beside the
        // space bar, and a long-press cannot be hit by accident.
        'v' to listOf("'"),
        'b' to listOf(","),
        'n' to listOf("!"),
        'm' to listOf("?"),
    )

    private fun letterRow(chars: String): List<Key> = chars.map { char ->
        Key(
            label = char.toString(),
            action = KeyAction.Text(char.toString()),
            longPress = letterAlternates[char].orEmpty(),
        )
    }

    private fun symbolRow(vararg specs: Pair<String, List<String>>): List<Key> =
        specs.map { (label, alternates) ->
            Key(label = label, action = KeyAction.Text(label), longPress = alternates)
        }

    /** The bottom row is byte-identical across layers apart from the toggle label. */
    private fun bottomRow(toggleLabel: String): List<Key> = listOf(
        Key(toggleLabel, KeyAction.ToggleLayer, widthWeight = 1.5f),
        Key("🌐", KeyAction.NextInputMethod, widthWeight = 1f),
        Key("", KeyAction.Space, widthWeight = 5f),
        Key("↵", KeyAction.Enter, widthWeight = 1.5f),
    )

    val letters = KeyboardLayout(
        listOf(
            letterRow("qwertyuiop"),
            letterRow("asdfghjkl"),
            buildList {
                add(Key("⇧", KeyAction.Shift, widthWeight = 1.5f))
                addAll(letterRow("zxcvbnm"))
                add(Key("⌫", KeyAction.Backspace, widthWeight = 1.5f))
            },
            bottomRow("?123"),
        ),
    )

    /**
     * There is no third "more symbols" layer: the rarer glyphs hang off
     * long-press here, which keeps the single-toggle-position rule (D16)
     * intact rather than adding a second toggle to reach them.
     */
    val symbols = KeyboardLayout(
        listOf(
            symbolRow(
                "1" to listOf("¹"), "2" to listOf("²"), "3" to listOf("³"),
                "4" to emptyList(), "5" to emptyList(), "6" to emptyList(),
                "7" to emptyList(), "8" to emptyList(), "9" to emptyList(),
                "0" to emptyList(),
            ),
            symbolRow(
                "@" to listOf("©", "®", "™"),
                "#" to listOf("№"),
                "$" to listOf("€", "£", "¥", "¢"),
                "%" to listOf("‰", "°"),
                "&" to listOf("§", "¶"),
                "-" to listOf("_", "–", "—"),
                "+" to listOf("=", "±", "×", "÷"),
                "(" to listOf("[", "{", "<"),
                ")" to listOf("]", "}", ">"),
                "/" to listOf("\\", "|"),
            ),
            buildList {
                addAll(
                    symbolRow(
                        "*" to listOf("^", "~", "•"),
                        "\"" to listOf("“", "”", "„"),
                        "'" to listOf("‘", "’", "‚"),
                        ":" to emptyList(),
                        ";" to emptyList(),
                        "," to emptyList(),
                        "." to listOf("…"),
                        "!" to listOf("¡"),
                        "?" to listOf("¿"),
                    ),
                )
                add(Key("⌫", KeyAction.Backspace, widthWeight = 1.5f))
            },
            bottomRow("ABC"),
        ),
    )

    fun forLayer(layer: Layer): KeyboardLayout = when (layer) {
        Layer.LETTERS -> letters
        Layer.SYMBOLS -> symbols
    }

    fun other(layer: Layer): Layer = when (layer) {
        Layer.LETTERS -> Layer.SYMBOLS
        Layer.SYMBOLS -> Layer.LETTERS
    }
}
