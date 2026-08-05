package de.coonabibba.bikeyboard

import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Keyboard settings, reached from the launcher screen and from the system's own
 * keyboard settings (declared in res/xml/method.xml).
 *
 * One setting so far: how large the suggestions are. It is here rather than
 * hard-coded because two guesses at the right size were wrong in opposite
 * directions — fine print, then shouting — and it depends on eyesight, screen
 * and system font scale rather than on anything decidable here.
 *
 * The preview under the slider is the point of it: the size is chosen by
 * looking, not by reading a number.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)

        val heading = TextView(this).apply {
            setText(R.string.settings_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        }

        val label = TextView(this).apply { setPadding(0, dp(24), 0, dp(4)) }
        val preview = TextView(this).apply {
            setText(R.string.settings_text_size_preview)
            setPadding(0, dp(8), 0, dp(8))
        }

        fun show(sp: Int) {
            label.text = getString(R.string.settings_text_size, sp)
            preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
        }

        val slider = SeekBar(this).apply {
            max = KeyboardPrefs.MAX_TEXT_SP - KeyboardPrefs.MIN_TEXT_SP
            progress = KeyboardPrefs.suggestionTextSp(this@SettingsActivity) -
                KeyboardPrefs.MIN_TEXT_SP
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                        val sp = KeyboardPrefs.MIN_TEXT_SP + value
                        show(sp)
                        // Written as it moves. The keyboard picks it up the next
                        // time a field is focused, which is the next time it is
                        // seen anyway.
                        KeyboardPrefs.setSuggestionTextSp(this@SettingsActivity, sp)
                    }

                    override fun onStartTrackingTouch(bar: SeekBar) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                },
            )
        }
        show(KeyboardPrefs.suggestionTextSp(this))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(heading)
            addView(label)
            addView(slider)
            addView(preview)
        }

        // Edge-to-edge is mandatory at targetSdk 35, so the window no longer
        // insets itself and this would otherwise begin under the status bar.
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = pad + bars.top, bottom = pad + bars.bottom)
            insets
        }

        setContentView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
