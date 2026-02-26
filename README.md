# Hush AI

Offline AI chat for Android. Runs Qwen3 on your phone. Nothing leaves your device.

Not fast. But it works and nothing phones home.

## Download

[APK in Releases](https://github.com/ahitokun/hushai-android/releases) · [hushai.app](https://hushai.app)

## What is this

A private AI assistant that runs 100% on your phone. No servers, no accounts, no telemetry. Pick a model, download it once, and chat offline forever.

It reads PDFs and Word docs too — extracts text locally, OCRs image-based PDFs with ML Kit, and the AI analyzes everything without uploading anything anywhere.

## Why another local LLM app?

I tried PocketPal, MNN Chat, ChatterUI. They're cool but:

- No Kotlin/Java library supports Qwen3 GGUF as of Feb 2026
- kotlinllamacpp is stuck on an old llama.cpp that can't read Qwen3
- java-llama.cpp broke its headers after b4916
- React Native wrappers (llama.rn) work but wrong stack for native Android

So I wrote a ~420 line C++ JNI bridge from scratch using llama.cpp b7446 with `LLAMA_BUILD_COMMON=OFF`. It depends only on `llama.h` — no `common/` headers, no breakage.

## Models

| Tier | Model | Size | Min RAM |
|------|-------|------|---------|
| ⚡ Swift | Qwen3-0.6B Q4_K_M | 484 MB | 3 GB |
| 🎯 Smart | Qwen3-1.7B Q4_K_M | 1.3 GB | 4 GB |
| 🧠 Genius | Qwen3-4B Q4_K_M | 2.5 GB | 6 GB |

App auto-recommends based on your phone's RAM.

## Benchmarks

Real numbers on real phones:

**Pixel 7** (Tensor G2, 8GB) — Genius (4B):
- Prefill: ~6s warm, ~21s cold
- Generation: 4-6 tok/s

**Galaxy A52** (SD 720G, 6GB) — Smart (1.7B):
- Prefill: ~9s
- Generation: ~8 tok/s

## Features

- Chat with streaming responses
- PDF text extraction + OCR fallback (ML Kit, offline)
- Word doc support
- Conversation history (saved locally)
- Deep linking — AI responses include tappable phone numbers, emails, addresses
- App detection — knows what's installed, tailors responses
- Stop button, copy, model switching
- Dark mode via system theme

## Known issues

- First response after model load is slower (cold prefill)
- 0.6B (Swift) is too small for document analysis — file button hidden on that tier
- Image-based PDFs depend on OCR quality
- No iOS version yet

## Build

Needs Android Studio, NDK, and CMake. First build downloads llama.cpp via FetchContent (needs internet).

    git clone https://github.com/ahitokun/hushai-android.git
    cd hushai-android
    # Open in Android Studio → Build → Run

Tested with NDK 27, CMake 3.22+, Android Studio Ladybug.

## Tech

- Kotlin + Jetpack Compose
- Custom C++ JNI bridge → llama.cpp b7446
- ML Kit text recognition (English + Devanagari, offline)
- SQLite for conversations
- No external AI libraries or SDKs

## License

Apache 2.0
