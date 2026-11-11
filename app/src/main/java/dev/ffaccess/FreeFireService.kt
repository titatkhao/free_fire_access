package dev.ffaccess
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.nio.ByteBuffer
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
    private var pendingMode = Mode.MENU   // a mode change must be seen on consecutive scans before it is believed
    private var pendingCount = 0
    private var prevFrame: IntArray? = null // tiny fingerprint of the last processed screenshot
    private var unchangedStreak = 0
    private var lastOcrAt = 0L
    private var generation = 0 // bumped whenever the screen may have changed: older screenshots are discarded

    private var active = false
    private var busy = false
    private var pending = false
    private var items = listOf<Item>()
    private var focus = -1
    private var focusItem: Item? = null // snapshot of the focused item: survives rescans that reshuffle the list
    private var injecting = false         // true while our own tap is being sent to the game

    // match state
    private var blue = false
    private var blueVotes = 0
    private var calmScans = 0
    private var blueBase = -1f
    private var health: Int? = null
    private var rawHp: Int? = null
    private var lastHitSpeak = 0L
    private var lastHealthSpeak = 0L
    private var prevKillNames = setOf<String>()
    private val recentKills = HashMap<String, Long>()

    // screen-change / progress state
    private var lastSig: Set<String> = emptySet()
    private var announcedSig: Set<String> = emptySet()
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
        override fun run() {
            if (!active) return
            capture()
            handler.postDelayed(this, when {
                CaptureService.instance != null -> Config.FAST_TICK_MS // fast capture: cheap checks every 50 ms
                mode == Mode.MATCH -> Config.MATCH_SCAN_MS
                else -> (Config.MENU_SCAN_MS * (1 + unchangedStreak.coerceAtMost(3))).coerceAtMost(4000L)
            })
        }
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
                    mode = Mode.MENU; resetMatch(); lastSig = emptySet(); announcedSig = emptySet(); lastBucket = -1
                    prevFrame = null; unchangedStreak = 0; pendingMode = Mode.MENU; pendingCount = 0
                    wipe(); generation++
                    setTouchable(true)
                    speaker.speak("On", flush = true)
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
                if (t - lastEventRefresh > 500) { lastEventRefresh = t; refresh(invalidate = false) }
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
    private fun wipe() { items = emptyList(); focus = -1; focusItem = null }

    private fun resetMatch() {
        blue = false; blueVotes = 0; calmScans = 0; blueBase = -1f
        health = null; rawHp = null; prevKillNames = emptySet()
    }

    // ---------- screen capture ----------
    /**
     * The screen changed (tap, window change, content change): the old screenshot and any
     * result still being processed are thrown away, and a fresh screenshot is taken.
     */
    private fun refresh(delayMs: Long = 300, invalidate: Boolean = true) {
        if (!active) return
        if (invalidate) generation++
        handler.removeCallbacks(captureNow)
        handler.postDelayed(captureNow, delayMs)
    }

    private fun capture() {
        if (!active) return
        if (CaptureService.instance != null) captureFast() else captureViaAccessibility()
    }

    private fun captureViaAccessibility() {
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
                // The screenshot lives only in memory (never written to storage) and is freed right after use.
                val cur = frameSignature(bmp)
                if (mode != Mode.MATCH && unchangedStreak < 5 && sameFrame(prevFrame, cur)) {
                    // Nothing changed on screen: no text reading and nothing to say.
                    unchangedStreak++
                    bmp.recycle(); finish(); return
                }
                unchangedStreak = 0
                val ratio = edgeBlueRatio(bmp)
                ocr.run(bmp) { base ->
                    refine(bmp, base) { list ->
                        bmp.recycle(); finish()
                        if (gen == generation) { prevFrame = cur; onScan(list, ratio) } else prevFrame = null
                    }
                }
            }
            override fun onFailure(errorCode: Int) { finish() }
        })
    }

    private fun finish() {
        busy = false
        if (pending) { pending = false; handler.post(captureNow) }
    }

    /** 48x27 brightness fingerprint of the screen, used only to tell whether anything changed. */
    private fun frameSignature(b: Bitmap): IntArray {
        val s = Bitmap.createScaledBitmap(b, 48, 27, true)
        val px = IntArray(48 * 27)
        s.getPixels(px, 0, 48, 0, 0, 48, 27)
        if (s !== b) s.recycle()
        return IntArray(px.size) { val c = px[it]; (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10 }
    }

    private fun sameFrame(a: IntArray?, b: IntArray): Boolean {
        if (a == null || a.size != b.size) return false
        var changed = 0
        for (i in b.indices) if (abs(a[i] - b[i]) > Config.CHANGE_CELL_DIFF) changed++
        return changed < b.size * Config.CHANGE_CELL_FRACTION
    }

    // ---------- fast capture (screen-capture session, every 50 ms) ----------
    private fun sampleSig(w: Int, h: Int, px: (Int, Int) -> Int): IntArray = IntArray(48 * 27) { i ->
        val c = px((i % 48) * w / 48 + w / 96, (i / 48) * h / 27 + h / 54)
        (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10
    }

    private fun bitmapFrom(buf: ByteBuffer, rs: Int, ps: Int, w: Int, h: Int): Bitmap? = runCatching {
        val rowPixels = rs / ps
        val full = Bitmap.createBitmap(rowPixels, h, Bitmap.Config.ARGB_8888)
        buf.rewind()
        full.copyPixelsFromBuffer(buf)
        if (rowPixels == w) full else Bitmap.createBitmap(full, 0, 0, w, h).also { full.recycle() }
    }.getOrNull()

    /**
     * Every tick: look at the newest frame, close it immediately. Text reading (OCR) starts only when
     * the previous reading is finished and, outside matches, only when the screen actually changed.
     */
    private fun captureFast() {
        val cs = CaptureService.instance ?: return
        if (busy) return
        val now = System.currentTimeMillis()
        if (mode == Mode.MATCH && now - lastOcrAt < Config.MATCH_MIN_GAP_MS) return
        val img = cs.latestImage() ?: return
        var fallback = false
        try {
            val plane = img.planes[0]
            val buf = plane.buffer
            val rs = plane.rowStride; val ps = plane.pixelStride
            val w = img.width; val h = img.height
            val px = { x: Int, y: Int ->
                val o = y * rs + x * ps
                Color.rgb(buf.get(o).toInt() and 0xFF, buf.get(o + 1).toInt() and 0xFF, buf.get(o + 2).toInt() and 0xFF)
            }
            val cur = sampleSig(w, h, px)
            if (cur.average() < 2.0) { fallback = true; return } // all black: the game blocks capture, use the other method
            if (mode != Mode.MATCH && now - lastOcrAt < 3000 && sameFrame(prevFrame, cur)) return
            val ratio = edgeBlueRatio(w, h, px)
            val gen = generation
            val bmp = bitmapFrom(buf, rs, ps, w, h) ?: return
            busy = true; lastOcrAt = now
            ocr.run(bmp) { base ->
              refine(bmp, base) { list ->
                bmp.recycle(); busy = false; lastOcrAt = System.currentTimeMillis()
                if (gen == generation) { prevFrame = cur; onScan(list, ratio) } else prevFrame = null
              }
            }
        } finally {
            img.close() // the frame is deleted right away
            if (fallback) captureViaAccessibility()
        }
    }

    /** Share of strongly blue pixels in a thin band around the screen edges (blue-zone tint). */
    private fun edgeBlueRatio(b: Bitmap): Float = edgeBlueRatio(b.width, b.height) { x, y -> b.getPixel(x, y) }

    private fun edgeBlueRatio(w: Int, h: Int, px: (Int, Int) -> Int): Float {
        val band = (w * 0.08f).toInt().coerceAtLeast(4)
        val step = 12
        var n = 0; var hit = 0
        fun s(x: Int, y: Int) {
            val p = px(x, y)
            val r = Color.red(p); val g = Color.green(p); val bl = Color.blue(p)
            n++
            if (bl > 90 && bl > r + 35 && bl > g + 15) hit++
        }
        var y = 0
        while (y < h) { var x = 0; while (x < band) { s(x, y); s(w - 1 - x, y); x += step }; y += step }
        var x = 0
        while (x < w) { var yy = 0; while (yy < band) { s(x, yy); yy += step }; x += step }
        return if (n == 0) 0f else hit.toFloat() / n
    }

    /** Real Android text (dialogs, web pages, system views) is exact, so it is preferred over OCR. */
    private fun nodeItems(): List<Item> {
        val out = ArrayList<Item>()
        val root = rootInActiveWindow ?: return out
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 25) return
            val t = (n.text ?: n.contentDescription)?.toString()?.trim()
            if (!t.isNullOrEmpty() && n.isVisibleToUser) {
                val r = Rect(); n.getBoundsInScreen(r)
                if (!r.isEmpty) out.add(Item(t, r))
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        runCatching { walk(root, 0) }
        return out
    }

    private var scanCount = 0

    /** In matches/shop, re-read key areas zoomed in (only when there is text there, plus every 3rd scan). */
    private fun refine(bmp: Bitmap, base: List<Item>, done: (List<Item>) -> Unit) {
        if (!Config.REGION_PASSES || mode == Mode.MENU) { done(base); return }
        scanCount++
        val w = bmp.width; val h = bmp.height
        val regions = Config.REGIONS.map { f -> Rect((f[0] * w).toInt(), (f[1] * h).toInt(), (f[2] * w).toInt(), (f[3] * h).toInt()) }
            .filter { r -> scanCount % 3 == 0 || base.any { r.contains(it.box.centerX(), it.box.centerY()) } }
        fun next(i: Int, acc: List<Item>) {
            if (i >= regions.size) { done(acc); return }
            val r = regions[i]
            ocr.cropPass(bmp, r) { better ->
                next(i + 1, acc.filter { !r.contains(it.box.centerX(), it.box.centerY()) } + better)
            }
        }
        next(0, base)
    }

    private fun merge(nodes: List<Item>, ocr: List<Item>): List<Item> =
        nodes + ocr.filter { o -> nodes.none { it.box.contains(o.box.centerX(), o.box.centerY()) } }

    private fun onScan(ocrList: List<Item>, edgeBlue: Float) {
        if (!active) return
        val list = merge(nodeItems(), ocrList)
        val dm = resources.displayMetrics
        val hud = list.any { Fuzzy.hasWord(it.text, "alive") }
        val shop = hud && list.any { Patterns.SHOP.containsMatchIn(it.text) }
        val held = System.currentTimeMillis() < matchHoldUntil
        val next = when {
            !hud -> Mode.MENU
            shop && !held -> Mode.SHOP
            else -> Mode.MATCH
        }
        if (next != mode) {
            // A single misread scan must not flip the mode (that caused "Menu / Match" chatter).
            if (next == pendingMode) pendingCount++ else { pendingMode = next; pendingCount = 1 }
            val need = if (mode == Mode.MATCH && next == Mode.MENU) 3 else 2
            if (pendingCount >= need) {
                pendingCount = 0
                mode = next
                if (mode == Mode.MENU) resetMatch()
                setTouchable(mode != Mode.MATCH)
                speaker.speak(when (mode) { Mode.MENU -> "Menu"; Mode.SHOP -> "Shop"; Mode.MATCH -> "Match" }, Prio.NORMAL, "mode")
                wipe(); lastSig = emptySet(); announcedSig = emptySet()
            }
        } else pendingCount = 0
        if (mode != Mode.MENU) scanMatch(list, dm.widthPixels, dm.heightPixels, edgeBlue)
        if (mode != Mode.MATCH) { updateMenu(list, dm.heightPixels); scanProgress(list) }
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        val u = (a + b).size
        return if (u == 0) 1.0 else (a intersect b).size.toDouble() / u
    }

    private fun updateMenu(list: List<Item>, h: Int) {
        val row = (h / 25).coerceAtLeast(1)
        items = list.sortedWith(compareBy({ it.box.centerY() / row }, { it.box.left }))
        // Re-find the focused item in the new list (same text, else the closest one). If it vanished,
        // keep the old snapshot so a double tap still works.
        focusItem?.let { fi ->
            var idx = items.indexOfFirst { it.text == fi.text && hypot((it.box.centerX() - fi.box.centerX()).toFloat(), (it.box.centerY() - fi.box.centerY()).toFloat()) < 120f }
            if (idx < 0) idx = items.indexOfFirst { it.text == fi.text }
            if (idx >= 0) { focus = idx; focusItem = items[idx] } else focus = -1
        }

        // Speak only what changed, and only once it has settled (seen on two scans in a row).
        val sig = list.map { it.text.lowercase() }.toSet()
        val now = System.currentTimeMillis()
        if (mode == Mode.MENU && sig.isNotEmpty() && jaccard(sig, lastSig) >= 0.8 && now - lastScreenSpeak > 2000) {
            val newScreen = jaccard(sig, announcedSig) < 0.5
            val added = (sig intersect lastSig) - announcedSig
            val lines = items.map { it.text }.filter { it.count { c -> c.isLetter() } >= 3 }.distinct()
            val pick = if (newScreen) {
                val notice = lines.filter { Patterns.NOTICE.containsMatchIn(it) }
                (if (lines.size <= Config.SCREEN_MAX_LINES) lines else if (notice.isNotEmpty()) notice else lines)
                    .take(Config.SCREEN_MAX_LINES)
            } else lines.filter { it.lowercase() in added }.take(2) // same screen: only the new lines
            if (pick.isNotEmpty()) {
                lastScreenSpeak = now
                speaker.speak(pick.joinToString(". ").take(Config.SCREEN_MAX_CHARS), Prio.LOW, "screen")
            }
            if (newScreen || pick.isNotEmpty()) announcedSig = sig
        }
        lastSig = sig
    }

    private fun scanProgress(list: List<Item>) {
        val pct = list.firstNotNullOfOrNull { Patterns.PERCENT.find(it.text)?.groupValues?.get(1)?.toIntOrNull() }
            ?.takeIf { it in 0..100 }
        if (pct == null) { lastBucket = -1; return }
        val bucket = pct / Config.PROGRESS_STEP
        val now = System.currentTimeMillis()
        if (bucket != lastBucket && now - lastPctSpeak > 1500) {
            lastBucket = bucket; lastPctSpeak = now
            speaker.speak("$pct percent", Prio.LOW, "progress")
        }
    }

    private fun fixDigits(s: String): Int? =
        s.map { when (it) { 'O', 'o' -> '0'; 'l', 'I' -> '1'; 'S' -> '5'; 'B' -> '8'; else -> it } }
            .joinToString("").toIntOrNull()

    private fun scanMatch(list: List<Item>, w: Int, h: Int, edgeBlue: Float) {
        val text = list.joinToString("\n") { it.text }
        val now = System.currentTimeMillis()

        // blue zone: text warning OR blue edge tint, needing two scans in a row to start
        if (blueBase < 0f) blueBase = edgeBlue
        else if (edgeBlue < blueBase + 0.1f) blueBase = 0.9f * blueBase + 0.1f * edgeBlue
        val colorBlue = Config.USE_COLOR_ZONE && edgeBlue > blueBase + 0.2f
        val textBlue = Patterns.BLUE.any { it.containsMatchIn(text) }
        if (textBlue || colorBlue) {
            calmScans = 0; blueVotes++
            if (!blue && blueVotes >= 2) { blue = true; speaker.speak("Blue zone", Prio.HIGH, "zone") }
        } else {
            blueVotes = 0
            if (blue && ++calmScans >= 3) { blue = false; calmScans = 0; speaker.speak("Safe zone", Prio.HIGH, "zone") }
        }

        // health: a value counts only if two scans in a row agree (guards against misread digits)
        val hp = list.firstNotNullOfOrNull { Patterns.HEALTH.find(it.text)?.groupValues?.get(1)?.let(::fixDigits) }
            ?.takeIf { it in 0..999 }
        if (hp != null && hp == rawHp) {
            val prev = health
            health = hp
            if (prev != null && hp < prev) {
                // Rapid hits: every new value cuts off the previous one, and after the first only the number is said.
                val rapid = now - lastHitSpeak < 2500
                lastHitSpeak = now
                speaker.speak(if (rapid) "$hp" else "Health $hp", Prio.HIGH, "hp")
            }
        }
        rawHp = hp

        // your own kills: the name must appear in two scans in a row
        recentKills.entries.removeIf { now - it.value > 6000 }
        val seen = HashMap<String, String>()
        for (item in list) {
            val cx = item.box.centerX(); val cy = item.box.centerY()
            if (cx < w * 0.25 || cx > w * 0.75 || cy < h * 0.1 || cy > h * 0.45) continue // own banner area only (not the kill feed)
            val name = Fuzzy.killVictim(item.text) ?: continue
            if (name.length >= 2 && !name.equals("you", true)) seen[name.lowercase()] = name
        }
        for ((key, name) in seen) {
            val prevKey = prevKillNames.firstOrNull { Fuzzy.similar(it, key) }
            if (prevKey != null && recentKills.keys.none { Fuzzy.similar(it, key) }) {
                recentKills[key] = now
                // OCR drops letters more often than it adds them: say the longer of the two readings
                speaker.speak("${if (prevKey.length > key.length) prevKey else name}, defeated", Prio.HIGH, "kill:$key")
            }
        }
        prevKillNames = seen.keys.toSet()
    }

    private fun speakHealth() {
        val now = System.currentTimeMillis()
        if (now - lastHealthSpeak < 3000) return
        lastHealthSpeak = now
        val hp = health
        speaker.speak(if (hp != null && mode != Mode.MENU) "Health $hp" else "Health not visible", flush = true)
    }

    /**
     * Match mode: the overlay lets touches through, so a tiny window just counts them.
     * Two fingers landing within ~70 ms counts as a two-finger tap.
     */
    private fun onOutsideTouch(t: Long) {
        if (!active || mode != Mode.MATCH) return
        speaker.stopIfNotCritical()
        if (t - lastOutsideT < 70) speakHealth()
        lastOutsideT = t
    }

    // ---------- menu / shop touch navigation ----------
    private fun itemAt(x: Float, y: Float): Int {
        val pad = 24
        return items.indexOfFirst { val b = it.box; x >= b.left - pad && x <= b.right + pad && y >= b.top - pad && y <= b.bottom + pad }
    }

    private fun say(i: Int) { if (i in items.indices) { focus = i; focusItem = items[i]; speaker.speak(items[i].text, flush = true) } }

    private fun onTouch(e: MotionEvent) {
        if (injecting) return // ignore our own injected tap
        val slop = resources.displayMetrics.density * 16
        when (e.actionMasked) {
            MotionEvent.ACTION_CANCEL -> { maxPtr = 0; moved = false }
            MotionEvent.ACTION_DOWN -> { speaker.stop(); sx = e.x; sy = e.y; downT = e.eventTime; maxPtr = 1; moved = false }
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
                    maxPtr >= 3 && !moved && dt < 500 -> { performGlobalAction(GLOBAL_ACTION_BACK); speaker.speak("Back", flush = true); refresh(600) }
                    maxPtr == 2 && !moved && dt < 500 -> speakHealth()
                    maxPtr == 1 && dt <= 350 && abs(dx) > 120 && abs(dx) > abs(dy) * 1.5 -> {
                        if (items.isNotEmpty()) say(if (dx > 0) (focus + 1).coerceIn(0, items.size - 1) else (if (focus < 0) 0 else focus - 1).coerceAtLeast(0))
                    }
                    maxPtr == 1 && !moved -> {
                        val now = e.eventTime
                        val hit = itemAt(e.x, e.y)
                        if (now - lastTapT < 500 && focusItem != null) { lastTapT = 0; activate() }
                        else { lastTapT = now; if (hit >= 0) say(hit) } // empty space: just silence
                    }
                }
                maxPtr = 0
            }
        }
    }

    private fun activate() {
        val item = focusItem ?: return
        val b = item.box
        val bought = Patterns.BUY.containsMatchIn(item.text)
        // Button pressed: throw away the old screen data and any screenshot still being processed.
        generation++
        prevFrame = null
        wipe()
        if (bought) {
            matchHoldUntil = System.currentTimeMillis() + 2500
            mode = Mode.MATCH
            speaker.speak("Match", Prio.NORMAL, "mode")
        }
        injecting = true
        setTouchable(false) // let the injected tap reach the game instead of our overlay
        // Give Android a moment to apply the flag, otherwise our own tap would land on our overlay.
        handler.postDelayed({
            val path = Path().apply { moveTo(b.centerX().toFloat(), b.centerY().toFloat()) }
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
            val done = object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) = restore()
                override fun onCancelled(d: GestureDescription?) = restore()
                fun restore() {
                    injecting = false
                    handler.postDelayed({ if (active && mode != Mode.MATCH) setTouchable(true) }, 200)
                    refresh(700) // new screen after the tap: take a fresh screenshot
                }
            }
            if (!dispatchGesture(g, done, null)) {
                injecting = false
                if (active && mode != Mode.MATCH) setTouchable(true)
            }
        }, 150)
    }
}
