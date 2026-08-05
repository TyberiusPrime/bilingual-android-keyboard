package de.coonabibba.bikeyboard

/**
 * Re-casing a word that is already typed (D23).
 *
 * Three states in a cycle, because those are the three a word is ever wanted
 * in: as typed in lower case, capitalised, and shouted. Swiping up on shift
 * moves to the next one, so fixing a German noun that was typed without shift
 * is a gesture rather than four keystrokes.
 */
object TextCase {

    fun cycle(word: String): String = when (state(word)) {
        State.LOWER -> capitalised(word)
        State.CAPITALISED -> word.uppercase()
        State.UPPER -> word.lowercase()
    }

    private enum class State { LOWER, CAPITALISED, UPPER }

    private fun state(word: String): State {
        val letters = word.filter { it.isLetter() }
        return when {
            // No letters to speak of: anything is as good as anything, and
            // lower is where the cycle starts.
            letters.isEmpty() -> State.UPPER
            letters.none { it.isUpperCase() } -> State.LOWER
            // A one-letter word has only two states to be in, so its capital
            // form counts as the shouted one and the cycle stays two long.
            letters.all { it.isUpperCase() } -> State.UPPER
            letters.first().isUpperCase() && letters.drop(1).none { it.isUpperCase() } ->
                State.CAPITALISED

            // Anything else — `mcDonald`, `iPhone` — has been cased
            // deliberately, so the cycle takes it to shouting rather than
            // pretending to know better.
            else -> State.CAPITALISED
        }
    }

    /**
     * `ß` has no capital form worth using here: `.uppercase()` turns it into
     * `SS`, which is right for shouting and wrong for a single leading letter.
     * It never leads a German word anyway, so only the first character is
     * touched.
     */
    private fun capitalised(word: String): String =
        word.replaceFirstChar { it.uppercaseChar() }
}
