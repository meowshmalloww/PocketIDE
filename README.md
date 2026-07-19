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

PocketIDE measures real inference performance instead of assuming that more CPU threads are faster. For GGUF, **Quick** tests profiles up to 4 llama.cpp threads plus the device heuristic; **Deep evidence** screens up to 8 and confirms the finalists. **Sustained evidence** repeats real generations on the selected profile and reports first-half versus second-half speed retention, battery temperature, Android thermal state, and public fuel-gauge energy when the phone exposes it. The winner is saved for that exact phone, model file, runtime version, and loader-selected Arm native library. PTE models use a separate fixed-backend ExecuTorch protocol because thread and delegate choices are embedded during export.

For GGUF files, PocketIDE reads architecture, context, layer, embedding, attention head, and KV head metadata directly from the GGUF header before native loading. This keeps memory planning model aware across different model names and quantizations, with a conservative fallback when metadata is unavailable.

GGUF runs also include llama.cpp's real native prompt-processing (`pp128`) and token-generation (`tg32`) microbenchmark. PTE runs use exact counters emitted by ExecuTorch and reset KV state between repetitions. The in-app Summary shows video-ready cards for decode speed, TTFT, selected CPU profile, process PSS, sustained retention, device energy, temperature, and native throughput. Report and JSON retain the full evidence. Warmups are excluded and unverified acceleration claims are marked.

Energy values come from Android `BatteryManager` and describe the whole phone, not PocketIDE alone. Charging runs hide energy attribution. A device may expose energy, charge, current, or none of those counters.

Normal inference also responds to thermal state, battery level, and memory pressure by reducing thread count or generation length when needed.

Before native loading, PocketIDE selects one shared context, batch size, and output cap from Android system memory. Normal chat and benchmarks reuse that profile so a benchmark cannot silently load a much smaller context than the real coding workflow. Unsafe cold loads are stopped before native allocation, and Android process exit evidence is included in later reports when available.

### Physical-device evidence

Schema 9 was measured on an LGE LM-Q620 running Android 12 with Qwen2.5-Coder 1.5B Instruct Q4_0 GGUF. The corrected protocol recreates the native context for every load-configured thread profile and invalidates older calibrations that did not reload the context.

| Metric | Previous 4-thread heuristic | Calibrated 2-thread profile | Change |
|---|---:|---:|---:|
| Median decode throughput | 8.12 tok/s | 11.73 tok/s | **+44.4%** |
| Median TTFT | 132 ms | 87 ms | **-34.1%** |
| Mean process CPU time per output token | 445.6 ms | 169.4 ms | **-62.0%** |
| Mean maximum sampled process PSS | 1780.7 MB | 1915.5 MB | +7.6% |

The CPU-time result is derived from process CPU counters in the same Deep report and is not a battery-energy claim. Decode timing starts after the first emitted token. TTFT excludes model loading and prompt formatting, and the fixed benchmark prompt may reuse the runtime prefix cache after warmup. Thread values are load configured because the wrapper does not expose the native live worker count. The run used the loader-selected `librnllama_v8_2_dotprod.so` CPU library with zero GPU layers; PocketIDE does not claim NPU, GPU, KleidiAI-kernel, or ExecuTorch-delegate acceleration without runtime proof.

The separate Sustained run retained 102.5% of first-half throughput, with the selected profile at 11.48 tok/s versus 8.20 tok/s for four threads. See [the evidence summary and protocol](docs/BENCHMARK_EVIDENCE.md).

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

For a smaller phone-only APK that excludes x86 emulator libraries:

```powershell
.\gradlew.bat --project-prop "pocketide.abi=arm64-v8a" assembleDebug
```

The default build continues to include `arm64-v8a` and `x86_64` so emulator verification still works.

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

To calibrate inference, open AI Chat, tap the benchmark button, choose **Quick**, **Deep evidence**, or **Sustained evidence**, and scroll the Summary, Report, or JSON directly in the app. Run Quick first, unplug the phone, let it cool, and then run Sustained for the cleanest thermal and device-energy comparison. Both export formats can also be copied.

## Verification

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
./gradlew connectedDebugAndroidTest
```

Current verification:

- 162 JVM/Robolectric tests passed with no failures or skips
- 16 Android instrumentation tests passed on the Android emulator, including CPython input and imports, JavaScript, the TypeScript subset, Lua, SQL, Shell, BeanShell Java, terminal input, localhost preview, the Android hardware bridge, benchmark cards, and benchmark cancellation
- Debug lint completed with 0 errors
- Debug and release APK assembly passed

The emulator validates application behavior, not Arm inference speed, power, sensors, or thermal behavior. Those final measurements must be captured on the target physical phone.

Physical-device testing instructions are in [docs/PHYSICAL_DEVICE_TEST_PLAN.md](docs/PHYSICAL_DEVICE_TEST_PLAN.md).

The short recording prompts and order are in [docs/DEMO_SCRIPT.md](docs/DEMO_SCRIPT.md). Paste ready submission text is in [docs/DEVPOST_SUBMISSION.md](docs/DEVPOST_SUBMISSION.md).

## Main technologies

- Kotlin and Jetpack Compose
- llama.cpp through KotlinLlamaCpp
- ExecuTorch
- CPython through Chaquopy
- Rhino, LuaJ, BeanShell, SQLite, and NanoHTTPD

## License

Apache License 2.0. See [LICENSE](LICENSE).
