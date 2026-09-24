# Lingo

An Android app for the Samsung Galaxy S25 FE. It translates speech in Shokz headphones, reads photos from HeyCyan-style glasses, and shows heart rate, blood pressure, steps, and calories from an ST9 watch.

## Author

This project was built by **7dollarbooks** in 2026.

It is released under the GNU General Public License v3. See [LICENSE](LICENSE). Derivative works that you distribute must stay under GPLv3 and keep this copyright notice.

## What it does

The app runs a foreground service so translation can continue while the phone is locked. Four parts work together:

1. **Glasses link.** Bluetooth stays connected for commands. A photo taken with the glasses camera button is a full JPEG on the glasses, downloaded over a direct Wi-Fi link.
2. **Photo questions.** Gemini Flash-Lite describes the photo or reads a sign. The upload is a smaller JPEG. Spoken answers start as sentences arrive.
3. **Headphones.** Speech recognition and spoken replies use the Shokz OpenRun Pro over media audio.
4. **Watch.** While the Watch screen is open, Lingo reads the ST9 directly. Heart rate and blood pressure start only when that card is refreshed. Steps and calories are read on connect and can update while the screen stays open.

## Hardware protocol

See [HARDWARE_PROTOCOL.md](HARDWARE_PROTOCOL.md).

## Build

- Android Studio with JDK 17
- `local.properties` pointing at the Android SDK (this file is not committed)
- The Oudmon glasses SDK AAR, if it is not already on the machine. AAR files are not committed.

```
.\gradlew.bat :app:installDebug
```

On first launch, grant microphone, Bluetooth, nearby Wi-Fi, notifications, and location. Location is used to tell the home Wi-Fi name from other networks. Add a Gemini API key in Settings before asking about a photo.
