package de.coonabibba.bikeyboard

import java.io.File

/**
 * The words the user added by hand.
 *
 * Under D8 this is the *only* thing that ever teaches the keyboard anything.
 * Nothing typed is absorbed silently, undo teaches it nothing, and a tapped
 * suggestion teaches it nothing either — a word gets in here because someone
 * asked for it to be, and no other way. Which means the friction of asking sets
 * the ceiling on how good this keyboard ever gets for this user, so the ask is
 * one tap in the suggestion strip at the moment the word is typed.
 *
 * Stored as one word per line in the app's own files directory: ordinary
 * credential-encrypted storage, which D18 makes available by giving up
 * pre-unlock availability.
 *
 * The in-memory set is the truth during a session; [persist] is handed to the
 * caller so the write can happen off the main thread.
 */
class PersonalStore(private val file: File) {

    private val words = LinkedHashSet<String>()

    /** Reads the file, if it exists. Cheap: this is a user's own vocabulary. */
    fun load() {
        words.clear()
        if (!file.exists()) return
        file.forEachLine { line ->
            val word = line.trim()
            if (word.isNotEmpty()) words.add(word)
        }
    }

    fun all(): List<String> = words.toList()

    /**
     * Adds [word], returning whether it was new. Does not write anything —
     * [persist] does that, on whatever thread the caller likes.
     */
    fun add(word: String): Boolean = words.add(word)

    fun persist() {
        file.parentFile?.mkdirs()
        file.writeText(words.joinToString(separator = "\n", postfix = "\n"))
    }

    /** Whether the store holds [word], ignoring case and accents — see [Lexicon.knows]. */
    fun knows(word: CharSequence): Boolean {
        val folded = Folding.fold(word)
        return folded.isNotEmpty() && words.any { Folding.fold(it) == folded }
    }

    /** Every stored word starting with [foldedPrefix], ignoring case and accents. */
    fun completions(foldedPrefix: String): List<String> {
        if (foldedPrefix.isEmpty()) return emptyList()
        return words.filter { Folding.fold(it).startsWith(foldedPrefix) }
    }
}
