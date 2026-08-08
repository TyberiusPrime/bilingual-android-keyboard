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
 * one press on the personal key at the moment the word is typed (D40).
 *
 * It is also the only thing the user can be *wrong* about, so the launcher
 * screen lists what is in here and can take words back out. A store that only
 * grows would eventually be full of half-typed mistakes with no way to say so.
 *
 * Some entries are additionally marked **quick**, which puts them in the menu
 * the personal key opens (D40). That is for the handful of strings worth
 * inserting whole rather than completing towards — an email address, a
 * postcode, a name nobody spells right — and it is a property of an entry
 * rather than a separate list, so a word cannot be quick without being known.
 *
 * Stored one entry per line in the app's own files directory: ordinary
 * credential-encrypted storage, which D18 makes available by giving up
 * pre-unlock availability. A quick entry carries a tab and a marker after the
 * word, so a file written by an older build reads back unchanged.
 *
 * **Read from the typing thread, written from a disk thread, and edited from
 * another activity entirely.** The entries are therefore replaced wholesale
 * rather than mutated, so a reader always sees one complete version or another
 * — and they are held in *one* list rather than a list plus a set of tags,
 * because two fields cannot be swapped together and a reader landing between
 * the two writes would see a word tagged quick that it does not have.
 * [reloadIfChanged] exists because the keyboard service and the review screen
 * each hold their own copy of a file the other one may have rewritten.
 */
class PersonalStore(private val file: File) {

    /** One remembered word, and whether it is on the quick menu (D40). */
    data class Entry(val word: String, val quick: Boolean = false)

    @Volatile
    private var entries: List<Entry> = emptyList()

    /** The file's modification time when [entries] was read, to notice edits elsewhere. */
    @Volatile
    private var loadedAt = NEVER

    /** Reads the file, if it exists. Cheap: this is one person's vocabulary. */
    fun load() {
        entries = if (file.exists()) {
            file.readLines()
                .mapNotNull(::parse)
                .distinctBy { it.word }
        } else {
            emptyList()
        }
        loadedAt = file.lastModified()
    }

    /**
     * One stored line. A bare word is an ordinary entry, which is what every
     * file written before D40 contains.
     */
    private fun parse(line: String): Entry? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val tab = trimmed.indexOf('\t')
        if (tab < 0) return Entry(trimmed)
        val word = trimmed.substring(0, tab).trim()
        if (word.isEmpty()) return null
        return Entry(word, quick = trimmed.substring(tab + 1).trim() == QUICK_MARKER)
    }

    /**
     * Re-reads the file if something else has written it since. A stat, in the
     * common case where nothing has.
     */
    fun reloadIfChanged() {
        if (file.lastModified() != loadedAt) load()
    }

    fun all(): List<String> = entries.map { it.word }

    /** Every entry, for the screen that edits them. */
    fun entries(): List<Entry> = entries

    /**
     * The words on the quick menu, alphabetically.
     *
     * Folded first and then by the word itself, which is the order the launcher
     * screen already lists everything in — so a word is in the same place on
     * both, and `Ärztin` sorts under A where it is looked for rather than after
     * Z where its code point puts it.
     */
    fun quick(): List<String> = entries
        .filter { it.quick }
        .map { it.word }
        .sortedWith(compareBy({ Folding.fold(it) }, { it }))

    fun isQuick(word: String): Boolean = entries.any { it.word == word && it.quick }

    /**
     * Marks [word] as quick or not, returning whether anything changed. A word
     * the store does not hold cannot be quick.
     */
    fun setQuick(word: String, quick: Boolean): Boolean {
        val current = entries.firstOrNull { it.word == word } ?: return false
        if (current.quick == quick) return false
        entries = entries.map { if (it.word == word) it.copy(quick = quick) else it }
        return true
    }

    /**
     * Adds [word], returning whether it was new. Does not write anything —
     * [persist] does that, on whatever thread the caller likes.
     */
    fun add(word: String): Boolean {
        if (entries.any { it.word == word }) return false
        entries = entries + Entry(word)
        return true
    }

    /** Removes [word], returning whether it was there. Also does not write. */
    fun remove(word: String): Boolean {
        val without = entries.filterNot { it.word == word }
        if (without.size == entries.size) return false
        entries = without
        return true
    }

    fun persist() {
        file.parentFile?.mkdirs()
        file.writeText(
            entries.joinToString(separator = "\n", postfix = "\n") { entry ->
                if (entry.quick) "${entry.word}\t$QUICK_MARKER" else entry.word
            },
        )
        // Our own write is not a change to notice later.
        loadedAt = file.lastModified()
    }

    /** Whether the store holds [word], ignoring case and accents — see [Lexicon.knows]. */
    fun knows(word: CharSequence): Boolean {
        val folded = Folding.fold(word)
        return folded.isNotEmpty() && entries.any { Folding.fold(it.word) == folded }
    }

    /** Whether the store has [word] spelled exactly that way — see [Lexicon.knowsExactly]. */
    fun knowsExactly(word: CharSequence): Boolean {
        val lowered = word.toString().lowercase()
        return entries.any { it.word.lowercase() == lowered }
    }

    /** Every stored word starting with [foldedPrefix], ignoring case and accents. */
    fun completions(foldedPrefix: String): List<String> {
        if (foldedPrefix.isEmpty()) return emptyList()
        return entries.mapNotNull { entry ->
            entry.word.takeIf { Folding.fold(it).startsWith(foldedPrefix) }
        }
    }

    companion object {
        /**
         * Where the store lives inside the app's files directory. Shared by the
         * keyboard, which writes it, and the launcher screen, which edits it.
         */
        const val FILE_NAME = "personal-words.txt"

        /** What marks an entry as belonging on the quick menu, after a tab. */
        const val QUICK_MARKER = "quick"

        /** `File.lastModified` returns 0 for a file that does not exist. */
        private const val NEVER = -1L
    }
}
