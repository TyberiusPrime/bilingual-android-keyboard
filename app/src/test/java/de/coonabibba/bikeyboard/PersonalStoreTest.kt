package de.coonabibba.bikeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PersonalStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store(): Pair<PersonalStore, File> {
        val file = File(folder.newFolder(), "personal-words.txt")
        return PersonalStore(file) to file
    }

    @Test
    fun `a word added is a word kept`() {
        val (store, _) = store()
        assertTrue(store.add("Coonabibba"))
        assertFalse("adding it twice changes nothing", store.add("Coonabibba"))
        assertTrue(store.knows("Coonabibba"))
    }

    @Test
    fun `words survive a restart`() {
        val (store, file) = store()
        store.add("Coonabibba")
        store.add("Fairphone")
        store.persist()

        val reopened = PersonalStore(file)
        reopened.load()
        assertEquals(listOf("Coonabibba", "Fairphone"), reopened.all())
    }

    @Test
    fun `a store with no file yet is simply empty`() {
        val (store, _) = store()
        store.load()
        assertEquals(emptyList<String>(), store.all())
        assertFalse(store.knows("anything"))
    }

    @Test
    fun `knowing a word ignores case and accents`() {
        val (store, _) = store()
        store.add("Straße")
        assertTrue(store.knows("straße"))
        assertTrue(store.knows("strasse"))
        assertFalse(store.knows("strass"))
    }

    @Test
    fun `completions match the folded prefix`() {
        val (store, _) = store()
        store.add("Coonabibba")
        store.add("Übermorgen")
        assertEquals(listOf("Coonabibba"), store.completions("coo"))
        assertEquals(listOf("Übermorgen"), store.completions("uber"))
        assertEquals(emptyList<String>(), store.completions(""))
    }

    /** A store that only grows fills up with half-typed mistakes. */
    @Test
    fun `a word can be taken back out`() {
        val (store, file) = store()
        store.add("Coonabibba")
        store.add("Fairphone")
        assertTrue(store.remove("Coonabibba"))
        assertFalse("removing it twice changes nothing", store.remove("Coonabibba"))
        assertFalse(store.knows("Coonabibba"))
        store.persist()

        val reopened = PersonalStore(file)
        reopened.load()
        assertEquals(listOf("Fairphone"), reopened.all())
    }

    /**
     * The keyboard service and the launcher screen each hold a copy of the same
     * file, and the screen can rewrite it while the keyboard is alive.
     */
    @Test
    fun `an edit made elsewhere is picked up`() {
        val (store, file) = store()
        store.add("Coonabibba")
        store.persist()

        val keyboardsCopy = PersonalStore(file)
        keyboardsCopy.load()
        assertTrue(keyboardsCopy.knows("Coonabibba"))

        // Something else rewrites the file. The timestamp has one-second
        // granularity on some filesystems, so make the change unmistakable.
        file.writeText("Fairphone\n")
        file.setLastModified(file.lastModified() + 2_000)

        keyboardsCopy.reloadIfChanged()
        assertFalse(keyboardsCopy.knows("Coonabibba"))
        assertTrue(keyboardsCopy.knows("Fairphone"))
    }

    @Test
    fun `nothing is re-read while the file is untouched`() {
        val (store, file) = store()
        store.add("Coonabibba")
        store.persist()

        // A word added but not yet written must survive a reload check, or the
        // keyboard would forget words between the tap and the disk write.
        store.add("Fairphone")
        store.reloadIfChanged()
        assertTrue(store.knows("Fairphone"))
        assertTrue(file.exists())
    }

    @Test
    fun `blank lines in the file are ignored`() {
        val (_, file) = store()
        file.parentFile?.mkdirs()
        file.writeText("Coonabibba\n\n  \nFairphone\n")
        val store = PersonalStore(file)
        store.load()
        assertEquals(listOf("Coonabibba", "Fairphone"), store.all())
    }

    // -- the quick menu (D40) -------------------------------------------------

    /**
     * A file written before D40 is one bare word per line, and must keep
     * meaning exactly what it meant. This is somebody's own vocabulary, built
     * up a word at a time; losing it to a format change would be unforgivable
     * for a feature nobody asked to pay for.
     */
    @Test
    fun `a file from before the quick menu still reads`() {
        val (store, file) = store()
        file.parentFile?.mkdirs()
        file.writeText("Coonabibba\nFairphone\n")
        store.load()
        assertEquals(listOf("Coonabibba", "Fairphone"), store.all())
        assertTrue("nothing was quick before there was a quick menu", store.quick().isEmpty())
    }

    @Test
    fun `a tagged word survives a restart and an untagged one stays untagged`() {
        val (store, file) = store()
        store.add("john@coonabibba.de")
        store.add("Fairphone")
        assertTrue(store.setQuick("john@coonabibba.de", true))
        store.persist()

        val reopened = PersonalStore(file)
        reopened.load()
        assertEquals(listOf("john@coonabibba.de"), reopened.quick())
        assertTrue(reopened.isQuick("john@coonabibba.de"))
        assertFalse(reopened.isQuick("Fairphone"))
        // And it is still an ordinary word for every other purpose.
        assertEquals(listOf("john@coonabibba.de", "Fairphone"), reopened.all())
    }

    /** A word the store does not hold cannot be on the menu. */
    @Test
    fun `tagging an unknown word does nothing`() {
        val (store, _) = store()
        assertFalse(store.setQuick("nobody", true))
        assertTrue(store.quick().isEmpty())
    }

    @Test
    fun `tagging is idempotent and reversible`() {
        val (store, _) = store()
        store.add("Fairphone")
        assertTrue(store.setQuick("Fairphone", true))
        assertFalse("already quick", store.setQuick("Fairphone", true))
        assertTrue(store.setQuick("Fairphone", false))
        assertTrue(store.quick().isEmpty())
    }

    /** Forgetting a word takes it off the menu with it. */
    @Test
    fun `removing a tagged word removes it from the menu`() {
        val (store, _) = store()
        store.add("Fairphone")
        store.setQuick("Fairphone", true)
        assertTrue(store.remove("Fairphone"))
        assertTrue(store.quick().isEmpty())
        assertFalse(store.isQuick("Fairphone"))
    }

    /** The menu is alphabetical, whatever order the words went in. */
    @Test
    fun `the menu is sorted`() {
        val (store, _) = store()
        listOf("zebra", "apple", "mango").forEach {
            store.add(it)
            store.setQuick(it, true)
        }
        assertEquals(listOf("apple", "mango", "zebra"), store.quick())
    }

    /**
     * Sorted the way the launcher screen sorts, so a word is in the same place
     * on both — and so an accented word is where the eye looks for it rather
     * than after `z`, which is where its code point would put it.
     */
    @Test
    fun `the menu sorts accents and case the way the list does`() {
        val (store, _) = store()
        listOf("Zoo", "Ärztin", "apfel", "Öl").forEach {
            store.add(it)
            store.setQuick(it, true)
        }
        assertEquals(listOf("apfel", "Ärztin", "Öl", "Zoo"), store.quick())
    }

    /** Nothing is dropped for want of room on screen — the menu scrolls. */
    @Test
    fun `every tagged word is on the menu however many there are`() {
        val (store, _) = store()
        val many = (1..30).map { "word%02d".format(it) }
        many.forEach {
            store.add(it)
            store.setQuick(it, true)
        }
        assertEquals(many, store.quick())
    }

    // -- entries typed in by hand (D51) ---------------------------------------

    @Test
    fun `a phrase keeps its spaces, through the file and back`() {
        val (store, file) = store()
        val phrase = PersonalStore.clean("  mit freundlichen Grüßen ")
        assertEquals("mit freundlichen Grüßen", phrase)
        assertTrue(store.add(phrase))
        assertTrue(store.setQuick(phrase, true))
        store.persist()

        val reopened = PersonalStore(file)
        reopened.load()
        assertEquals(listOf(phrase), reopened.all())
        assertEquals(listOf(phrase), reopened.quick())
        assertTrue(reopened.knows(phrase))
    }

    /**
     * An entry is a line and the quick marker is behind a tab, so neither can
     * appear inside one — a pasted line break would otherwise come back as two
     * entries, and a pasted tab would name the rest of the phrase as a marker.
     */
    @Test
    fun `what the file cannot hold becomes a space`() {
        assertEquals("Anna Maria", PersonalStore.clean("Anna\tMaria"))
        assertEquals("Anna Maria", PersonalStore.clean("Anna\nMaria"))
        assertEquals("Anna Maria", PersonalStore.clean("Anna \r\n Maria"))
        assertEquals("Anna Maria", PersonalStore.clean("Anna   Maria"))
        assertEquals("", PersonalStore.clean("   \t\n "))
        assertEquals("", PersonalStore.clean(""))
    }

    /** And a phrase that has been through it survives the round trip whole. */
    @Test
    fun `a pasted line break does not split an entry in two`() {
        val (store, file) = store()
        store.add(PersonalStore.clean("Anna\nMaria"))
        store.persist()
        val reopened = PersonalStore(file)
        reopened.load()
        assertEquals(listOf("Anna Maria"), reopened.all())
    }

    /**
     * The strip completes towards a phrase from its first word, which is what
     * makes a stored phrase worth having at all.
     */
    @Test
    fun `a phrase completes from its opening`() {
        val (store, _) = store()
        store.add("mit freundlichen Grüßen")
        assertEquals(listOf("mit freundlichen Grüßen"), store.completions("mit"))
        assertEquals(listOf("mit freundlichen Grüßen"), store.completions("mit fre"))
        assertTrue(store.completions("freundlichen").isEmpty())
    }

    /** A word containing the marker text is still just a word. */
    @Test
    fun `a word that looks like a marker is not one`() {
        val (store, file) = store()
        store.add(PersonalStore.QUICK_MARKER)
        store.persist()
        val reopened = PersonalStore(file)
        reopened.load()
        assertEquals(listOf(PersonalStore.QUICK_MARKER), reopened.all())
        assertTrue(reopened.quick().isEmpty())
    }
}
