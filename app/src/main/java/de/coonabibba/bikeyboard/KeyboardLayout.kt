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
    /**
     * A label drawn small in the key's corner that is **not** something a hold
     * will type — what holding the key does instead (D53).
     *
     * A key with [longPress] alternates already advertises its first one there
     * (D17), which is the idiom this borrows: the corner of a key is where it
     * says what else it can do. This is for the keys whose hold changes the
     * keyboard rather than inserting anything, and which therefore have no
     * alternate to put there.
     */
    val holdHint: String? = null,
)

/**
 * Whether holding this key means something of its own, popup or not.
 *
 * Two keys do: the personal key remembers a word (D40) and the layer key
 * reaches the numbers (D53). Both need the hold timer even though neither has a
 * popup at the end of it.
 */
fun KeyAction.hasHold(): Boolean = this == KeyAction.Personal || this == KeyAction.ToggleLayer

sealed interface KeyAction {
    /** Insert [text] at the cursor. */
    data class Text(val text: String) : KeyAction

    data object Backspace : KeyAction
    data object Enter : KeyAction
    data object Shift : KeyAction
    data object Space : KeyAction

    /**
     * The one key that changes the board: tapped it swaps letters and symbols,
     * held it reaches the number layer and leaves it again (D52, D53).
     *
     * Deliberately a step rather than "switch to layer X": there is exactly
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
     *
     * Since D40 it appears **only in password fields**, which is the only place
     * that reasoning holds. Everywhere else the position carries [Personal].
     */
    data object ToggleTrail : KeyAction

    /**
     * The personal key (D40): everything to do with words this keyboard knows
     * because it was told.
     *
     * Three things, because they are three points on one idea and there is only
     * one key. A tap opens the quick menu, a hold remembers the word in front
     * of the cursor, and a double tap opens the screen where both are managed.
     */
    data object Personal : KeyAction
}

/**
 * The three boards: two under the layer key's tap, the third under its hold
 * (D52, D53).
 *
 * [NUMBERS] is a calculator rather than a third page of symbols: ten digits in
 * a dialpad block, the arithmetic beside them, and both decimal separators,
 * because D2 has German and English live in the same paragraph and they do not
 * agree on which of `.` and `,` splits a number.
 */
enum class Layer { LETTERS, SYMBOLS, NUMBERS }

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

    /** The personal key (D40). Purple, because it is the one key that learns. */
    const val PERSONAL_LABEL = "+"

    /**
     * What the layer key says (D52, D53).
     *
     * Always the name of where a press goes, never of where you are — a key
     * labelled with the room you are standing in tells you nothing you cannot
     * see. [TO_NUMBERS_LABEL] is the odd one: it is not the key's label but the
     * hint in its corner, because the numbers are behind a *hold*, and a corner
     * hint is how every other key on this board says what a hold gives (D17).
     */
    const val TO_SYMBOLS_LABEL = "?123"
    const val TO_NUMBERS_LABEL = "123"
    const val TO_LETTERS_LABEL = "ABC"

    private fun symbolRow(vararg specs: Pair<String, List<String>>): List<Key> =
        specs.map { (label, alternates) ->
            Key(label = label, action = KeyAction.Text(label), longPress = alternates)
        }

    /**
     * The bottom row is byte-identical across layers apart from the toggle
     * label — and, since D40, apart from which of two keys holds the position
     * next to it.
     *
     * That position was the globe, then the trail toggle (D38), and is now the
     * trail toggle **only in a password field**. Everywhere else it is the
     * personal key. The two swap rather than share because the argument for
     * the trail toggle being on a key at all was always specifically about
     * passwords — the moment somebody is standing behind you — while the
     * argument for the personal key is about every other field: under D8 the
     * store is the only way this keyboard learns anything, and the strip could
     * only offer to add a word when it had a slot going spare.
     *
     * [enter] is what the field being typed into makes of the enter key (D49),
     * and `null` is a field that makes nothing of it: the key is left off and
     * its width goes to the space bar, since a key that does nothing is worse
     * than a gap and much worse than more space bar.
     *
     * [toggleHint] is what a *hold* on the layer key gives (D53), drawn in its
     * corner. Null on the layer a hold would only take you where the tap
     * already goes.
     */
    private fun bottomRow(
        toggleLabel: String,
        toggleHint: String?,
        inPassword: Boolean,
        enter: EnterKey?,
    ): List<Key> = listOfNotNull(
        Key(toggleLabel, KeyAction.ToggleLayer, widthWeight = 1.5f, holdHint = toggleHint),
        if (inPassword) {
            Key(TRAIL_ON_LABEL, KeyAction.ToggleTrail, widthWeight = 1f)
        } else {
            Key(PERSONAL_LABEL, KeyAction.Personal, widthWeight = 1f)
        },
        Key("", KeyAction.Space, widthWeight = if (enter == null) 6.5f else 5f),
        enter?.let { Key(it.label, KeyAction.Enter, widthWeight = 1.5f) },
    )

    private fun buildLetters(inPassword: Boolean, enter: EnterKey?) = KeyboardLayout(
        listOf(
            letterRow("qwertyuiop"),
            letterRow("asdfghjkl"),
            buildList {
                add(Key("⇧", KeyAction.Shift, widthWeight = 1.5f))
                addAll(letterRow("zxcvbnm"))
                add(Key("⌫", KeyAction.Backspace, widthWeight = 1.5f, repeats = true))
            },
            bottomRow(TO_SYMBOLS_LABEL, TO_NUMBERS_LABEL, inPassword, enter),
        ),
    )

    /**
     * There is no third "more symbols" layer: the rarer glyphs hang off
     * long-press here, which keeps the single-toggle-position rule (D16)
     * intact rather than adding a second toggle to reach them.
     */
    private fun buildSymbols(inPassword: Boolean, enter: EnterKey?) = KeyboardLayout(
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
            bottomRow(TO_LETTERS_LABEL, TO_NUMBERS_LABEL, inPassword, enter),
        ),
    )

    /**
     * The number layer (D52): a calculator, not a third page of symbols.
     *
     * **Seven equal keys in each of the three rows, so the columns line up.**
     * That is the whole reason this is worth having as a layer of its own: a
     * digit here is nearly half as wide again as one hiding on the symbol
     * layer's top row, and the block of them sits under the thumb in the shape
     * everybody already knows.
     *
     * ```
     * 1 2 3   +  -   (  )
     * 4 5 6   ×  ÷   %  =
     * 7 8 9   0  .   ,  ⌫
     * ```
     *
     * **Dialpad order, not calculator order.** `1 2 3` on top is what every
     * phone in the world shows and what the thumb has learned from dialling;
     * `7 8 9` on top belongs to a machine with a numeric keypad, and this is
     * not one. What the calculator lends is the *symbols* — the arithmetic in
     * one place instead of scattered through the punctuation.
     *
     * **Both decimal separators, side by side** (D2). `12.50` and `12,50` are
     * the same price in the two languages this keyboard is for, and neither is
     * the odd one out here.
     *
     * **`×` and `÷` lead, with `*` and `/` a hold away.** The glyphs are what
     * arithmetic looks like written down, and are what most things that parse
     * a sum will take; the ASCII pair is what a spreadsheet or a shell wants,
     * so it is one hold behind and drawn in the corner like every other
     * alternate (D17). The minus key is the other way round: it types the
     * ASCII hyphen, because a phone number, a date range and a hyphenated word
     * all want that one, and the true `−` leads its alternates.
     *
     * Backspace keeps the corner it has on the other two layers rather than the
     * width — a grid with one key in it wider than the rest is not a grid.
     */
    private fun buildNumbers(inPassword: Boolean, enter: EnterKey?) = KeyboardLayout(
        listOf(
            symbolRow(
                "+" to listOf("±"),
                "-" to listOf("−", "–", "—"),
                "1" to emptyList(),
                "2" to emptyList(),
                "3" to emptyList(),
                "(" to listOf("[", "{", "<"),
                ")" to listOf("]", "}", ">"),
            ),
            symbolRow(
                "×" to listOf("*"),
                "÷" to listOf("/"),
                "4" to emptyList(),
                "5" to emptyList(),
                "6" to emptyList(),
                "%" to listOf("‰", "°"),
                "=" to listOf("≈", "≠", "≤", "≥"),
            ),
            buildList {
                addAll(
                    symbolRow(
                        // A time is the other thing a number layer is used for,
                        // so the stop leads with a colon: 14:30 needs one more
                        // than it needs an ellipsis.
                        "." to listOf(":", "…"),
                        "," to emptyList(),
                        "7" to emptyList(),
                        "8" to emptyList(),
                        "9" to emptyList(),
                        "0" to emptyList(),
                    ),
                )
                add(Key("⌫", KeyAction.Backspace, repeats = true))
            },
            bottomRow(TO_LETTERS_LABEL, null, inPassword, enter),
        ),
    )

    /**
     * The layouts, built once each and kept.
     *
     * Two, then four with D40's password variant, and now one per distinct
     * enter key on top of that (D49) — which is still a handful, since a field
     * either takes a newline, performs one of five actions, or wants no such
     * key at all. A layout is a few dozen immutable objects and focus moves
     * far more often than a new combination turns up, so they are memoised
     * rather than rebuilt.
     */
    private val cache = HashMap<Triple<Layer, Boolean, EnterKey?>, KeyboardLayout>()

    val letters = forLayer(Layer.LETTERS)
    val symbols = forLayer(Layer.SYMBOLS)

    fun forLayer(
        layer: Layer,
        inPassword: Boolean = false,
        enter: EnterKey? = EnterKey.Newline,
    ): KeyboardLayout = cache.getOrPut(Triple(layer, inPassword, enter)) {
        when (layer) {
            Layer.LETTERS -> buildLetters(inPassword, enter)
            Layer.SYMBOLS -> buildSymbols(inPassword, enter)
            Layer.NUMBERS -> buildNumbers(inPassword, enter)
        }
    }

    /**
     * Where a **tap** on the layer key goes (D53).
     *
     * Letters and symbols, back and forth, exactly as before there was a third
     * layer — one press each way, which is what the pair is worth. The numbers
     * are not in this cycle: a ring of three was tried and the second press
     * back from the symbols is one too many for the trip everybody makes
     * dozens of times a day.
     */
    fun next(layer: Layer): Layer = when (layer) {
        Layer.LETTERS -> Layer.SYMBOLS
        Layer.SYMBOLS, Layer.NUMBERS -> Layer.LETTERS
    }

    /**
     * Where a **hold** on the layer key goes (D53): the numbers, and off them
     * again.
     *
     * A hold that landed you somewhere you could not get out of the same way
     * would be a one-way door, and one that did nothing on the third layer
     * would be an inert control — which this project has caught itself
     * shipping twice (D38, D40). So it is a switch: on, and off.
     */
    fun held(layer: Layer): Layer =
        if (layer == Layer.NUMBERS) Layer.LETTERS else Layer.NUMBERS
}
