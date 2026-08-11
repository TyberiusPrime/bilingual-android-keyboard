package de.coonabibba.bikeyboard

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import java.io.File

/**
 * Launcher screen. An IME cannot enable itself: the user has to tick it in
 * system settings and then pick it from the input-method switcher. All this
 * screen can do is send them to the right places — and show what the keyboard
 * has learned, which is the one thing about it the user can be wrong about.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var learnedHeading: TextView
    private lateinit var learnedList: LinearLayout

    /** The field that adds an entry by hand, its quick tick, and what it says back (D51). */
    private lateinit var newWord: EditText
    private lateinit var newWordQuick: CheckBox
    private lateinit var addMessage: TextView
    private val store by lazy { PersonalStore(File(filesDir, PersonalStore.FILE_NAME)) }

    private val pad by lazy { dp(16) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        content.addView(
            TextView(this).apply {
                setText(R.string.app_name)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                typeface = Typeface.DEFAULT_BOLD
            },
        )

        status = TextView(this).apply { setPadding(0, dp(8), 0, dp(16)) }
        content.addView(status)

        content.addView(
            button(R.string.action_enable) {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            },
        )
        content.addView(
            button(R.string.action_select) {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showInputMethodPicker()
            },
        )
        content.addView(
            button(R.string.settings_title) {
                startActivity(Intent(this@SetupActivity, SettingsActivity::class.java))
            },
        )

        addAddWordField(content)

        learnedHeading = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(24), 0, dp(8))
        }
        content.addView(learnedHeading)

        learnedList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(learnedList)

        val scroll = ScrollView(this).apply {
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        // targetSdk 35 makes edge-to-edge mandatory for activities too, so
        // without this the first thing on the screen is drawn underneath the
        // status bar and the gesture pill sits on top of the last one.
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        status.text = getString(
            if (isEnabled()) R.string.status_enabled else R.string.status_not_enabled,
        )
        // Whatever the field last said is about an entry that is now simply in
        // the list below, which says it better.
        addMessage.visibility = View.GONE
        showLearnedWords()
    }

    /**
     * The other way into the personal store (D51).
     *
     * The personal key remembers **whatever is between the spaces** (D40),
     * which is the right rule for something pressed mid-word and means a phrase
     * cannot be asked for that way at all — `Anna Maria`, `mit freundlichen
     * Grüßen`, a street with a space in it. Typed here it goes in as it stands.
     *
     * The quick tick sits beside the field rather than only on the row that
     * appears afterwards, because putting the entry on the + key's menu is the
     * usual reason for coming here and the list below is alphabetical: finding
     * the thing you just added, among a hundred others, to tick a box you were
     * looking at a second ago is not a step worth keeping.
     */
    private fun addAddWordField(content: LinearLayout) {
        content.addView(
            TextView(this).apply {
                setText(R.string.add_word_title)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(24), 0, dp(4))
            },
        )
        content.addView(
            TextView(this).apply {
                setText(R.string.add_word_explanation)
                alpha = 0.7f
                setPadding(0, 0, 0, dp(8))
            },
        )

        newWord = EditText(this).apply {
            setHint(R.string.add_word_hint)
            // Free text, and the store is not a form: nothing the phone has
            // filed under a person's name belongs in this box by default.
            inputType = InputType.TYPE_CLASS_TEXT
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            // One line, so the only line breaks that can reach the store are
            // pasted ones — which PersonalStore.clean turns into spaces.
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) addTypedWord()
                // Not consumed: the keyboard closing after the entry is added
                // is the right end to the gesture.
                false
            }
        }
        newWordQuick = CheckBox(this).apply { setText(R.string.action_quick) }

        content.addView(newWord)
        content.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    newWordQuick,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(
                    Button(context).apply {
                        setText(R.string.action_add)
                        setOnClickListener { addTypedWord() }
                    },
                )
            },
        )

        addMessage = TextView(this).apply {
            alpha = 0.7f
            visibility = View.GONE
        }
        content.addView(addMessage)
    }

    /**
     * Takes what is in the field, if there is anything the store can hold.
     *
     * A word already known is not an error and not a no-op either: the tick is
     * still applied, since "I want this on the menu" is the other half of what
     * the field is for and the entry being there already should not swallow it.
     * An *un*ticked box is not the same request in reverse — it takes nothing
     * off the menu, because the row in the list below is where that is said.
     */
    private fun addTypedWord() {
        val word = PersonalStore.clean(newWord.text.toString())
        if (word.isEmpty()) return

        store.load()
        val added = store.add(word)
        val ticked = newWordQuick.isChecked && store.setQuick(word, true)
        if (added || ticked) store.persist()

        newWord.text.clear()
        newWordQuick.isChecked = false
        addMessage.text = getString(
            if (added) R.string.add_word_added else R.string.add_word_known,
            word,
        )
        addMessage.visibility = View.VISIBLE
        showLearnedWords()
    }

    /**
     * The personal store, with a way out of it for every word.
     *
     * Re-read on every resume rather than kept: the keyboard service is a
     * different lifetime writing the same file, and it may well have added a
     * word since this screen was last looked at.
     */
    private fun showLearnedWords() {
        store.load()
        // Alphabetical, ignoring case and accents, so a word is where the eye
        // looks for it. The store itself keeps the order words were added in,
        // which is the order they will be least easily found in.
        val entries = store.entries()
            .sortedWith(compareBy({ Folding.fold(it.word) }, { it.word }))
        learnedHeading.text = getString(R.string.learned_title, entries.size)
        learnedList.removeAllViews()

        if (entries.isEmpty()) {
            learnedList.addView(
                TextView(this).apply {
                    setText(R.string.learned_empty)
                    alpha = 0.7f
                },
            )
            return
        }

        // What the quick menu is for, said once above the list rather than on
        // every row: the checkbox is otherwise a mystery with no label.
        learnedList.addView(
            TextView(this).apply {
                setText(R.string.quick_explanation)
                alpha = 0.7f
                setPadding(0, 0, 0, dp(8))
            },
        )

        entries.forEach { entry ->
            learnedList.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        TextView(context).apply {
                            text = entry.word
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(
                        CheckBox(context).apply {
                            setText(R.string.action_quick)
                            isChecked = entry.quick
                            contentDescription =
                                getString(R.string.action_quick_word, entry.word)
                            setOnCheckedChangeListener { _, checked ->
                                setQuick(entry.word, checked)
                            }
                        },
                    )
                    addView(
                        Button(context).apply {
                            setText(R.string.action_forget)
                            contentDescription = getString(R.string.action_forget_word, entry.word)
                            setOnClickListener { forget(entry.word) }
                        },
                    )
                },
            )
        }
    }

    private fun forget(word: String) {
        if (!store.remove(word)) return
        store.persist()
        showLearnedWords()
    }

    /**
     * Puts a word on the keyboard's quick menu, or takes it off (D40).
     *
     * The list is deliberately not rebuilt afterwards: this runs from a
     * checkbox that has already drawn itself in the new state, and redrawing
     * every row underneath the finger that just tapped one is how a list loses
     * your place.
     */
    private fun setQuick(word: String, quick: Boolean) {
        if (!store.setQuick(word, quick)) return
        store.persist()
    }

    private fun button(textId: Int, onClick: () -> Unit) = Button(this).apply {
        setText(textId)
        setOnClickListener { onClick() }
    }

    private fun isEnabled(): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
