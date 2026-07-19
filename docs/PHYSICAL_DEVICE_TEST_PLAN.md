# PocketIDE Physical Device Test Plan

Use this checklist on the actual Arm phone before treating the APK as submission-ready. Emulator tests prove app correctness, not mobile inference performance.

## 1. Install and configure

1. Install the debug APK and launch PocketIDE.
2. Open **Settings → On-Device Model → Add Model**.
3. Choose a `.gguf` file, or choose a `.pte` file followed by its tokenizer file.
4. Confirm the model name fills automatically and leave **Prompt template** on **Auto detect** unless the model card requires a specific format.
5. Return to AI Chat and ask a short question. A successful answer should show tokens/second and TTFT below it.

Start with a 0.5B–1.5B 4-bit GGUF model. A 2B model may work, but memory and speed depend on the phone and context size.

## 2. IDE and language checks

Run one success case and one error case for each executable path:

| Path | Success example | Error example | Expected capability |
|---|---|---|---|
| JavaScript | `console.log(2 + 3);` | `console.log(;` | Embedded runtime |
| Lua | `print(2 + 3)` | `print(` | Embedded runtime |
| SQL | `SELECT 2 + 3;` | `SELECT FROM;` | Embedded runtime |
| Shell | `echo hello` | `command_that_does_not_exist` | Device shell runtime |
| TypeScript | `const x: number = 5; console.log(x);` | `const x: number = ;` | Compatibility subset |
| Python | `print(2 + 3)` | `print(` | CPython 3.11 |
| Java | `System.out.println(2 + 3);` | `System.out.println(;` | BeanShell compatibility |

Kotlin, Dart, YAML, Markdown, and JSON are editor-only. HTML and CSS use browser preview. Do not present these as bundled compilers.

## 3. Browser preview

1. Create `index.html`, `style.css`, and optionally `main.js` or a simple `main.ts`.
2. Tap the browser icon in Terminal.
3. Confirm the browser opens a `http://127.0.0.1:8765/`-style URL.
4. Edit the project and reopen preview to refresh the in-memory snapshot.

The preview server is device-local and rejects traversal paths. TypeScript preview strips common type annotations; it is not a full `tsc` build.

Browser preview does not expose PocketIDE's native `hardware` object. Test hardware calls by running a supported script inside PocketIDE. `hardware.listCameras()` verifies camera discovery/capabilities only; photo and video capture are outside the current bridge.

## 4. Android hardware bridge

Grant Android permissions when requested, then test only hardware present on the phone:

```javascript
console.log(hardware.getDeviceInfo());
hardware.toast("PocketIDE hardware bridge works");
hardware.vibrate(100);
console.log(hardware.listSensors());
```

Then test flashlight, notification, location, Bluetooth, camera listing, TTS, clipboard, and sandbox file APIs individually. A permission denial must return an error value rather than crash the app.

## 5. AI history and agent loop

1. Send two AI messages, tap **History**, and confirm the conversation appears.
2. Tap **New**, send another message, then reopen the first conversation.
3. Restart PocketIDE and confirm both sessions remain.
4. In Code mode, generate a runnable file and run it.
5. Introduce a syntax error and use the repair action. Confirm Terminal shows error type, line/column when available, warnings, and duration.

## 6. Hackathon benchmark protocol

Open AI Chat and tap its benchmark icon:

- **Quick** tests every available profile up to 4 llama.cpp decode threads plus the device heuristic, with 1 warmup and 3 measured 96-token generations per profile.
- **Deep evidence** screens every available profile up to 8 threads with 1 warmup and 1 measured 128-token generation, then confirms up to three finalists with another warmup and 4 measured runs each.
- **Sustained evidence** uses the saved Quick winner when available, performs 1 warmup plus 8 measured 128-token generations, and compares the first and second halves for throughput retention. Without a saved winner it first performs a short profile screen.
- With a PTE model, Quick runs 1 warmup plus 3 measured generations; Deep runs 1 warmup plus 5 measured generations. ExecuTorch's exact generated/prompt token counters are recorded, KV state is reset before each run, and worker count is marked as not exposed.
- With a PTE model, Sustained runs 1 warmup plus 8 measured generations on the fixed exported backend. It does not claim control over context, workers, NPU, or delegates that the Android API does not expose.
- GGUF modes finish with a real llama.cpp native microbenchmark that separately reports `pp128` prompt processing and `tg32` generation throughput. PTE does not claim this GGUF-only native test.

The measured winner is saved for normal chat on that exact device, model file, runtime version, and selected Arm native library. Scroll through **Summary**, **Report**, and **JSON** in the app, then copy both export formats. The Summary cards show the main video evidence without hiding the complete report. The report labels sampled memory, excludes warmups, includes variability and thermal data, and does not claim that KleidiAI or an NPU was used without runtime proof.

For GGUF, confirm that the report says `Architecture source: GGUF metadata`. This proves that context and KV planning came from the model header rather than only from its filename. A fallback source is acceptable for older or malformed files, but it must be disclosed in the report.

For energy evidence, unplug the phone and keep the screen brightness and background workload fixed. PocketIDE reads public Android energy, charge, current, voltage, temperature, and thermal counters. These counters describe the whole phone, not only the app, and unsupported counters are reported as unavailable. Charging runs intentionally do not receive an energy score.

If Android reports low memory, the suite stops cleanly instead of starting another generation. Close other apps, cool the phone, and run Quick before Deep or Sustained.

For a defensible before/after comparison:

1. Use the same physical phone, Android build, model family, context size, battery range, charging state, and room temperature.
2. Close background apps and let the phone cool before each set.
3. Run at least three complete suites per configuration.
4. Compare an actual baseline model/export with the optimized model/export—for example FP16 versus INT4, or a generic export versus an Arm/KleidiAI-enabled ExecuTorch export.
5. Report median tokens/second and TTFT, native prompt/decode throughput, model file size, max sampled process PSS, thermal status, coefficient of variation, and any failures. Do not mix emulator and phone numbers.
6. Keep the raw JSON files, screenshots, exact model hashes, device model, and build commit with the submission evidence.

The app measures and applies a decode thread count, but the current wrapper does not expose CPU affinity or separate prompt-processing and single-token decode thread pools. Native KV-cache precision is controlled by the model/runtime; PocketIDE reports a conservative memory estimate rather than claiming an unapplied INT8 cache.

## 7. Release gate

Do not submit until all of these are true:

- `assembleDebug`, `lintDebug`, and `testDebugUnitTest` pass.
- The APK installs and launches on an Arm64 phone.
- A real model loads, generates twice, unloads on app exit, and survives screen rotation/backgrounding.
- Every executable language path has a success and error result.
- Browser preview and persistent AI history work after restart.
- The fixed benchmark finishes without thermal shutdown or out-of-memory errors.
- Quick and Sustained both finish while unplugged, and the dashboard, text report, and JSON agree on the selected profile and measured values.
- Raw before/after benchmark evidence is archived for the Devpost submission.
