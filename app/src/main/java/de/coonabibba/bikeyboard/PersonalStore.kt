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
 * It is also the only thing the user can be *wrong* about, so the launcher
 * screen lists what is in here and can take words back out. A store that only
 * grows would eventually be full of half-typed mistakes with no way to say so.
 *
 * Stored as one word per line in the app's own files directory: ordinary
 * credential-encrypted storage, which D18 makes available by giving up
 * pre-unlock availability.
 *
 * **Read from the typing thread, written from a disk thread, and edited from
 * another activity entirely.** The word list is therefore replaced wholesale
 * rather than mutated, so a reader always sees one complete version or another;
 * and [reloadIfChanged] exists because the keyboard service and the review
 * screen each hold their own copy of a file the other one may have rewritten.
 */
class PersonalStore(private val file: File) {

    @Volatile
    private var words: List<String> = emptyList()

    /** The file's modification time when [words] was read, to notice edits elsewhere. */
    @Volatile
    private var loadedAt = NEVER

    /** Reads the file, if it exists. Cheap: this is one person's vocabulary. */
    fun load() {
        words = if (file.exists()) {
            file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        } else {
            emptyList()
        }
        loadedAt = file.lastModified()
    }

    /**
     * Re-reads the file if something else has written it since. A stat, in the
     * common case where nothing has.
     */
    fun reloadIfChanged() {
        if (file.lastModified() != loadedAt) load()
    }

    fun all(): List<String> = words

    /**
     * Adds [word], returning whether it was new. Does not write anything —
     * [persist] does that, on whatever thread the caller likes.
     */
    fun add(word: String): Boolean {
        if (words.contains(word)) return false
        words = words + word
        return true
    }

    /** Removes [word], returning whether it was there. Also does not write. */
    fun remove(word: String): Boolean {
        if (!words.contains(word)) return false
        words = words - word
        return true
    }

    fun persist() {
        file.parentFile?.mkdirs()
        file.writeText(words.joinToString(separator = "\n", postfix = "\n"))
        // Our own write is not a change to notice later.
        loadedAt = file.lastModified()
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

    companion object {
        /**
         * Where the store lives inside the app's files directory. Shared by the
         * keyboard, which writes it, and the launcher screen, which edits it.
         */
        const val FILE_NAME = "personal-words.txt"

        /** `File.lastModified` returns 0 for a file that does not exist. */
        private const val NEVER = -1L
    }
}
