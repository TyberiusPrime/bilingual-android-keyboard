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
        addFlash(content)
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
     * The five numbers that make up the correction flash, over a preview that
     * replays it on every slider move.
     *
     * The preview is the point. Two rounds of "too subtle" came back from the
     * phone while this was a constant in a source file, which meant each guess
     * cost a rebuild, an install, and a deliberately misspelled word. Here the
     * wave plays as the thumb drags.
     */
    private fun addFlash(into: LinearLayout) {
        into.addView(section(R.string.settings_section_flash))

        val preview = FlashPreviewView(this)
        val replay = {
            preview.shape = FlashShape.fromPrefs(this)
            preview.play()
        }

        KeyboardPrefs.FLASH.forEach { range ->
            val label = TextView(this).apply { setPadding(0, dp(12), 0, dp(4)) }
            val show = { value: Int ->
                label.text = getString(R.string.setting_value, getString(range.label), value)
            }
            into.addView(label)
            into.addView(
                slider(range.min, range.max, KeyboardPrefs.value(this, range)) {
                    show(it)
                    KeyboardPrefs.putInt(this, range.key, it)
                    replay()
                },
            )
            show(KeyboardPrefs.value(this, range))
        }

        into.addView(
            TextView(this).apply {
                setText(R.string.setting_flash_hint)
                alpha = 0.7f
                setPadding(0, dp(12), 0, dp(4))
            },
        )
        into.addView(preview)
        preview.setOnClickListener { preview.play() }
    }

    /**
     * A row of three per event: off, and two strengths.
     *
     * Three states rather than a switch because "on" means something different
     * on every phone. Two rows rather than one because a keypress tick and a
     * correction knock are not the same message and nobody has to want them in
     * the same amount — silence while typing and a firm knock when a word is
     * replaced is a coherent position, and one slider could not say it.
     */
    private fun addHaptics(into: LinearLayout) {
        into.addView(section(R.string.settings_section_haptics))

        KeyboardPrefs.HapticEvent.entries.forEach { event -> addHapticLevels(into, event) }

        if (!Haptics.fromPrefs(this).available) {
            into.addView(
                TextView(this).apply {
                    setText(R.string.setting_haptics_unavailable)
                    alpha = 0.7f
                    setPadding(0, dp(8), 0, 0)
                },
            )
            return
        }
        addHapticRoutes(into)
        addHapticDiagnosis(into)
    }

    /** One labelled row of Off / Light / Strong, for one kind of event. */
    private fun addHapticLevels(into: LinearLayout, event: KeyboardPrefs.HapticEvent) {
        into.addView(
            TextView(this).apply {
                setText(
                    when (event) {
                        KeyboardPrefs.HapticEvent.KEY_PRESS -> R.string.setting_haptics_key_press
                        KeyboardPrefs.HapticEvent.CORRECTION -> R.string.setting_haptics_correction
                    },
                )
                setPadding(0, dp(12), 0, dp(4))
            },
        )

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
                setOnClickListener { view ->
                    KeyboardPrefs.setHaptics(this@SettingsActivity, event, level)
                    showSelected(level)
                    // Feel it now rather than by going back to the keyboard, as
                    // the event it belongs to — so the correction row plays the
                    // double knock rather than a tick — and through the route
                    // that is actually configured, or the button lies about what
                    // the keyboard will do.
                    Haptics.sample(
                        this@SettingsActivity,
                        level,
                        KeyboardPrefs.hapticRoute(this@SettingsActivity),
                    ).fire(KeyboardPrefs.hapticRoute(this@SettingsActivity), view, event)
                }
            }
            buttons += level to button
            row.addView(
                button,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }

        into.addView(row)
        showSelected(KeyboardPrefs.haptics(this, event))
    }

    /**
     * One button per way of asking the phone to buzz, and a setting recording
     * which one worked.
     *
     * This should not need to exist. It does because the phone reports a working
     * motor and produces nothing, and **not one of these calls returns whether
     * the motor moved** — so the only instrument left is a finger. Tap down the
     * list; whichever is felt, choose it. Insistent is the diagnostic: it asks
     * with a usage the system does not scale to nothing, so if only that one is
     * felt, the fault is a system setting rather than this keyboard.
     */
    private fun addHapticRoutes(into: LinearLayout) {
        into.addView(
            TextView(this).apply {
                setText(R.string.setting_haptics_route_hint)
                alpha = 0.7f
                setPadding(0, dp(16), 0, dp(4))
            },
        )

        val buttons = mutableListOf<Pair<KeyboardPrefs.HapticRoute, Button>>()
        fun showSelected(route: KeyboardPrefs.HapticRoute) {
            buttons.forEach { (its, button) -> button.alpha = if (its == route) 1f else 0.5f }
        }

        // Always audible to the finger while hunting for a route that works,
        // even if keypress feedback is set to off — the question here is whether
        // the phone can buzz at all, not how hard it should.
        val level = KeyboardPrefs.HapticLevel.STRONG

        listOf(
            KeyboardPrefs.HapticRoute.AUTO to R.string.setting_haptics_route_auto,
            KeyboardPrefs.HapticRoute.VIEW to R.string.setting_haptics_route_view,
            KeyboardPrefs.HapticRoute.PREDEFINED to R.string.setting_haptics_route_predefined,
            KeyboardPrefs.HapticRoute.PULSE to R.string.setting_haptics_route_pulse,
            KeyboardPrefs.HapticRoute.INSISTENT to R.string.setting_haptics_route_insistent,
        ).forEach { (route, label) ->
            val button = Button(this).apply {
                setText(label)
                setOnClickListener { view ->
                    KeyboardPrefs.setHapticRoute(this@SettingsActivity, route)
                    showSelected(route)
                    // Through the same code path the keyboard uses, or the test
                    // proves nothing about the keyboard.
                    Haptics.sample(this@SettingsActivity, level, route)
                        .fire(route, view, KeyboardPrefs.HapticEvent.KEY_PRESS)
                }
            }
            buttons += route to button
            into.addView(
                button,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        showSelected(KeyboardPrefs.hapticRoute(this))
    }

    /**
     * What the phone says about its own motor.
     *
     * Four separate gates can each turn a correct vibration request into
     * silence, and none of them raises an error. Printing them together usually
     * names the culprit outright.
     */
    private fun addHapticDiagnosis(into: LinearLayout) {
        val haptics = Haptics.fromPrefs(this)

        // The conclusion first, when there is one. A list of readings is data;
        // this is the sentence the reader came for.
        haptics.verdict()?.let { verdict ->
            into.addView(
                TextView(this).apply {
                    text = verdict
                    setPadding(0, dp(16), 0, 0)
                },
            )
        }

        into.addView(
            TextView(this).apply {
                text = haptics.diagnose().joinToString("\n") { (label, value) -> "$label: $value" }
                alpha = 0.7f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(16), 0, 0)
            },
        )
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
