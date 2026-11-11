package dev.ffaccess

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager

/**
 * Optional fast capture. Android's accessibility screenshot is limited
 * to about 3 per second; a screen-capture session delivers frames
 * continuously, so the app can look at the screen every 50 ms.
 *
 * Frames only live in memory: each one is read and closed immediately,
 * nothing is ever saved.
 */
class CaptureService : Service() {

    companion object {
        @Volatile
        var instance: CaptureService? = null

        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"

        private const val CHANNEL = "ffaccess_capture"
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        val nm = getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "Fast capture",
                NotificationManager.IMPORTANCE_LOW
            )
        )

        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Free Fire Access: fast capture is on")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()

        startForeground(
            1,
            n,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0

        val data: Intent? =
            if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                intent?.getParcelableExtra(EXTRA_DATA)
            }

        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val mpm =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

            val mp: MediaProjection? =
                mpm.getMediaProjection(code, data)

            // FIX: Handle a null MediaProjection safely.
            if (mp == null) {
                stopSelf()
                return START_NOT_STICKY
            }

            mp.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopSelf()
                    }
                },
                Handler(Looper.getMainLooper())
            )

            val b =
                (getSystemService(WINDOW_SERVICE) as WindowManager)
                    .maximumWindowMetrics.bounds

            val w = b.width()
            val h = b.height()

            val r = ImageReader.newInstance(
                w,
                h,
                PixelFormat.RGBA_8888,
                2
            )

            display = mp.createVirtualDisplay(
                "ffaccess",
                w,
                h,
                resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface,
                null,
                null
            )

            projection = mp
            reader = r
            instance = this

        } catch (e: Exception) {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    /**
     * Newest frame, or null.
     * The caller must close() it right after use.
     */
    fun latestImage(): Image? =
        try {
            reader?.acquireLatestImage()
        } catch (e: Exception) {
            null
        }

    override fun onDestroy() {
        instance = null

        runCatching {
            display?.release()
        }

        runCatching {
            reader?.close()
        }

        runCatching {
            projection?.stop()
        }

        super.onDestroy()
    }
}   }
}
