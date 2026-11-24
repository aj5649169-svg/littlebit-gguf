# LittleBit Chat 1.0 for Android ARM64

Run GGUF chat models on Android 9 or later. Inference and chat storage stay on your device. The Model Hub opens external downloads in your browser. Choose a downloaded GGUF using the system file picker or reload it from My Local Models.

## Install

1. Download **LittleBit-Chat-arm64.apk** from [v1.0.0](https://github.com/aj5649169-svg/littlebit-gguf/releases/tag/v1.0.0).
2. Open the APK in Files or Chrome. If prompted, allow that app to install unknown apps, install LittleBit Chat, then turn that permission off.
3. Load a GGUF. Start with a 4B Q4 model when memory is tight. An 8B Q4 model needs room for weights, working buffers, KV cache and Android itself.
4. For an update, Android requires the same signing certificate. If signatures differ, export important chats before uninstalling. Uninstalling removes app-private chats and imported model copies.

## Inference

Vulkan defaults to 99 requested GPU layers. A failed Vulkan load retries on CPU. The backend badge shows which path loaded. New installations use a 2,048-token context, a 512-token logical batch and at most 256 tokens per physical batch. Existing explicit preferences are preserved.

Vulkan Flash Attention requests Q8_0 keys and values where context initialization accepts them. If initialization fails, the app retries F16 on the same loaded model before attempting CPU. CPU uses memory-mapped weights, F16 KV, smaller prompt chunks and Arm KleidiAI. Reload after changing the backend, flash setting, batch or context size. A reload rebuilds the existing conversation on its first send. Later turns reuse matching tokens. Prompt progress includes the history being processed.

## Realme P3 Ultra measurements

User-reported results on a 12 GB Realme P3 Ultra with Qwen3-8B-Q4_K_M, recorded before this release polish.

| Run | Prefill | Decode |
| --- | ---: | ---: |
| Vulkan chat | 35.0 tokens/s | up to 5.1 tokens/s |
| Vulkan Quick Benchmark | unreported | 4.6 tokens/s |
| Earlier CPU build | unreported | 2.7 tokens/s |
| CPU after model reload, about 1.3 GB free | no completed measurement | no token observed within 30 seconds |

These runs used different conditions. To compare this release, reload the same model on each backend with the same context and thread count, then use **Settings → Run Quick Benchmark**. The last five runs are saved.

## Chat features

- Fast/Think modes, collapsible reasoning, optional background pre-warm, UTF-8 streaming, manual scrolling and jump to bottom.
- TTFT, prefill and decode rates, context meter, sampling controls and system personas.
- Local text/code attachments, prompt templates, calculator and optional local clock context.
- Search, pin, rename, export Markdown, edit and resend, regenerate and continue interrupted replies.
- Markdown tables, code copy, links, math formatting, AMOLED mode, accent colors, font sizes and haptics.

## Building and signing

The [APK workflow](../.github/workflows/build-android-apk.yml) installs the pinned NDK, CMake and host Vulkan SDK, applies the llama.cpp patches, runs Gradle 8.10 with JDK 17, builds the R8-optimized release variant and verifies its APK signature. Tagging the release commit as v1.0.0 attaches the APK to GitHub Releases.

For stable update signing, configure GitHub secrets ANDROID_RELEASE_KEYSTORE_BASE64, ANDROID_RELEASE_STORE_PASSWORD, ANDROID_RELEASE_KEY_ALIAS and ANDROID_RELEASE_KEY_PASSWORD. Local builds take ANDROID_RELEASE_STORE_FILE and the same three password/alias environment variables. Without a configured key the release uses the debug signing configuration. Fresh CI runners can generate different certificates.
