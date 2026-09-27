# Contributing to Lingo

Lingo is GPLv3. A contribution is accepted only through a pull request. `main` is protected. The maintainer merges it.

## What you are agreeing to

Submit only work you have the right to license under GPLv3. `git commit -s` is not required. A signed-off line does not make the project liable for someone else's code, and it is not a finding that the code is non-infringing.

The glasses path already calls the proprietary `com.oudmon.ble` library. A glasses change may keep using that library. Do not paste source from the HeyCyan app. Do not add another closed-source SDK.

The Oudmon AAR is not in this repository. A glasses build needs that library on your machine. `local.properties` is not committed.

No Gemini API key is in this repository. Do not add one. Enter a key in the app Settings on the phone. It is required for wake-word questions (“Hey Lingo,” “Hello Lingo,” “OK Lingo”), SPIT, real-time help, photo questions, and sign or menu reading that falls through to Gemini. Listen mode, Conversation mode, the watch, and the glasses Wi-Fi import do not use it.

## Health Mode

Watch code lives in two files:

- `app/src/main/kotlin/com/livetranslate/headphones/watch/MoyoungWatchClient.kt`
- `app/src/main/kotlin/com/livetranslate/headphones/ui/WatchVitalsScreen.kt`

`WatchVitals` currently has heart rate, systolic and diastolic blood pressure, steps, and calories. Heart rate and blood pressure update only when that card is refreshed. Steps and calories are read when the Watch screen opens and may update while that screen stays open. The link closes when you leave the screen.

A new metric needs a field on `WatchVitals`, a parser for a reply already seen on this watch or a command byte already documented in Gadgetbridge's Moyoung code for 0.94.0, and a card with its own refresh button. Do not invent a command byte. Do not add a timer that measures heart rate or blood pressure.

There is no `WatchVitalsPoller`, no `IVitalsProvider`, and no `com.lingo.modes.health` package. Glasses Bluetooth and the watch link are separate. Keep a watch change inside the watch client and the Watch screen.

## Other modes

Speech translation, the Gemini assistant (including SPIT), and glasses photo questions are in use. A health change should leave those paths alone.
