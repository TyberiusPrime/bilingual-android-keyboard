package de.coonabibba.bikeyboard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Launcher screen. An IME cannot enable itself: the user has to tick it in
 * system settings and then pick it from the input-method switcher. All this
 * screen can do is send them to the right places.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        status = TextView(this)
        root.addView(status)

        root.addView(
            Button(this).apply {
                setText(R.string.action_enable)
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                }
            },
        )

        root.addView(
            Button(this).apply {
                setText(R.string.action_select)
                setOnClickListener {
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showInputMethodPicker()
                }
            },
        )

        root.addView(
            Button(this).apply {
                setText(R.string.settings_title)
                setOnClickListener {
                    startActivity(Intent(this@SetupActivity, SettingsActivity::class.java))
                }
            },
        )

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        status.text = getString(
            if (isEnabled()) R.string.status_enabled else R.string.status_not_enabled,
        )
    }

    private fun isEnabled(): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }
}
