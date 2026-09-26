package app.pwhs.blockads

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Debug-only harness: a native screen with a tappable "跳过" button.
 * The ad-skip accessibility service should find and tap it within ~100ms
 * of the window opening, flipping the label to "SKIPPED_OK".
 * Not compiled into release builds (src/debug only).
 */
class AdSkipTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (resources.displayMetrics.density * 24).toInt()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val label = TextView(this).apply {
            text = "WAITING_FOR_SKIP"
            textSize = 28f
            setPadding(pad, pad, pad, pad)
        }
        val btn = Button(this).apply {
            text = "跳过"
            textSize = 28f
            contentDescription = "跳过"
            setOnClickListener {
                label.text = "SKIPPED_OK"
                (it as Button).text = "SKIPPED_OK"
            }
        }
        layout.addView(label)
        layout.addView(btn)
        setContentView(layout)
        window.decorView.contentDescription = null
    }
}
