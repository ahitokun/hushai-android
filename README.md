# Hush AI

Private AI that runs on your device. No cloud, no accounts, no data collection.

Works offline after a one-time model download. Reads PDFs, Word docs, and text files — all on-device.

## Download

[Google Play](https://play.google.com/store/apps/details?id=app.hushai.android) · [APK in Releases](https://github.com/ahitokun/hushai-android/releases) · [Windows Desktop](https://github.com/ahitokun/hushai-android/releases) · [hushai.app](https://hushai.app)

## Models

Runs Qwen3.5 — Alibaba's latest open-source model family.

### Mobile (Android)

| Tier | Model | Size | Min RAM |
|------|-------|------|---------|
| ⚡ Swift | Qwen3.5-0.8B | 533 MB | 3 GB |
| 🎯 Smart | Qwen3.5-2B | 1.5 GB | 4 GB |
| 🧠 Genius | Qwen3.5-4B | 2.7 GB | 6 GB |

### Desktop (Windows)

| Tier | Model | Size | Min RAM |
|------|-------|------|---------|
| ⚡ Swift | Qwen3.5 2B | 2.7 GB | 4 GB |
| 🎯 Smart | Qwen3.5 4B | 3.4 GB | 8 GB |
| 🧠 Genius | Qwen3.5 9B | 6.6 GB | 16 GB |

App picks the best model for your device automatically.

## Features

- Chat with streaming responses
- PDF, Word doc, CSV, and code file support
- OCR for scanned PDFs (mobile)
- Conversation history saved locally
- Tappable phone numbers, emails, addresses in responses
- Open in email, maps, calendar (desktop)
- System tray + Ctrl+Shift+H hotkey (desktop)
- Stop, copy, save, model switching
- Thinking disabled by default — fast responses

## Known issues

- First response is slower while the model loads into RAM
- Desktop: Ollama doesn't fully support disabling Qwen3.5 thinking yet — responses may be slower than expected
- No iOS version yet
- Windows desktop not code-signed — SmartScreen will warn

## Build

### Mobile
Needs Android Studio, NDK, and CMake.

```
git clone https://github.com/ahitokun/hushai-android.git
# Open in Android Studio → Build → Run
```

### Desktop
Needs Node.js and Rust.

```
cd app && npm install && npm run tauri build
```

## License

Apache 2.0
