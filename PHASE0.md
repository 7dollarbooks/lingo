# Phase 0 — Validation results

Tested on **Samsung S25 FE** with **Shokz OpenRun Pro** over **mobile data**.

## Results

| App | Input | Output to Shokz | Listen mode | Decision |
|-----|-------|-----------------|-------------|----------|
| Google Translate Live | Heard audio | **Failed** (no audio in headphones) | N/A | Custom app needed |
| Samsung AI Interpreter | Worked | Worked | **Failed** (conversation only) | Custom app needed |

**Conclusion:** Proceed with custom Live Translate app. Primary fixes:

1. Route TTS through Bluetooth (`RoutedTtsPlayer`) — addresses Google Translate failure
2. Dedicated **Listen mode** with longer silence thresholds for passive ambient use

---

## Original Phase 0 checklist

### 1. Google Translate Live translate

1. Install/update Google Translate
2. Pair Shokz — enable **Phone calls** and **Media audio**
3. Turn off WiFi; use mobile data
4. Open Google Translate → **Live translate**
5. Connect headphones when prompted

**Your result:** Input worked; no translated audio in Shokz.

### 2. Samsung Galaxy AI Interpreter

1. Settings → Galaxy AI → Interpreter
2. Route audio to Shokz

**Your result:** Works in conversation mode only; no passive listen mode.

---

## Custom app validation (after rebuild)

Run these in order on the phone:

1. **Loopback** — hear yourself in Shokz (mic + speaker path)
2. **Test TTS in headphones** — hear test phrase in Shokz (fixes Google Translate output issue)
3. **Listen mode** on mobile data — foreign speech nearby → English in Shokz
4. **Conversation mode** — face-to-face → English in Shokz
5. **English discard** — English speech → transcript shows discarded, no TTS

### Pass criteria

- Test TTS audible in Shokz (this failed in Google Translate)
- Listen mode works with phone in pocket while others speak nearby
- Conversation mode matches Samsung Interpreter behavior
- English speech produces no TTS output
