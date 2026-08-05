package de.coonabibba.bikeyboard

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
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
        val words = store.all().sortedWith(compareBy({ Folding.fold(it) }, { it }))
        learnedHeading.text = getString(R.string.learned_title, words.size)
        learnedList.removeAllViews()

        if (words.isEmpty()) {
            learnedList.addView(
                TextView(this).apply {
                    setText(R.string.learned_empty)
                    alpha = 0.7f
                },
            )
            return
        }

        words.forEach { word ->
            learnedList.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        TextView(context).apply {
                            text = word
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(
                        Button(context).apply {
                            setText(R.string.action_forget)
                            contentDescription = getString(R.string.action_forget_word, word)
                            setOnClickListener { forget(word) }
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
