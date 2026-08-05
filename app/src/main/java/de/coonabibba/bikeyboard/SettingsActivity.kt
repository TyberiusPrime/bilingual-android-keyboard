package de.coonabibba.bikeyboard

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Reached from the system keyboard settings (declared in res/xml/method.xml).
 * Empty until the design document says what belongs in it — the learned words,
 * the only thing there is to configure so far, sit on the launcher screen where
 * they are seen without going looking for them.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            setText(R.string.settings_placeholder)
            setPadding(pad, pad, pad, pad)
        }
        // Edge-to-edge is mandatory at targetSdk 35, so the window no longer
        // insets itself and this would otherwise begin under the status bar.
        ViewCompat.setOnApplyWindowInsetsListener(text) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = pad + bars.top, bottom = pad + bars.bottom)
            insets
        }
        setContentView(text)
    }
}
