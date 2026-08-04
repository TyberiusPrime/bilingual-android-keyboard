package de.coonabibba.bikeyboard

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Reached from the system keyboard settings (declared in res/xml/method.xml).
 * Empty until the design document says what belongs in it.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        setContentView(
            TextView(this).apply {
                setText(R.string.settings_placeholder)
                setPadding(pad, pad, pad, pad)
            },
        )
    }
}
