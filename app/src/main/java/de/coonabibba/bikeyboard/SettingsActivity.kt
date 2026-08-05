package de.coonabibba.bikeyboard

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Keyboard settings, reached from the launcher screen and from the system's own
 * keyboard settings (declared in res/xml/method.xml).
 *
 * Everything here is a number that could not be settled from a laptop. The
 * suggestion size was guessed wrong twice in opposite directions; the timings
 * are a matter of how fast a particular thumb moves; and the correction
 * threshold is the one D3 says outright must be tunable, because it decides how
 * often the keyboard is allowed to be wrong on its own initiative.
 */
class SettingsActivity : AppCompatActivity() {

    private val pad by lazy { dp(16) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        content.addView(
            TextView(this).apply {
                setText(R.string.settings_title)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            },
        )

        addTextSize(content)
        addTimings(content)
        addCorrection(content)
        addHaptics(content)

        val scroll = ScrollView(this).apply {
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        // Edge-to-edge is mandatory at targetSdk 35, so the window no longer
        // insets itself and this would otherwise begin under the status bar.
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        setContentView(scroll)
    }

    /**
     * The size, with a preview at that size. The point of the preview is that
     * the number is chosen by looking rather than by reading.
     */
    private fun addTextSize(into: LinearLayout) {
        val label = TextView(this).apply { setPadding(0, dp(16), 0, dp(4)) }
        val preview = TextView(this).apply {
            setText(R.string.settings_text_size_preview)
            setPadding(0, dp(8), 0, dp(8))
        }
        val show = { sp: Int ->
            label.text = getString(R.string.settings_text_size, sp)
            preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
        }

        into.addView(label)
        into.addView(
            slider(KeyboardPrefs.MIN_TEXT_SP, KeyboardPrefs.MAX_TEXT_SP, KeyboardPrefs.suggestionTextSp(this)) {
                show(it)
                KeyboardPrefs.putInt(this, KeyboardPrefs.SUGGESTION_TEXT_SP, it)
            },
        )
        into.addView(preview)
        show(KeyboardPrefs.suggestionTextSp(this))
    }

    private fun addTimings(into: LinearLayout) {
        into.addView(section(R.string.settings_section_timing))
        KeyboardPrefs.TIMINGS.forEach { timing ->
            val label = TextView(this).apply { setPadding(0, dp(12), 0, dp(4)) }
            val show = { value: Int ->
                label.text = getString(R.string.setting_milliseconds, getString(timing.label), value)
            }
            into.addView(label)
            into.addView(
                slider(timing.min, timing.max, KeyboardPrefs.timing(this, timing).toInt()) {
                    show(it)
                    KeyboardPrefs.putInt(this, timing.key, it)
                },
            )
            show(KeyboardPrefs.timing(this, timing).toInt())
        }
    }

    private fun addCorrection(into: LinearLayout) {
        into.addView(section(R.string.settings_section_correction))

        val confidenceLabel = TextView(this).apply { setPadding(0, dp(12), 0, dp(4)) }
        val confidenceSlider = slider(
            KeyboardPrefs.MIN_CONFIDENCE,
            KeyboardPrefs.MAX_CONFIDENCE,
            (KeyboardPrefs.autoCorrectConfidence(this) * 100).toInt(),
        ) {
            confidenceLabel.text = getString(R.string.setting_confidence, it)
            KeyboardPrefs.putInt(this, KeyboardPrefs.AUTO_CORRECT_CONFIDENCE, it)
        }
        confidenceLabel.text =
            getString(R.string.setting_confidence, (KeyboardPrefs.autoCorrectConfidence(this) * 100).toInt())

        val explanation = TextView(this).apply {
            setText(R.string.setting_auto_correct_off)
            alpha = 0.7f
        }

        fun showEnabled(enabled: Boolean) {
            confidenceLabel.isEnabled = enabled
            confidenceSlider.isEnabled = enabled
            confidenceLabel.alpha = if (enabled) 1f else 0.5f
            explanation.visibility = if (enabled) TextView.GONE else TextView.VISIBLE
        }

        val toggle = SwitchCompat(this).apply {
            setText(R.string.setting_auto_correct)
            isChecked = KeyboardPrefs.autoCorrect(this@SettingsActivity)
            setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                KeyboardPrefs.putBoolean(this@SettingsActivity, KeyboardPrefs.AUTO_CORRECT, checked)
                showEnabled(checked)
            }
        }

        into.addView(toggle)
        into.addView(explanation)
        into.addView(confidenceLabel)
        into.addView(confidenceSlider)
        showEnabled(KeyboardPrefs.autoCorrect(this))
    }

    /**
     * Three states rather than a switch: off, and two strengths, because "on"
     * means different things on different phones and the strong setting is
     * what makes a correction distinguishable from a keypress by feel alone.
     */
    private fun addHaptics(into: LinearLayout) {
        into.addView(section(R.string.settings_section_haptics))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val buttons = mutableListOf<Pair<KeyboardPrefs.HapticLevel, Button>>()

        fun showSelected(level: KeyboardPrefs.HapticLevel) {
            buttons.forEach { (its, button) -> button.alpha = if (its == level) 1f else 0.5f }
        }

        listOf(
            KeyboardPrefs.HapticLevel.OFF to R.string.setting_haptics_off,
            KeyboardPrefs.HapticLevel.LIGHT to R.string.setting_haptics_light,
            KeyboardPrefs.HapticLevel.STRONG to R.string.setting_haptics_strong,
        ).forEach { (level, label) ->
            val button = Button(this).apply {
                setText(label)
                setOnClickListener {
                    KeyboardPrefs.setHaptics(this@SettingsActivity, level)
                    showSelected(level)
                    // Feel it now rather than by going back to the keyboard.
                    Haptics(this@SettingsActivity, level).keyPress(this)
                }
            }
            buttons += level to button
            row.addView(
                button,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }

        into.addView(row)
        showSelected(KeyboardPrefs.haptics(this))
    }

    private fun section(title: Int) = TextView(this).apply {
        setText(title)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setPadding(0, dp(28), 0, dp(4))
    }

    private fun slider(min: Int, max: Int, value: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max - min
        progress = value.coerceIn(min, max) - min
        setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    onChange(min + progress)
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            },
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
