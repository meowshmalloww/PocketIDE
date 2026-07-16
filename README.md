# PocketIDE

PocketIDE is an Android IDE with a fully local AI coding assistant. It can generate multi-file projects, run supported languages, accept terminal input, inspect errors, and repair failed code without sending prompts or source code to a server.

Built for the [Arm AI Optimization Challenge 2026](https://arm-ai-optimization-challenge.devpost.com/) Mobile AI track.

## What it does

- Runs GGUF language models locally through llama.cpp; ExecuTorch `.pte` support is also included.
- Generates and updates multiple project files from one request.
- Executes code on the phone and displays stdout, stderr, source locations, and runtime status.
- Supports interactive terminal input for programs such as Python menus.
- Opens HTML, CSS, JavaScript, and simple TypeScript projects in the phone browser through `127.0.0.1`.
- Exposes Android features such as sensors, flashlight, vibration, battery, GPS, TTS, notifications, files, and camera information to supported scripts.
- Stores projects, model configuration, and AI chat history locally.

AI modes:

- **Code:** generate or edit project files.
- **Ask:** explain code without changing files.
- **Plan:** produce an implementation plan.
- **Swarm:** reuse the active local model sequentially as Architect, Coder, and Validator. It does not require three models.

## Arm optimization

PocketIDE measures real inference performance instead of assuming that more CPU threads are faster. Its benchmark runs equal warmup and measured generations across 1–4 llama.cpp thread profiles, selects the fastest measured profile, and saves the result for that exact phone and model file.

Normal inference also responds to thermal state, battery level, and memory pressure by reducing thread count or generation length when needed.

### Physical-device result

Tested on an LGE LM-Q620 running Android 12 with Qwen2.5-Coder 1.5B Instruct Q4_0:

| Metric | Previous 4-thread heuristic | Calibrated 2-thread profile | Change |
|---|---:|---:|---:|
| Decode throughput | 8.24 tok/s | 11.67 tok/s | **+41.6%** |
| Average TTFT | 115 ms | 104 ms | **-9.6%** |
| Average steady process PSS | 1880 MB | 2014 MB | +7.1% |

Benchmark protocol: one warmup and three measured 96-token generations per profile, 16 total runs. The phone loaded `librnllama_v8_2_dotprod.so`, selected at runtime from its Arm CPU features.

The selected profile maximizes decode speed. It is not claimed to minimize memory at the same time.

## Language support

| Language | Support |
|---|---|
| Python | CPython 3.11, interactive `input()`, tracebacks, sibling-file imports |
| JavaScript | Rhino with compatibility preprocessing and terminal input |
| Lua | LuaJ with `io.read()` terminal input |
| SQL | Android SQLite |
| Shell | Android `/system/bin/sh` with a timeout |
| TypeScript | Compatibility type stripping, then Rhino; not a complete TypeScript compiler |
| Java | BeanShell scripting; not Android `javac` |
| HTML/CSS/JS/simple TS | Device-local browser preview |
| Kotlin, Dart, YAML, Markdown, JSON | Editing and syntax highlighting only |

PocketIDE edits and highlights 14 formats, but it does not claim complete compilers for all of them.

## Android hardware bridge

Python, JavaScript, TypeScript, Lua, and BeanShell scripts executed inside PocketIDE receive a global `hardware` object. Common methods include:

```text
hardware.getDeviceInfo()
hardware.batteryLevel()
hardware.readSensor("accelerometer", 1000)
hardware.setFlashlight(true)
hardware.vibrate(200)
hardware.toast("Hello")
hardware.speak("Hello")
hardware.notify("Title", "Message")
hardware.getLocation(5000)
hardware.listCameras()
hardware.readFile("data.txt")
hardware.writeFile("data.txt", "content")
hardware.startServer(8080)
```

Browser-preview pages do not receive the native `hardware` object. `listCameras()` reports camera IDs and capabilities; photo and video capture are not implemented.

## Build

Requirements:

- Android Studio with the Android SDK
- Android API 26 or newer
- An `arm64-v8a` device for real inference, or an `x86_64` emulator for UI and execution tests
- A compatible `.gguf` model, or a `.pte` model and tokenizer

```bash
git clone https://github.com/meowshmalloww/PocketIDE.git
cd PocketIDE
./gradlew assembleDebug
```

On Windows:

```powershell
.\gradlew.bat assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it with Android Studio, `installDebug`, or ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Configure a model

1. Copy or download a compatible model onto the phone.
2. Open **Settings → On-Device Model → Add Model**.
3. Select the `.gguf` or `.pte` file with Android's document picker.
4. For `.pte`, also select its tokenizer file.
5. Select the imported model as active.

Models are not bundled in the repository or APK.

## Try it

Generate and run an interactive Python project:

```text
Build a two-file Python expense tracker using main.py and storage.py. Include an
interactive terminal menu, input validation, JSON persistence, and summaries by
category. Do not create a calculator.
```

Generate a local browser project:

```text
Build a three-file emergency checklist using index.html, styles.css, and app.ts.
Include priorities, filters, completion statistics, and localStorage persistence.
Use no frameworks or network access.
```

To calibrate inference, open AI Chat, tap the benchmark button, run the suite, and export the text and JSON reports.

## Verification

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
./gradlew connectedDebugAndroidTest
```

Current verification:

- 114 JVM/Robolectric tests passed
- 9 Android instrumented tests passed
- Debug lint passed
- Debug APK assembly passed

Physical-device testing instructions are in [docs/PHYSICAL_DEVICE_TEST_PLAN.md](docs/PHYSICAL_DEVICE_TEST_PLAN.md).

## Main technologies

- Kotlin and Jetpack Compose
- llama.cpp through KotlinLlamaCpp
- ExecuTorch
- CPython through Chaquopy
- Rhino, LuaJ, BeanShell, SQLite, and NanoHTTPD

## License

Apache License 2.0. See [LICENSE](LICENSE).
