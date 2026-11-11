package dev.ffaccess

/** Quick switches. Lower numbers / false = the app talks less or relies less on guesses. */
object Config {
    const val ENHANCE_OCR = false       // grayscale + contrast on the whole frame (the zoomed banner passes always use it)
    const val USE_COLOR_ZONE = true     // also detect the blue zone from screen-edge color, not only text
    const val SCREEN_MAX_LINES = 4      // lines read when a screen/dialog appears
    const val SCREEN_MAX_CHARS = 140    // hard cap on that announcement
    const val PROGRESS_STEP = 25        // announce progress every N percent
    const val MATCH_SCAN_MS = 350L      // how often the screen is checked during a match
    const val MENU_SCAN_MS = 400L       // ...and in menus (slows down by itself while nothing changes)
    const val FAST_TICK_MS = 50L        // fast capture: how often the newest frame is looked at
    const val MATCH_MIN_GAP_MS = 120L   // fast capture: minimum time between text readings in a match (battery/heat)
    const val CHANGE_CELL_DIFF = 28     // how different a screen cell must be to count as changed (0-255)
    const val CHANGE_CELL_FRACTION = 0.04 // share of changed cells that counts as "the screen changed"

    // Zoomed second look at areas where exact words matter (kill banner, top warnings).
    // Each area is [left, top, right, bottom] as fractions of the screen. Used in matches and the shop only.
    const val REGION_PASSES = true
    val REGIONS = listOf(floatArrayOf(0.25f, 0.10f, 0.75f, 0.45f), floatArrayOf(0.2f, 0.0f, 0.8f, 0.18f))
}
