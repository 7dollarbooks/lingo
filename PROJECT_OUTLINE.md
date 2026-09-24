# Lingo — Detailed Project Outline

**App name:** Lingo (package `com.livetranslate.headphones`)  
**Platform:** Android (minSdk 31, targetSdk 35, Kotlin + Jetpack Compose)  
**Hardware target:** Phone + Shokz OpenRun Pro (Bluetooth SCO for mic + speakers)  
**Version:** 1.0.0

---

## 1. What the app does

Lingo has **two mutually exclusive product surfaces**:

### A. Live translation (original core)
- Capture speech from the Shokz headset mic over Bluetooth SCO
- Detect language; **discard English** (no TTS)
- Translate other languages → English with **on-device ML Kit**
- Speak English through the Shokz speakers

### B. Lingo AI Assistant
- Wake word: **"Hey Lingo" / "Ok Lingo"** (normal mode)
- Cloud LLM: **Google Gemini** (`gemini-flash-lite-latest`) via REST/OkHttp
- **Real-time help**: ambient listening + proactive tips (5 assistance levels)
- **SPIT mode** (Smartest Person In Town): auto-answer questions, no wake word; ignore user’s spoken repeat of the answer
- **Camera vision**: ask about a photo (CameraX + multimodal Gemini)
- Network policy: cloud calls only on **home Wi‑Fi** (`Waupoos24` / `Waupoos24-5G`) or **mobile data**; block other Wi‑Fi

Translation and Assistant cannot run at the same time (service stops one when starting the other).

---

## 2. High-level architecture

```
┌─────────────────────────────────────────────────────────────┐
│ MainActivity (Compose UI: Main / Settings / Assistant)      │
│ MainViewModel  ←→  LiveTranslateApp (singletons)            │
└────────────┬───────────────────────────┬────────────────────┘
             │                           │
             ▼                           ▼
┌────────────────────────┐   ┌───────────────────────────────┐
│ TranslationSession     │   │ AssistantSession              │
│ Listen / Conversation  │   │ AssistantEngine               │
│ ML Kit + STT + TTS     │   │ Wake / SPIT / Real-time help  │
└────────────┬───────────┘   └─────────────┬─────────────────┘
             │                             │
             └──────────────┬──────────────┘
                            ▼
              TranslationForegroundService
              (keeps mic/SCO alive in background)
                            │
              ┌─────────────┴─────────────┐
              ▼                           ▼
     BluetoothAudioRouter          RoutedTtsPlayer
     (SCO / headset route)         (TTS → Shokz)
```

---

## 3. Package / folder map

```
app/src/main/kotlin/com/livetranslate/headphones/
├── LiveTranslateApp.kt          Application; owns shared singletons
├── MainActivity.kt              Navigation + permission requests
├── MainViewModel.kt             UI state bridge to sessions/settings
├── Models.kt                    AppMode, AppScreen, transcripts, assistant models
├── AppSettings.kt               Theme, etc.
├── TranslationSession.kt        Start/stop translation + loopback + TTS test
│
├── audio/
│   ├── BluetoothAudioRouter.kt  SCO connect, preferred BT devices
│   ├── RoutedTtsPlayer.kt       Low-latency speak() + synthesize-to-file path
│   ├── AudioIO.kt               Capture helpers
│   └── PcmAudioPlayback.kt      Loopback PCM play
│
├── translation/
│   ├── RecognitionPipeline.kt   SpeechRecognizer loop
│   ├── ListenModeEngine.kt      Passive ambient thresholds
│   ├── ConversationEngine.kt    Face-to-face style
│   ├── TranslationEngine.kt     Orchestration
│   ├── TranslationProcessor.kt  Lang ID + ML Kit translate + English gate
│   ├── LanguageModelManager.kt  Download/manage on-device packs
│   ├── SupportedLanguages.kt    Language catalog
│   └── SpeechSupport.kt         STT helpers
│
├── assistant/
│   ├── AssistantSession.kt      Lifecycle; vision capture deferred API
│   ├── AssistantEngine.kt       STT loop, wake/SPIT/proactive, queries
│   ├── LlmClient.kt             Gemini REST + stream + network binding
│   ├── NetworkMonitor.kt        Home SSID / cellular standby networks
│   ├── AssistantSettings.kt     API key (encrypted), SPIT, levels, home SSIDs
│   ├── WakeWordDetector.kt      Fuzzy “Hey/Ok Lingo”
│   ├── QuestionDetector.kt      SPIT question + repeat-match heuristics
│   ├── AmbientTranscriptBuffer.kt  Rolling text for real-time help
│   └── VisionIntent.kt          Heuristic: does query need camera?
│
├── service/
│   └── TranslationForegroundService.kt
│
└── ui/
    ├── MainScreen.kt            Translation controls + headset status
    ├── SettingsScreen.kt        Languages, theme, API key, home Wi‑Fi
    ├── AssistantScreen.kt       Lingo Assistant + SPIT + real-time help
    ├── VisionCaptureScreen.kt   CameraX capture + prompt
    └── theme/                   Color.kt, Theme.kt
```

Resources: `AndroidManifest.xml`, `res/values/{strings,themes,colors}.xml`, launcher icons.

---

## 4. Feature outline (by subsystem)

### 4.1 Bluetooth audio
- Prefer **SCO** (call audio) so mic and TTS land on Shokz
- Fallback to A2DP for playback when needed
- Loopback test validates mic → speaker path
- TTS test phrase validates Shokz output (Google Translate failed this)

### 4.2 Translation pipeline
```
Shokz mic → SpeechRecognizer → language ID
  → if English: discard (transcript only)
  → else: ML Kit → English → RoutedTtsPlayer → Shokz
```
- **Listen mode**: longer silence thresholds (pocket / ambient)
- **Conversation mode**: tighter turn-taking
- Language packs downloaded in Settings (Wi‑Fi recommended)

### 4.3 Lingo Assistant (wake-word mode)
```
Continuous STT → WakeWordDetector
  → optional “Yes?” follow-up
  → LlmClient.ask / askWithImage
  → RoutedTtsPlayer.speak
```
- Speech recognizer beep muted via `STREAM_MUSIC` mute for session
- Short system prompt; device local clock injected for time/date
- Streaming Gemini (`streamGenerateContent`) + connection warmup

### 4.4 SPIT mode
- Exclusive with Real-time help
- No wake word; local `QuestionDetector` only (no classify LLM call)
- Stable-partial commit (~420 ms unchanged) so long questions finish
- Drop utterances while answering
- After TTS: **repeat-ignore** window (~10 s) — user’s spoken re-tell of the answer is ignored (token overlap ≥ 60%)
- Tight SPIT prompt: answer only, no question echo; maxOutputTokens 80

### 4.5 Real-time help
- Ambient buffer + timer by `AssistanceLevel` (Minimal→Maximum)
- LLM returns tip or `NO_RESPONSE` sentinel
- Paused while SPIT is on

### 4.6 Vision
- Voice intent or “Ask about a photo” button
- `VisionCaptureScreen` → JPEG + prompt → multimodal Gemini

### 4.7 Network policy
- `NetworkMonitor` tracks Wi‑Fi (SSID via `FLAG_INCLUDE_LOCATION_INFO`) and standby cellular
- `LlmClient` binds sockets to home Wi‑Fi network object or cellular
- Needs: `ACCESS_FINE_LOCATION`, `ACCESS_WIFI_STATE`, `CHANGE_NETWORK_STATE`, `INTERNET`

### 4.8 UI / navigation
- Screens: `MAIN` | `SETTINGS` | `ASSISTANT` (+ overlay vision)
- `AppScreen` lives in **ViewModel** so rotation does not reset to translation

---

## 5. Key dependencies

| Library | Role |
|---------|------|
| Jetpack Compose / Material3 | UI |
| ML Kit Translate + Language ID | On-device translation |
| OkHttp | Gemini REST / SSE stream |
| Security Crypto | Encrypted API key storage |
| CameraX | Vision capture |
| Coroutines / Lifecycle / ViewModel | Async + state |

---

## 6. Permissions (manifest + runtime)

- `RECORD_AUDIO`, `BLUETOOTH` / `BLUETOOTH_CONNECT`
- `CAMERA` (vision)
- `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE`
- `ACCESS_FINE_LOCATION`, `ACCESS_WIFI_STATE` (home SSID)
- `MODIFY_AUDIO_SETTINGS` (mute recognizer beep)
- `FOREGROUND_SERVICE` (+ microphone type), `WAKE_LOCK`, `POST_NOTIFICATIONS`

---

## 7. Runtime flows (short)

**Start translation:** UI → Service `ACTION_START` → `TranslationSession` → SCO + engine.

**Start assistant:** UI → Service `ACTION_START_ASSISTANT` → stops translation if needed → `AssistantSession` → `AssistantEngine`.

**SPIT question:** STT utterance → not busy → not repeat-match → `isQuestion` → Gemini stream → TTS → arm repeat-ignore.

**Cloud call routing:** `homeNetworkStatus(homeSsids)` → `HOME_WIFI` | `MOBILE_DATA` | `BLOCKED` → bind OkHttp `socketFactory`.

---

## 8. Build & run

```text
JDK 17 / Android Studio
./gradlew.bat :app:installDebug
```

First-run checklist: permissions → download language packs → Loopback → Test TTS → Listen/Conversation or Lingo Assistant (+ Gemini API key in Settings).

---

## 9. Where to get the complete code

This outline does **not** paste every file inline (≈40 source files / ~275 KB). Exports live at:

| File | Contents |
|------|----------|
| `exports/lingo-complete-source.zip` | Full `app/src` + Gradle project files |
| `exports/COMPLETE_APP_SOURCE.md` | Every Kotlin/XML source concatenated for reading/search |

Open those from the project root: `C:\Users\7doll\Projects\live-translate-headphones\exports\`
