package dev.ffaccess

/**
 * TUNE THESE against real screenshots / your game language.
 * Everything here matches text that Free Fire draws on YOUR OWN screen.
 */
object Patterns {
    // Free Fire, Free Fire MAX, Free Fire India
    val PACKAGES = setOf("com.dts.freefireth", "com.dts.freefiremax", "com.garena.game.ffi")

    // Text only visible during a match HUD.
    val HUD = Regex("alive", RegexOption.IGNORE_CASE)

    // Text that shows the in-match shop / buy menu is open.
    val SHOP = Regex("\\b(shop|buy menu|purchase)\\b|\\bbuy\\b", RegexOption.IGNORE_CASE)

    // Buttons that finish a purchase. Activating one returns you to match mode.
    val BUY = Regex("^\\s*(buy|purchase|confirm|equip)\\b", RegexOption.IGNORE_CASE)

    val BLUE = listOf(
        Regex("outside (of )?the safe ?zone", RegexOption.IGNORE_CASE),
        Regex("(in|inside|entered) (the )?(blue|poison) ?zone", RegexOption.IGNORE_CASE),
        Regex("return to (the )?safe ?zone", RegexOption.IGNORE_CASE),
        Regex("leave (the )?(blue|poison) ?zone", RegexOption.IGNORE_CASE),
    )

    // Center-screen banner for YOUR kills only. It must START with the verb (optionally "You"),
    // so kill-feed lines about other players ("A eliminated B") never match. Group 2 = victim name.
    val KILL = Regex("^(?:you\\s+)?(knocked down|eliminated|defeated|killed)\\s+(.{2,24})$", RegexOption.IGNORE_CASE)

    // Your own health number on the HUD, e.g. "HP 85" / "Health: 85". Group 1 = number.
    // If your HUD shows the number differently (e.g. "85/200"), change this.
    val HEALTH = Regex("(?:hp|health)\\D{0,3}([0-9OolISB]{1,3})", RegexOption.IGNORE_CASE)

    // Progress text, e.g. "Loading 45%". Group 1 = percent.
    val PERCENT = Regex("(\\d{1,3})\\s*%")

    // Lines that matter on update / loading / error screens.
    val NOTICE = Regex(
        "update|version|download|install|loading|\\d+(\\.\\d+)?\\s*(MB|GB|KB)|error|connect|login|retry",
        RegexOption.IGNORE_CASE)
}
