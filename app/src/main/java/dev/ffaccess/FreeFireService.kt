package dev.ffaccess
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs
import kotlin.math.hypot

class FreeFireService : AccessibilityService() {
    private lateinit var speaker: Speaker
    private val ocr = Ocr()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var overlay: View
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var edge: View // 1px window that watches touches outside itself (match mode)

    private enum class Mode { MENU, SHOP, MATCH }
    private var mode = Mode.MENU
    private var matchHoldUntil = 0L
    private var generation = 0 // bumped whenever the screen may have changed: older screenshots are discarded

    private var active = false
    private var busy = false
    private var pending = false
    private var items = listOf<Item>()
    private var focus = -1

    // match state
    private var blue = false
    private var calmScans = 0
    private var health: Int? = null
    private var lastHitSpeak = 0L
    private var lastHealthSpeak = 0L
    private val recentKills = HashMap<String, Long>()

    // screen-change / progress state
    private var lastSig: Set<String> = emptySet()
    private var lastScreenSpeak = 0L
    private var lastBucket = -1
    private var lastPctSpeak = 0L
    private var lastEventRefresh = 0L
    private var lastOutsideT = 0L

    // touch state
    private var sx = 0f; private var sy = 0f; private var downT = 0L
    private var maxPtr = 0; private var moved = false
    private var lastTapT = 0L

    private val captureNow = Runnable { capture() }
    private val loop = object : Runnable {
        override fun run() { if (active) { capture(); handler.postDelayed(this, 1000) } }
    }

    override fun onServiceConnected() {
        speaker = Speaker(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlay = View(this)
        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT)
        overlay.setOnTouchListener { _, e -> onTouch(e); true }
        wm.addView(overlay, lp)

        edge = View(this)
        val elp = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.TRANSLUCENT)
        elp.gravity = Gravity.TOP or Gravity.START
        edge.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) onOutsideTouch(e.eventTime); false }
        wm.addView(edge, elp)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val p = e.packageName?.toString() ?: return
        if (p == packageName) return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val now = p in Patterns.PACKAGES
                if (now == active) { if (active) refresh(); return }
                active = now
                if (active) {
                    mode = Mode.MENU; blue = false; health = null; lastSig = emptySet(); lastBucket = -1
                    wipe(); generation++
                    setTouchable(true)
                    speaker.speak("Free Fire accessibility on")
                    handler.removeCallbacks(loop)
                    handler.post(loop)
                } else {
                    handler.removeCallbacks(loop)
                    handler.removeCallbacks(captureNow)
                    generation++
                    wipe()
                    setTouchable(false)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> if (active && p in Patterns.PACKAGES) {
                val t = System.currentTimeMillis()
                if (t - lastEventRefresh > 500) { lastEventRefresh = t; refresh() }
            }
        }
    }

    override fun onInterrupt() { speaker.shutdown() }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { wm.removeView(overlay) }; runCatching { wm.removeView(edge) }
        speaker.shutdown(); super.onDestroy()
    }

    private fun setTouchable(t: Boolean) {
        lp.flags = if (t) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                   else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(overlay, lp) }
    }

    /** Drop everything captured from the screen. */
    private fun wipe() { items = emptyList(); focus = -1 }

    // ---------- screen capture + OCR ----------
    /**
     * The screen changed (tap, window change, content change): the old screenshot and any
     * result still being processed are thrown away, and a fresh screenshot is taken.
     */
    private fun refresh(delayMs: Long = 300) {
        if (!active) return
        generation++
        handler.removeCallbacks(captureNow)
        handler.postDelayed(captureNow, delayMs)
    }

    private fun capture() {
        if (!active) return
        if (busy) { pending = true; return }
        busy = true
        val gen = generation
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                val hb = r.hardwareBuffer
                val bmp = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                hb.close()
                if (bmp == null) { finish(); return }
                // The screenshot lives only in memory (never written to storage) and is freed right after OCR.
                ocr.run(bmp) { list -> bmp.recycle(); finish(); if (gen == generation) onScan(list) }
            }
            override fun onFailure(errorCode: Int) { finish() }
        })
    }

    private fun finish() {
        busy = false
        if (pending) { pending = false; handler.post(captureNow) }
    }

    private fun onScan(list: List<Item>) {
        if (!active) return
        val dm = resources.displayMetrics
        val hud = list.any { Patterns.HUD.containsMatchIn(it.text) }
        val shop = hud && list.any { Patterns.SHOP.containsMatchIn(it.text) }
        val held = System.currentTimeMillis() < matchHoldUntil
        val next = when {
            !hud -> Mode.MENU
            shop && !held -> Mode.SHOP
            else -> Mode.MATCH
        }
        if (next != mode) {
            mode = next
            setTouchable(mode != Mode.MATCH)
            speaker.speak(when (mode) { Mode.MENU -> "Menu"; Mode.SHOP -> "Shop"; Mode.MATCH -> "Match" })
            wipe(); lastSig = emptySet()
        }
        if (hud) scanMatch(list, dm.widthPixels, dm.heightPixels)
        if (mode != Mode.MATCH) { updateMenu(list, dm.heightPixels); scanProgress(list) }
    }

    private fun updateMenu(list: List<Item>, h: Int) {
        val old = items.getOrNull(focus)?.text
        val row = (h / 25).coerceAtLeast(1)
        items = list.sortedWith(compareBy({ it.box.centerY() / row }, { it.box.left }))
        focus = if (old == null) -1 else items.indexOfFirst { it.text == old }

        // Read the screen when it changes a lot (game opened, new dialog such as an update notice).
        val sig = list.map { it.text.lowercase() }.toSet()
        val union = (sig + lastSig).size
        val sim = if (union == 0) 1.0 else (sig intersect lastSig).size.toDouble() / union
        val now = System.currentTimeMillis()
        if (mode == Mode.MENU && sig.isNotEmpty() && sim < 0.5 && now - lastScreenSpeak > 2500) {
            lastScreenSpeak = now
            val lines = items.map { it.text }
            val notice = lines.filter { Patterns.NOTICE.containsMatchIn(it) }
            val say = when {
                lines.size <= 8 -> lines            // small screen / dialog: read all of it
                notice.isNotEmpty() -> notice       // busy screen: only the important lines
                else -> lines.take(6)
            }
            speaker.speak(say.joinToString(". "), false)
        }
        lastSig = sig
    }

    private fun scanProgress(list: List<Item>) {
        val pct = list.firstNotNullOfOrNull { Patterns.PERCENT.find(it.text)?.groupValues?.get(1)?.toIntOrNull() }
            ?.takeIf { it in 0..100 }
        if (pct == null) { lastBucket = -1; return }
        val bucket = pct / 10
        val now = System.currentTimeMillis()
        if (bucket != lastBucket && now - lastPctSpeak > 1500) {
            lastBucket = bucket; lastPctSpeak = now
            speaker.speak("$pct percent", false)
        }
    }

    private fun scanMatch(list: List<Item>, w: Int, h: Int) {
        val text = list.joinToString("\n") { it.text }
        val now = System.currentTimeMillis()

        // zones
        if (Patterns.BLUE.any { it.containsMatchIn(text) }) {
            calmScans = 0
            if (!blue) { blue = true; speaker.speak("Left safe zone. Entered blue zone.", false) }
        } else if (blue && ++calmScans >= 2) {
            blue = false; calmScans = 0
            speaker.speak("Exited blue zone. Entered safe zone.", false)
        }

        // health: announce when it drops (you got hit)
        val hp = list.firstNotNullOfOrNull { Patterns.HEALTH.find(it.text)?.groupValues?.get(1)?.toIntOrNull() }
        if (hp != null) {
            val prev = health
            health = hp
            if (prev != null && hp < prev && now - lastHitSpeak > 1500) {
                lastHitSpeak = now
                speaker.speak("Hit. Health $hp", false)
            }
        }

        // your own kills
        recentKills.entries.removeIf { now - it.value > 6000 }
        for (item in list) {
            val cx = item.box.centerX(); val cy = item.box.centerY()
            if (cx < w * 0.2 || cx > w * 0.8 || cy < h * 0.12 || cy > h * 0.5) continue // own banner area only
            val m = Patterns.KILL.find(item.text) ?: continue
            val name = m.groupValues[2].trim()
            if (name.isEmpty() || recentKills.containsKey(name.lowercase())) continue
            recentKills[name.lowercase()] = now
            speaker.speak("$name, defeated", false)
        }
    }

    private fun speakHealth() {
        val now = System.currentTimeMillis()
        if (now - lastHealthSpeak < 3000) return
        lastHealthSpeak = now
        val hp = health
        speaker.speak(if (hp != null && mode != Mode.MENU) "Health $hp" else "Health not visible")
    }

    /**
     * Match mode: the overlay lets touches through, so a tiny window just counts them.
     * Two fingers landing within ~70 ms counts as a two-finger tap. Android may not report
     * every finger, and moving + shooting at once can trigger it too (it only speaks health).
     */
    private fun onOutsideTouch(t: Long) {
        if (!active || mode != Mode.MATCH) return
        if (t - lastOutsideT < 70) speakHealth()
        lastOutsideT = t
    }

    // ---------- menu / shop touch navigation ----------
    private fun itemAt(x: Float, y: Float): Int {
        val pad = 24
        return items.indexOfFirst { val b = it.box; x >= b.left - pad && x <= b.right + pad && y >= b.top - pad && y <= b.bottom + pad }
    }

    private fun say(i: Int) { if (i in items.indices) { focus = i; speaker.speak(items[i].text) } }

    private fun onTouch(e: MotionEvent) {
        val slop = resources.displayMetrics.density * 10
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { sx = e.x; sy = e.y; downT = e.eventTime; maxPtr = 1; moved = false }
            MotionEvent.ACTION_POINTER_DOWN -> maxPtr = maxOf(maxPtr, e.pointerCount)
            MotionEvent.ACTION_MOVE -> {
                if (hypot(e.x - sx, e.y - sy) > slop) moved = true
                if (maxPtr == 1 && moved && e.eventTime - downT > 350) {
                    val i = itemAt(e.x, e.y)
                    if (i >= 0 && i != focus) say(i)
                }
            }
            MotionEvent.ACTION_UP -> {
                val dt = e.eventTime - downT
                val dx = e.x - sx; val dy = e.y - sy
                when {
                    maxPtr >= 3 && !moved && dt < 500 -> { performGlobalAction(GLOBAL_ACTION_BACK); speaker.speak("Back"); refresh(600) }
                    maxPtr == 2 && !moved && dt < 500 -> speakHealth()
                    maxPtr == 1 && dt <= 350 && abs(dx) > 120 && abs(dx) > abs(dy) * 1.5 -> {
                        if (items.isNotEmpty()) say(if (dx > 0) (focus + 1).coerceAtMost(items.size - 1) else (focus - 1).coerceAtLeast(0))
                    }
                    maxPtr == 1 && !moved -> {
                        val now = e.eventTime
                        val hit = itemAt(e.x, e.y)
                        if (now - lastTapT < 450 && focus in items.indices) { activate(); lastTapT = 0 }
                        else { lastTapT = now; if (hit >= 0) say(hit) else if (focus in items.indices) speaker.speak(items[focus].text) }
                    }
                }
                maxPtr = 0
            }
        }
    }

    private fun activate() {
        val item = items.getOrNull(focus) ?: return
        val b = item.box
        val bought = Patterns.BUY.containsMatchIn(item.text)
        // Button pressed: throw away the old screen data and any screenshot still being processed.
        generation++
        wipe()
        if (bought) {
            matchHoldUntil = System.currentTimeMillis() + 2500
            mode = Mode.MATCH
            speaker.speak("Match")
        }
        setTouchable(false) // let the injected tap reach the game instead of our overlay
        val path = Path().apply { moveTo(b.centerX().toFloat(), b.centerY().toFloat()) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
        val done = object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) = restore()
            override fun onCancelled(d: GestureDescription?) = restore()
            fun restore() {
                handler.postDelayed({ if (active && mode != Mode.MATCH) setTouchable(true) }, 300)
                refresh(700) // new screen after the tap: take a fresh screenshot
            }
        }
        dispatchGesture(g, done, null)
    }
}
