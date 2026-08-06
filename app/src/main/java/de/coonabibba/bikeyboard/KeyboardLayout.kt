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
    /**
     * Whether holding the key repeats it. Repeating keys fire on press rather
     * than on release, so they respond immediately.
     */
    val repeats: Boolean = false,
    /**
     * Whether this key can steer the cursor a line at a time (D38), the way
     * dragging across the space bar moves it a character at a time. The layout
     * decides which key, so the view does not have to know a letter by name.
     *
     * Reached by *tapping and then pressing again* rather than by a plain drag,
     * since D39 gave a plain drag off a letter to swipe typing. The two cannot
     * be separated by direction — `h` to `b` is down and to the left, which is
     * exactly what steering looks like — so they are separated by what came
     * before.
     */
    val steersLines: Boolean = false,
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

    /**
     * Show or hide the recent-keypress trail (D19, D38).
     *
     * On a key rather than buried in the settings screen because it is the one
     * setting whose whole purpose is to be turned off in a hurry: the trail
     * says what was just typed, and the moment that matters is the moment
     * somebody is standing behind you.
     */
    data object ToggleTrail : KeyAction
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
 *    carry umlauts rather than digits — as the *first* alternate, which is the
 *    one a plain hold gives and the one drawn in the corner of the key (D32).
 *  - One letter layer, regardless of language (D5). Language awareness is a
 *    matter of prediction, not of layout.
 *  - Exactly one key steers the cursor by lines (D38), and it is a letter on
 *    the home row rather than anything beside the space bar.
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
     *
     * **The first entry is the one the key advertises and the one a plain hold
     * commits.** It is drawn small in the corner of the key, and the popup opens
     * with it already selected, so holding and releasing without sliding gives
     * it. That is what makes the rest of the row free: the accents of the other
     * European languages hang off the same keys (D32), reachable by sliding,
     * and the German umlaut or the digit is untouched in front of them.
     */
    private val letterAlternates: Map<Char, List<String>> = mapOf(
        'q' to listOf("1"),
        'w' to listOf("2"),
        'e' to listOf("3", "é", "è", "ê", "ë"),
        'r' to listOf("4"),
        't' to listOf("5"),
        'y' to listOf("6", "ý", "ÿ"),
        'u' to listOf("ü", "ú", "ù", "û"),
        'i' to listOf("8", "í", "ì", "î", "ï"),
        'o' to listOf("ö", "ó", "ò", "ô", "õ", "ø"),
        'p' to listOf("0"),
        'a' to listOf("ä", "á", "à", "â", "å", "ã"),
        's' to listOf("ß", "š", "ś"),
        'j' to listOf("7"),
        'l' to listOf("9", "ł"),
        // Keys with no digit and no German letter to carry, so the accent leads.
        'c' to listOf("ç", "č", "ć"),
        'z' to listOf("ž", "ź", "ż"),
        // Punctuation reachable without the symbol layer. This does not
        // reopen complaint 3: D6 bars punctuation as a *tap target* beside the
        // space bar, and a long-press cannot be hit by accident.
        'v' to listOf("'"),
        'b' to listOf(","),
        'n' to listOf("!", "ñ"),
        'm' to listOf("?"),
    )

    private fun letterRow(chars: String): List<Key> = chars.map { char ->
        Key(
            label = char.toString(),
            action = KeyAction.Text(char.toString()),
            longPress = letterAlternates[char].orEmpty(),
            steersLines = char == LINE_STEERING_KEY,
        )
    }

    /**
     * The key that steers the cursor by lines (D38).
     *
     * `h` because it is the middle of the home row, so the gesture is reachable
     * with either thumb without looking, and because it carries no long-press
     * of its own to compete with.
     *
     * The tap that arms the gesture types an `h`, which is then taken back when
     * the drag begins (D39). Only on the drag: a plain double tap still types
     * both, so `withhold` and `Rohheit` cost nothing. Choosing a letter that
     * genuinely never doubles would have avoided the retraction, but no letter
     * on the home row qualifies, and being in the middle of the home row is the
     * whole reason this one was picked.
     */
    const val LINE_STEERING_KEY = 'h'

    /** What the trail toggle shows when the trail is on, and when it is off. */
    const val TRAIL_ON_LABEL = "◉"
    const val TRAIL_OFF_LABEL = "○"

    private fun symbolRow(vararg specs: Pair<String, List<String>>): List<Key> =
        specs.map { (label, alternates) ->
            Key(label = label, action = KeyAction.Text(label), longPress = alternates)
        }

    /** The bottom row is byte-identical across layers apart from the toggle label. */
    private fun bottomRow(toggleLabel: String): List<Key> = listOf(
        Key(toggleLabel, KeyAction.ToggleLayer, widthWeight = 1.5f),
        // Where the globe used to be. The system draws its own IME switcher —
        // in the navigation bar on this phone — so a second one cost a key
        // position for nothing.
        Key(TRAIL_ON_LABEL, KeyAction.ToggleTrail, widthWeight = 1f),
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
                add(Key("⌫", KeyAction.Backspace, widthWeight = 1.5f, repeats = true))
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
                add(Key("⌫", KeyAction.Backspace, widthWeight = 1.5f, repeats = true))
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
