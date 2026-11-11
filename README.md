Page 1
# Free Fire Access

An Android accessibility service that works as a screen reader for **Free Fire**, **Free Fire MAX** and **Free Fire India**. It speaks with your system text-to-speech settings (engine, voice, rate, pitch) and plays on the **media** volume.

Free Fire draws its own graphics, so Android can't read its menus directly. The service takes a screenshot, reads the text on it with on-device OCR (ML Kit), and keeps nothing: screenshots stay in memory only, are never saved, and are discarded whenever the screen changes or you press a button.

## Features
- **Menus and lobby:** drag a finger or swipe left/right to move between items, double tap to activate, three-finger tap to go back.
- **Shop:** same navigation. Double tapping a Buy/Purchase/Confirm/Equip button returns to match mode.
- **Match mode:** touches pass straight through to the game.
- **Zones:** announces when you leave the safe zone / enter the blue zone and when you exit it (your own player only).
- **Defeats:** announces the victim's name followed by "defeated" for your own kills only.
- **Health:** announces your health when you get hit, or on a two-finger tap.
- **Screens and progress:** reads the screen when the game opens or a dialog appears (for example an update notice with its size and button), and announces progress every 10 percent.

## Install
1. Download the APK from the **Actions** tab (open the latest run, then *Artifacts*), or build it yourself (below).
2. Install it, open **Settings → Accessibility → Free Fire Access** and turn it on.
3. Open Free Fire. Turn TalkBack off while playing; it conflicts with the touch overlay.

## Build
Requires JDK 17 and Android SDK Platform 36 (Android Studio recommended).

    ./gradlew assembleDebug

The APK is written to `app/build/outputs/apk/debug/`.

| Setting | Value |
|---|---|
| compileSdk | 36 |
| targetSdk | 34 |
| minSdk | 30 (Android 11, needed for accessibility screenshots) |
| Android Gradle Plugin | 8.9.1 (Gradle 8.11.1) |
| Kotlin | 2.0.21 |

## Tuning
All on-screen text matching lives in `app/src/main/java/dev/ffaccess/Patterns.kt`: match HUD, shop, buy buttons, blue-zone warnings, kill banner, health and progress text. These are best guesses. Check them against real screenshots from your game and language, and adjust.

## Known limits
- OCR reads Latin text only, so player names in other scripts won't be read.
- Health is read only if the HUD shows it as text. During a match the two-finger tap is detected approximately and can also fire when two fingers land together while playing.
- Package names: `com.dts.freefireth`, `com.dts.freefiremax`, `com.garena.game.ffi`. Verify the Free Fire India one on your device.
- The app only reads the screen and taps through accessibility; it never touches the game's files or memory. Anti-cheat systems may still treat overlays differently, so use it at your own risk.

This project is not affiliated with or endorsed by Garena.
