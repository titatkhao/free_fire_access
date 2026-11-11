package dev.ffaccess
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.*

class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 48, 48, 48) }
        l.addView(TextView(this).apply {
            text = "Free Fire Access\n\n1. Open Accessibility settings and turn on Free Fire Access.\n2. Open Free Fire.\n\nMenus: drag or swipe left/right to move, double tap to activate, three-finger tap to go back."
            textSize = 18f
        })
        l.addView(Button(this).apply {
            text = "Open Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        setContentView(l)
    }
}
