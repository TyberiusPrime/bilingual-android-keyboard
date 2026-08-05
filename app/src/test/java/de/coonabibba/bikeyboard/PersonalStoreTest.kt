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
}
