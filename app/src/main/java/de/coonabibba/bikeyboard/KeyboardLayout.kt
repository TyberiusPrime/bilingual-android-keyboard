package de.coonabibba.bikeyboard

/**
 * A key on the visual keyboard.
 *
 * [widthWeight] is relative within its row: a row's keys are laid out
 * proportionally to their weights, so a space bar of weight 5f next to two
 * weight-1f keys takes 5/7 of the row.
 */
data class Key(
    val label: String,
    val action: KeyAction,
    val widthWeight: Float = 1f,
)

sealed interface KeyAction {
    /** Insert [text] at the cursor. */
    data class Text(val text: String) : KeyAction

    data object Backspace : KeyAction
    data object Enter : KeyAction
    data object Shift : KeyAction
    data object Space : KeyAction

    /** Switch to another on-screen layer (letters / symbols). */
    data class SwitchLayer(val layer: Layer) : KeyAction

    /** Hand over to the next IME on the device (globe key). */
    data object NextInputMethod : KeyAction
}

enum class Layer { LETTERS, SYMBOLS }

data class KeyboardLayout(val rows: List<List<Key>>)

private fun textRow(chars: String): List<Key> =
    chars.map { Key(it.toString(), KeyAction.Text(it.toString())) }

/**
 * Scaffold layouts.
 *
 * Two deliberate choices, both up for revision once the design doc lands:
 *  - The space bar is wide and has NO punctuation key directly beside it, so a
 *    slightly-off thumb still produces a space rather than a period.
 *  - There is a single letter layer regardless of the language being typed;
 *    language awareness is a matter of prediction, not of layout.
 */
object Layouts {

    val letters = KeyboardLayout(
        listOf(
            textRow("qwertyuiop"),
            textRow("asdfghjkl"),
            buildList {
                add(Key("⇧", KeyAction.Shift, widthWeight = 1.5f))
                addAll(textRow("zxcvbnm"))
                add(Key("⌫", KeyAction.Backspace, widthWeight = 1.5f))
            },
            listOf(
                Key("?123", KeyAction.SwitchLayer(Layer.SYMBOLS), widthWeight = 1.5f),
                Key("🌐", KeyAction.NextInputMethod, widthWeight = 1f),
                Key("", KeyAction.Space, widthWeight = 5f),
                Key("↵", KeyAction.Enter, widthWeight = 1.5f),
            ),
        ),
    )

    val symbols = KeyboardLayout(
        listOf(
            textRow("1234567890"),
            textRow("@#\$%&-+()/"),
            buildList {
                add(Key("=\\<", KeyAction.SwitchLayer(Layer.SYMBOLS), widthWeight = 1.5f))
                addAll(textRow("*\"':;!?"))
                add(Key("⌫", KeyAction.Backspace, widthWeight = 1.5f))
            },
            listOf(
                Key("ABC", KeyAction.SwitchLayer(Layer.LETTERS), widthWeight = 1.5f),
                Key(",", KeyAction.Text(","), widthWeight = 1f),
                Key("", KeyAction.Space, widthWeight = 4f),
                Key(".", KeyAction.Text("."), widthWeight = 1f),
                Key("↵", KeyAction.Enter, widthWeight = 1.5f),
            ),
        ),
    )

    fun forLayer(layer: Layer): KeyboardLayout = when (layer) {
        Layer.LETTERS -> letters
        Layer.SYMBOLS -> symbols
    }
}
