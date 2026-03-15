# Hush AI

Private AI that runs on your device. No cloud, no accounts, no data collection.

Works offline after a one-time model download. Reads PDFs, Word docs, and text files — all on-device.

## Download

[<img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" height="80">](https://play.google.com/store/apps/details?id=app.hushai.android)

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
- Share text from any app into Hush AI for instant help
- Smart actions — text contacts, draft emails, add calendar events
- PDF, Word doc, CSV, and code file support
- OCR for scanned PDFs (mobile)
- Conversation history saved locally
- Tappable phone numbers, emails, addresses in responses
- System tray + Ctrl+Shift+H hotkey (desktop)
- 200+ languages supported
- Works completely offline after model download

## Known issues

- First response is slower while the model loads into RAM
- Windows desktop not code-signed — SmartScreen will warn
- No iOS version yet

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
