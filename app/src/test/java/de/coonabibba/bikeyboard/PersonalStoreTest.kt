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
