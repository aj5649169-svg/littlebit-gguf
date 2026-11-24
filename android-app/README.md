# LittleBit Chat — Android ARM64 AI Chat App

Native on-device LLM chat for Android (arm64-v8a, minSdk 28 / Android 9+),
powered by this repository's patched [llama.cpp](../third_party/llama.cpp)
with LittleBit `Q1_0` sub-1-bit support and Arm KleidiAI kernels.

The model runs in-process through a JNI bridge (`libllama_jni.so`) — no
shell execution, same speed as the Termux builds from
[scripts/build-android.sh](../scripts/build-android.sh).

## Layout

```
app/
  build.gradle.kts            # AGP 8.7 / Kotlin 2.0 / Compose, arm64-v8a only
  src/main/cpp/
    CMakeLists.txt            # builds llama.cpp (static) + libllama_jni.so
    llama_jni.cpp             # JNI bridge: load / template / stream / stop
  src/main/java/com/littlebit/chat/
    engine/LlamaEngine.kt     # Kotlin wrapper around the native engine
    MainActivity.kt           # minimal engine test screen (full UI lands in Step 2)
  src/main/assets/models.json # editable model catalog (used by Step 2's Model Hub)
```

## First-time build

The Gradle wrapper JAR is intentionally not committed (binary blob). Generate
it once with any Gradle 8.10+ install, or let the GitHub Actions workflow do it:

```bash
gradle -p android-app wrapper --gradle-version 8.10
```

Then (with `ANDROID_NDK_HOME` pointing at an NDK, e.g. 26.1.10909125):

```bash
cd android-app
./gradlew :app:assembleDebug
```

`CMakeLists.txt` applies `patches/*.diff` to the `third_party/llama.cpp`
submodule automatically (idempotently) at configure time, so a plain Gradle
build works on a fresh checkout. You can also apply them explicitly with
`bash scripts/apply-patches.sh`.

## Cloud build

GitHub Actions (`.github/workflows/build-android-apk.yml`) builds
`app-debug.apk` on `ubuntu-latest` and uploads it as an artifact.

## Engine defaults

- 4 CPU threads, 4096 context (Big.LITTLE-safe, OOM-safe on 6 GB phones)
- All standard GGUF quants (Q4_K_M, IQ2_XS, Q2_K, ...) plus patched LittleBit Q1_0
