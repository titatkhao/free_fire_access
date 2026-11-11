package dev.ffaccess
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*

class MainActivity : Activity() {
    private val reqCapture = 42

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 48, 48, 48) }
        l.addView(TextView(this).apply {
            text = "Free Fire Access\n\n1. Open Accessibility settings and turn on Free Fire Access.\n2. Optional: start fast capture (checks the screen every 50 ms instead of about 3 times a second).\n3. Open Free Fire.\n\nMenus: drag or swipe left/right to move, double tap to activate, three-finger tap to go back. Tap anywhere to stop speech."
            textSize = 18f
        })
        l.addView(Button(this).apply {
            text = "Open Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        l.addView(Button(this).apply {
            text = "Start fast capture"
            setOnClickListener {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
            }
        })
        l.addView(Button(this).apply {
            text = "Stop fast capture"
            setOnClickListener { stopService(Intent(this@MainActivity, CaptureService::class.java)) }
        })
        setContentView(l)
    }

    @Deprecated("kept simple")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture && resultCode == RESULT_OK && data != null) {
            startForegroundService(Intent(this, CaptureService::class.java)
                .putExtra(CaptureService.EXTRA_CODE, resultCode).putExtra(CaptureService.EXTRA_DATA, data))
        }
    }
}
