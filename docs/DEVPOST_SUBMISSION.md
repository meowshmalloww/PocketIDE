# PocketIDE Arm Devpost Submission Pack

This file contains paste ready copy, media guidance, and the final submission checklist for the Arm AI Optimization Challenge. The public claims use the completed schema 9 physical device reports from July 18, 2026.

## General information

### Project name

```text
PocketIDE
```

### Elevator pitch

```text
PocketIDE turns an Arm powered phone into a private offline IDE where a local AI agent writes, runs, tests, debugs, and repairs real code without a server.
```

### Track

```text
Mobile AI
```

## Project story

Paste everything inside the following block into **About the project**.

```markdown
## Inspiration

A phone is the computer people carry everywhere, including during travel, field work, network outages, and urgent situations when a laptop may not be available. Most mobile coding tools are limited editors or depend on a remote server. I wanted to learn whether an Android phone could become a private, useful development environment even without reliable internet access.

PocketIDE began with one question: can a small local model write code, run it on the same phone, inspect the result, and repair problems without sending the project anywhere?

## What it does

PocketIDE is a native Android IDE with a fully local AI coding agent. It can create and update multiple project files, explain code, plan an implementation, inspect execution errors, and attempt repairs. The model, project, chat history, and execution workflow can remain on the phone.

PocketIDE provides:

* Local GGUF inference through llama.cpp and compatible PTE inference through ExecuTorch
* Interactive Python execution with real terminal input
* Local execution for JavaScript, a compatible TypeScript subset, Lua, SQL, Android shell scripts, and BeanShell Java scripts
* Device local browser previews for HTML, CSS, JavaScript, and simple TypeScript projects
* Multiple project files and local AI chat history
* An Android hardware bridge for device information, battery, network, storage, sensors, location, vibration, flashlight, speech, notifications, and sandboxed files
* Quick, Deep, and Sustained benchmark modes with an in app summary, readable report, and JSON evidence

The final output is an installable open source Android application plus reusable benchmark, prompt, runtime, and validation workflows for building local AI developer tools on Arm phones.

## Useful without normal infrastructure

PocketIDE can help when a developer, student, field worker, or repair technician needs to create a small tool immediately but only has a phone. A user could build a checklist, sensor reader, storage monitor, device health report, local information page, or hardware control utility during a network outage or another constrained situation.

This is more than offline chat because generated programs can accept terminal input and communicate with available Android hardware. PocketIDE is a development tool for urgent and field situations. It is not a certified emergency, medical, or safety system.

## How I built it

I built PocketIDE as a native Android application using Kotlin and Jetpack Compose.

GGUF models run through llama.cpp. Compatible PTE models run through ExecuTorch. Python uses CPython through Chaquopy. Other local runtimes include Rhino, LuaJ, BeanShell, Android SQLite, Android shell, and NanoHTTPD for loopback browser previews.

PocketIDE does not infer acceleration from a model extension or library name. The current GGUF result is a CPU result. The runtime selected an Armv8.2 dot product native library from the phone CPU features, with zero GPU layers. I do not claim NPU, GPU, KleidiAI kernel, or ExecuTorch delegate acceleration without runtime proof.

## Arm optimization and measured result

PocketIDE treats mobile optimization as a device and model specific measurement problem. More threads are not automatically faster or more efficient on a phone. The corrected benchmark recreates the native llama.cpp context for every configured thread profile, excludes warmups, confirms the finalists, and saves the winning profile for the exact device, model file, runtime version, and selected Arm native library.

I measured Qwen2.5 Coder 1.5B Instruct Q4_0 GGUF on an LGE LM Q620 with a Qualcomm SM6150 processor and Android 12. The model file was 1,016.8 MB. The Deep benchmark selected two configured threads instead of the original four thread heuristic.

| Metric | Four thread heuristic | Calibrated two thread profile | Change |
| --- | ---: | ---: | ---: |
| Median decode throughput | 8.12 tok/s | 11.73 tok/s | 44.4 percent faster |
| Median time to first token | 132 ms | 87 ms | 34.1 percent lower |
| Mean process CPU time per output token | 445.6 ms | 169.4 ms | 62.0 percent lower |
| Mean maximum sampled process PSS | 1,780.7 MB | 1,915.5 MB | 7.6 percent higher |

The CPU time per token values are derived from the process CPU time and output token counters in the same Deep report. They measure process CPU work, not battery energy. The PSS result is reported as a tradeoff rather than hidden.

The native llama.cpp microbenchmark at the selected profile measured 46.82 prompt processing tokens per second for pp128 and 11.88 generation tokens per second for tg32.

In a separate Sustained run, the selected profile measured 11.48 tok/s compared with 8.20 tok/s for four threads, a 40.0 percent improvement. First half and second half medians were 11.37 and 11.65 tok/s. During the sustained segment, battery temperature moved from 28.1 C to 28.9 C and Android reported no thermal throttling state.

These results are real physical device measurements, but their scope matters. Decode timing begins after the first emitted token. Time to first token excludes model loading and prompt formatting, and the fixed GGUF prompt can reuse the runtime prefix cache after warmup. The thread values are load configured because the wrapper does not expose the native live worker count. Android energy counters cover the whole phone, so I do not present them as app only power.

PocketIDE also reads architecture, layer, embedding, attention head, and KV head information from GGUF metadata before loading. A memory planner selects a shared context, batch size, and output cap from Android memory information. Normal chat and benchmarks use the same plan, and an unsafe cold load is refused before Android can terminate the process.

PTE results remain separate because the exported model and ExecuTorch runtime control context, workers, and delegates. PocketIDE does not present a comparison between different GGUF and PTE models as an optimization result.

## What changed during the challenge

During the challenge period I significantly expanded and corrected PocketIDE. I added device specific GGUF calibration, separate Quick, Deep, and Sustained evidence protocols, native llama.cpp microbenchmarks, readable dashboard cards, JSON reports, thermal and whole device energy evidence, model aware memory planning, process exit diagnostics, cancellable generation, PTE support, and a model download catalog.

I also expanded and tested terminal input, multiple file workflows, browser preview, local language runtimes, and the Android hardware bridge. After finding that an earlier benchmark changed a generation argument without recreating the native context, I invalidated the old calibration and created schema 9, which reloads the context for every configured thread profile. The published result comes only from the corrected protocol.

## Challenges

The hardest challenge was fitting a model of roughly one gigabyte into a complete Android development environment on a phone with limited memory. Model weights, KV state, native buffers, language runtimes, the editor, and Android all compete for physical memory.

The second challenge was benchmark integrity. It was easy to display a thread number or library name, but harder to prove where a control was applied and what the runtime actually exposed. I chose to label configured values, timing boundaries, cache behavior, and whole device counters directly in the report.

Interactive execution also required more than showing standard output. PocketIDE needed to accept input while a program was running, preserve multiple files, report source locations, preview browser projects locally, and expose Android hardware without pretending every language is a complete desktop toolchain.

## What I learned

I learned that mobile AI must be measured on the target device. On this phone, two llama.cpp threads were much faster than four and required far less process CPU work per generated token. I also learned that native memory pressure and reliable model loading matter as much as headline throughput.

Small coding models perform better when the application gives them compact context, precise runtime capabilities, strict output structure, and a direct run and repair workflow.

## Why PocketIDE should win

PocketIDE turns local AI into a complete developer workflow instead of a chat demonstration. One Arm powered phone performs inference, creates project files, executes code, accepts terminal input, previews local web projects, communicates with phone hardware, and measures its own real inference behavior.

The project combines privacy, offline reliability, developer experience, and reproducible optimization evidence. Its benchmark and reporting work are reusable by other Android developers, and its hardware bridge demonstrates a practical reason to place the coding agent on the device it is programming.

## Build, run, and validate

1. Clone `https://github.com/meowshmalloww/PocketIDE`.
2. Open the project in Android Studio with the Android SDK, or run `./gradlew assembleDebug`. On Windows, run `.\gradlew.bat assembleDebug`.
3. Install `app/build/outputs/apk/debug/app-debug.apk` on an Arm64 Android device running API 26 or newer.
4. In PocketIDE Settings, download or import a compatible GGUF model. A compatible PTE model also requires its tokenizer file.
5. Select the model, open AI Assistant, and use Code mode to create a project.
6. Open Terminal to run the generated program. Terminal input remains available while an interactive program is running.
7. To validate offline behavior, install the model first, enable airplane mode, and generate and run code.
8. To validate optimization, open the benchmark dialog. Run Quick first, then Deep and Sustained on an unplugged and cooled phone. Review the in app Summary and copy the full report and JSON.
9. Repository verification commands are `./gradlew testDebugUnitTest`, `./gradlew lintDebug`, `./gradlew assembleDebug`, and `./gradlew connectedDebugAndroidTest` with a connected test device or emulator.

The repository is public under Apache License 2.0. The model files are not stored in the repository because they are approximately one gigabyte each.

## What is next

I plan to test more Arm powered phones and model sizes, publish a larger physical device benchmark matrix, improve memory planning, evaluate newer ExecuTorch exports, add more complete language runtimes, and expand camera, audio, sensor, connectivity, and device control capabilities in the Android hardware bridge.

I also plan to add optional cloud model providers. Users will be able to keep the complete private local workflow or choose a larger cloud model when connectivity is available. A future hybrid mode could keep execution and sensitive files on the phone while allowing the user to control exactly which context is sent to a provider. Local mode will remain a complete option.
```

## Built with

Enter the tags that Devpost recognizes, up to 25:

```text
Android, Kotlin, Jetpack Compose, Arm64, Android NDK, llama.cpp, ExecuTorch, GGUF, PTE, Qwen2.5 Coder, Llama 3.2, SpinQuant, Chaquopy, CPython, Rhino, LuaJ, BeanShell, SQLite, NanoHTTPD, Hugging Face, Gradle, Material 3, JSON
```

## Try it out links

Add these in this order:

1. Source code: `https://github.com/meowshmalloww/PocketIDE`
2. APK: add the public GitHub Release or Google Drive download link after a clean install test
3. Video: add the public YouTube link after upload
4. Benchmark artifacts: add a direct repository link to the final schema 9 text report and JSON

Do not add a private link, placeholder, or APK that still needs permission approval. Judges must have free access through the judging period.

## Project media

Use five clear images. Crop each to 3:2 and keep it under 5 MB.

1. **Hero:** PocketIDE AI Assistant beside generated code. Caption: `A fully local AI coding workflow on an Arm Android phone.`
2. **Offline proof:** Airplane mode visible with the selected local model. Caption: `Qwen2.5 Coder 1.5B running locally without an inference server.`
3. **Execution:** Terminal showing interactive input, `96`, and `Goodbye`. Caption: `Generated Python accepts real terminal input on the phone.`
4. **Hardware:** Device health output and the `Scan complete` toast. Caption: `Generated code reads real Android hardware through PocketIDE.`
5. **Optimization:** The schema 9 benchmark Summary showing 11.73 tok/s, 8.12 tok/s, and 44.4 percent. Caption: `Real physical device calibration selected two threads and improved decode throughput by 44.4 percent.`

Do not use a screenshot of an old benchmark, an incomplete run, or the report section with the known final resource plan display mismatch.

## Additional information selections

### Hardest parts

Select:

* Finding compatible hardware or cloud instances
* Understanding Arm specific guidance
* Measuring performance
* Improving model speed or latency
* Reducing model size or memory usage
* Debugging runtime or compatibility issues
* Knowing which tools to use

### What would have made it easier

Select:

* More Arm specific optimization guidance
* More benchmarking examples
* Easier access to Arm based hardware or cloud instances
* Clearer setup instructions
* More guidance on what judges were looking for

### Did this challenge change your likelihood of building on Arm in the future?

```text
Much more likely
```

### How likely are you to continue developing this project?

```text
Extremely likely
```

### What is one thing Arm could improve?

```text
Provide one maintained Android reference application and compatibility matrix that maps each supported model export to the exact ExecuTorch release, backend, device requirements, and reproducible memory, latency, thermal, and power benchmark steps.
```

## Video recording plan

### Record only after this gate passes

1. Install the exact APK that judges will download.
2. Complete two cold local AI generations without an app restart.
3. Run the interactive terminal example and confirm it prints `96` and `Goodbye`.
4. Run the hardware scanner and confirm it prints real values, survives a missing sensor, vibrates, and shows the toast.
5. Open the schema 9 benchmark Summary and verify that the cards match the saved report.
6. Put the final report and JSON in the public repository.

### Phone and recording setup

1. Restart the phone, close other applications, let it cool, and charge it above 70 percent.
2. Unplug it before recording benchmark evidence.
3. Turn on Do Not Disturb and remove personal notifications, file names, and account information.
4. Download the model first, then enable airplane mode. Keep the airplane icon visible when the demo begins.
5. Use the phone screen recorder at 1080p and 30 frames per second with touch indicators enabled.
6. Record short clips separately. Place the portrait recording in a 1920 by 1080 video with large captions so judges can read it on a desktop.
7. Add narration after recording. Use no music unless you own it and have permission. The safest choice is narration with no music.
8. If generation is sped up, display `2x playback, real on device generation` on screen. Do not replace output with unrelated prepared code.

### Timeline and shots

Keep the finished video around 2 minutes 35 seconds.

| Time | Show | Say |
| --- | --- | --- |
| 0:00 to 0:12 | Physical LGE phone, airplane mode, PocketIDE home | This Arm64 Android phone is offline. PocketIDE is a local AI IDE, not a remote editor. |
| 0:12 to 0:25 | Settings with Qwen2.5 Coder 1.5B Q4_0 selected | The model is a roughly one gigabyte GGUF file and inference stays on this phone. |
| 0:25 to 0:58 | Paste the hardware scanner prompt and show real generation, optionally at 2x | The local agent is creating a Python device health scanner with PocketIDE hardware calls. |
| 0:58 to 1:20 | Open the generated file, run it, show device values and toast | PocketIDE executes the code with CPython. It reads real battery, network, storage, and sensor information, then vibrates and shows a toast. |
| 1:20 to 1:38 | Run the prepared interactive calculator and enter `multiply`, `12`, `8`, `q` | The terminal accepts input while the generated program is running. It prints 96 and exits normally. |
| 1:38 to 1:52 | Briefly show multiple files and a device local browser preview | Projects can contain multiple files, and web projects preview through a loopback address on the phone. |
| 1:52 to 2:22 | Scroll the benchmark Summary cards | The corrected Deep benchmark selected two configured threads. Median decode improved from 8.12 to 11.73 tokens per second, while median time to first token fell from 132 to 87 milliseconds. |
| 2:22 to 2:35 | Return to AI Assistant and show GitHub address | PocketIDE makes private, local software creation available when a laptop, server, or reliable network is not. |

### Narration script

```text
This LGE LM Q620 is an Arm64 Android phone, and it is in airplane mode. PocketIDE is a local AI development environment, not a remote editor.

The selected model is Qwen2.5 Coder 1.5B Q4_0, a roughly one gigabyte GGUF file. The prompt, source code, project, and inference remain on this phone.

I am asking the local agent to create a device health scanner. This generation is shown at two times playback, but it is the real output from this device. PocketIDE creates the Python file and runs it with CPython. The program reads real battery, network, storage, and accelerometer information. A missing sensor cannot stop the report. It then vibrates the phone and shows a toast.

This second generated program proves interactive execution. I enter multiply, twelve, eight, and quit. The terminal prints ninety six and the program exits normally. PocketIDE also supports multiple project files and local browser previews.

Optimization is measured rather than assumed. On this phone, the corrected Deep benchmark recreated the native context for each profile and selected two configured threads. Median decode improved from 8.12 to 11.73 tokens per second, a 44.4 percent increase. Median time to first token fell from 132 to 87 milliseconds. A sustained run measured 11.48 tokens per second with no second half throughput loss.

These are CPU results using the loader selected Armv8.2 dot product library. I do not claim NPU, GPU, or KleidiAI acceleration. PocketIDE exports the complete report and JSON, and the evidence summary is public.

PocketIDE turns the Arm phone already in your pocket into a private software creation tool when a laptop, server, or reliable network is unavailable.
```

### Demo prompts

Hardware scanner:

```text
Create one Python file named phone_check.py. Build a device health scanner using the PocketIDE hardware object. Read getDeviceInfo, batteryLevel, isCharging, networkType, storageFree, and readSensor with accelerometer for 1000 milliseconds. Give every hardware call its own try except block so a missing sensor cannot stop the program. Print a clear report, vibrate for 200 milliseconds, and show a toast containing Scan complete. Use no external packages and keep the complete program under 45 lines.
```

Interactive terminal:

```text
Create one Python file named main.py. Build an interactive calculator that repeatedly asks the user to enter plus, minus, multiply, divide, power, or q. Ask for two numbers, validate numeric input, handle division by zero, print the result, and return to the menu. Entering q must print Goodbye and exit. Use input only, use no external packages, and keep the complete program under 50 lines.
```

### YouTube upload

Title:

```text
PocketIDE: A Fully Local AI IDE on an Arm Android Phone
```

Description:

```text
PocketIDE is an open source Android IDE where a local AI model creates, runs, tests, and repairs code without an inference server.

Demo device: LGE LM Q620, Qualcomm SM6150, Android 12
Model: Qwen2.5 Coder 1.5B Instruct Q4_0 GGUF
Corrected schema 9 result: 11.73 tok/s at two configured threads versus 8.12 tok/s at the previous four thread heuristic
Runtime path: CPU with loader selected Armv8.2 dot product library, zero GPU layers
No NPU, GPU, or KleidiAI kernel acceleration is claimed

Source code and full benchmark evidence:
https://github.com/meowshmalloww/PocketIDE
```

Set visibility to **Public**, add accurate English captions, and keep the video below 3 minutes. A good thumbnail is a clean phone screenshot with the text `Offline AI IDE` and `44% faster decode`. Do not add third party logos or copyrighted music.

## Final submission checklist

Do not submit until every item is true:

* The public GitHub main branch contains the current source, README, Apache 2.0 license, build instructions, and schema 9 evidence.
* The public README no longer shows the invalid older 11.67 tok/s comparison.
* A clean judge can download the APK without requesting access.
* The model download and tokenizer instructions are complete.
* The exact public APK passes two cold generations, terminal input, hardware execution, and benchmark Summary checks.
* The demo video is public and shorter than 3 minutes.
* All gallery screenshots come from the final APK and real device runs.
* The submission explains the significant updates made during the challenge period.
* No NPU, GPU, KleidiAI, native live worker count, or app only battery claim appears without proof.
* There are no placeholder links.
