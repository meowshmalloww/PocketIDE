# PocketIDE

A mobile AI IDE that runs **entirely on-device** — no cloud, no API keys, no network calls. AI generates code, executes it, catches errors, and repairs itself, all powered by on-device LLM inference optimized for Arm.

> **Arm AI Optimization Challenge 2026 — Track 3: Mobile AI**

## Features

### On-Device AI Inference

- **Dual model runtime support** — ExecuTorch (`.pte` models) and llama.cpp (`.gguf` models) via `kotlinllamacpp`
- **ExecuTorch integration** — `LlmModule` wrapper (`ExecutorchLlmRunner`) with coroutine-friendly load/generate/stop/close lifecycle, model instance reuse across requests, and mutex-serialized native calls
- **llama.cpp integration** — `LlamaHelper` wrapper (`LlamaCppRunner`) for GGUF model files with SharedFlow-to-Channel event bridging, self-contained tokenizer (no separate tokenizer file needed)
- **Prompt templating** — Llama 3 / 3.2 Instruct, Qwen 2.5 / Qwen 3 ChatML, and Plain templates with system + history + user message formatting
- **Structured response parsing** — AI responds in PLAN / FILENAME / code-block format; parsed into actionable file operations
- **Autonomous self-repair loop** — generates code → executes it → captures stderr → sends error context back to AI → re-executes, up to configurable max iterations
- **Configurable inference** — temperature, max sequence length, prompt template, quantization level (INT4 / INT8 / FP32), all persisted via SharedPreferences
- **Live token streaming** — tokens appear in the chat panel as they are generated
- **Tokens-per-second display** — real-time generation speed shown under AI messages
- **Thinking indicator** — animated rotating icon while AI is processing
- **AI modes** — CODE (generate code), ASK (explain concepts), PLAN (detailed planning)
- **Model modes** — SINGLE (one model) or SWARM (multi-model orchestration)

### Code Execution — 7 Languages Fully Supported

| Language | Engine | Notes |
|---|---|---|
| **JavaScript** | Mozilla Rhino 1.7.15 | ES6→ES5 transpilation (let/const, arrow functions, template literals, for...of, default params), ES6+ polyfills (Object.assign, Array.find, String.includes, etc.), `console.log()` support, instruction-count infinite-loop protection, **full hardware bridge** |
| **TypeScript** | Rhino (via JS transpilation) | Type annotations stripped, then ES6→ES5 preprocessing applied, **full hardware bridge** |
| **Python** | Rhino (via JS transpilation) | Python-to-JS transpilation: `print()`, `range()`, `len()`, `input()`, `for...in`, `if/elif/else`, `while`, `def` functions, `try/except`, string methods, list operations, **full hardware bridge** |
| **Lua** | LuaJ 3.0.1 | Full Lua 5.2 standard library, `print()` capture, **full hardware bridge** |
| **SQL** | Android SQLite | In-memory temp database, SELECT/INSERT/UPDATE/DELETE/CREATE TABLE, formatted tabular output |
| **Java** | BeanShell 3.0 | Scripting mode, `System.out` capture, basic Java execution, **full hardware bridge** |
| **Shell** | `ProcessBuilder` | `/system/bin/sh`, 10-second timeout, stdout/stderr capture |

### 14 Language Definitions (Syntax Highlighting)

Python, JavaScript, TypeScript, Kotlin, Dart, SQL, HTML, CSS, Java, Lua, Shell, YAML, Markdown, JSON — all with custom syntax highlighting (keywords, strings, comments, numbers) and language-aware file icons in tabs.

### IDE Experience

- **Custom code editor** — `BasicTextField` with syntax highlighting, line numbers, horizontal/vertical scrolling, and synced gutter
- **Multi-file project support** — file explorer, tab bar with language icons, create/delete/save operations with unsaved-change tracking
- **File tabs** — VS Code-style tabs with active indicator, unsaved dot, language badge, close button
- **Project switcher** — create, switch, and delete projects with isolated file namespaces
- **AI chat panel** — message bubbles, scrollable code blocks, agent status badges, mode selectors (CODE/ASK/PLAN, SINGLE/SWARM), new chat button
- **Terminal panel** — collapsible, status indicators (running/passed/failed), run/repair buttons, auto-scroll, monospace output
- **Responsive layout** — landscape (split view with draggable panels) and portrait (tab-switched full screen) modes
- **Draggable panels** — explorer width, terminal height, AI overlay width all adjustable via drag handles
- **Extensions panel** — browse available extensions
- **Dark/light theme** — VS Code-inspired neutral palette with persisted preference
- **Settings screen** — on-device model config (`.pte` or `.gguf`), optimization toggles (power saving, thermal-aware, adaptive cores), agent repair iterations, context window size, code context injection, history summarization, sandbox language enable/disable

## Tech Stack

| Component | Technology |
|---|---|
| Language | Kotlin 2.2.10 |
| UI | Jetpack Compose + Material 3 |
| Code Editor | Custom `BasicTextField` + `SyntaxHighlighter` |
| On-Device AI (PTE) | ExecuTorch 1.0.0 (`LlmModule`) — XNNPACK + KleidiAI on Arm |
| On-Device AI (GGUF) | kotlinllamacpp (`LlamaHelper`) — llama.cpp backend |
| JS Execution | Mozilla Rhino 1.7.15 |
| Lua Execution | LuaJ 3.0.1 |
| Java Execution | BeanShell 3.0 |
| SQL Execution | Android SQLite |
| Shell Execution | `ProcessBuilder` (`/system/bin/sh`) |
| Navigation | Compose Navigation 2.8.5 |
| Persistence | SharedPreferences (config) + internal storage (files) |
| Build | Gradle + AGP 9.2.1 |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 36 |
| ABI Filters | `arm64-v8a` (target), `x86_64` (emulator testing) |

## Arm Optimization Pipeline

PocketIDE is built for the **Arm AI Optimization Challenge — Track 3: Mobile AI**. All inference runs locally on Arm-powered devices (arm64-v8a) with optimizations at every layer:

### XNNPACK + KleidiAI Integration

| Layer | Technology | Role |
|---|---|---|
| **Model export** | ExecuTorch + KleidiAI | INT4/INT8 quantization with KleidiAI-optimized kernels baked into `.pte` |
| **Runtime backend** | XNNPACK | Arm NEON-optimized inference kernels, bundled in ExecuTorch AAR |
| **CPU detection** | `BackendInfo` | Runtime detection of NEON, i8mm, dotprod, SVE2, SME2 capabilities |
| **Thread tuning** | `BackendInfo.optimalThreadCount` | big.LITTLE-aware thread count to avoid thermal throttling |

### How It Works

1. **At model export time (Python):** The model is exported to `.pte` with `--enable_kleidiai` and XNNPACK delegate. KleidiAI provides optimized INT4/INT8 matmul kernels for Arm Cortex-A and Cortex-X cores.

2. **At app startup:** `BackendInfo` reads `/proc/cpuinfo` to detect Arm CPU features:
   - **NEON** — baseline SIMD for all arm64-v8a
   - **i8mm** — INT8 matrix multiplication (required for KleidiAI INT4 kernels)
   - **dotprod** — ARMv8.4 dot product instructions (accelerates INT8 inference)
   - **SVE2** — Scalable Vector Extension 2 (enables SME2 workloads)
   - **SME2** — Scalable Matrix Extension (advanced matrix multiplication)

3. **At model load time:** `BackendInfo.logBackendInfo()` logs the full capability profile, so you can verify which optimizations are active.

4. **At inference time:** XNNPACK automatically dispatches to the best kernel for the detected CPU features. No manual configuration needed — the `.pte` model + XNNPACK runtime handle it.

### Exporting a Model with KleidiAI

```bash
# Install ExecuTorch with KleidiAI support
pip install executorch

# Export with XNNPACK + KleidiAI (INT4)
python -m executorch.exir.export_to_edge \
    --model_name "Qwen2ForCausalLM" \
    --hf_model_dir ./my_finetuned_model \
    --output_model my_model_kleidiai_int4.pte \
    --enable_kleidiai \
    --quantize int4 \
    --xnnpack_delegate

# You also need a tokenizer.bin file
python -c "
from transformers import AutoTokenizer
t = AutoTokenizer.from_pretrained('./my_finetuned_model')
t.save_pretrained('./tokenizer_output')
"
```

### Arm Learning Paths Followed

- [Build an Android chat app with Llama, KleidiAI, ExecuTorch, and XNNPACK](https://learn.arm.com/learning-paths/mobile-graphics-and-gaming/build-llama3-chat-android-app-using-executorch-and-xnnpack/)
- [Measure LLM inference performance with KleidiAI and SME2 on Android](https://learn.arm.com/learning-paths/mobile-graphics-and-gaming/performance_llama_cpp_sme2/)

### Mobile Optimizations

| Optimization | Implementation | Impact |
|---|---|---|
| **INT4 quantization** | Model exported with KleidiAI INT4 kernels | ~75% model size reduction vs FP16 |
| **Thread count tuning** | `BackendInfo.optimalThreadCount` — reserves cores for UI | Prevents UI jank during inference |
| **Power saving mode** | `AiService.applyOptimizations()` — halves seqLen | Reduces battery drain |
| **Thermal awareness** | Reads battery temperature, reduces seqLen when hot | Prevents thermal throttling |
| **Adaptive cores** | Scales seqLen based on `availableProcessors` | Matches workload to device capability |
| **Offline inference** | No network calls, all local | Privacy + zero latency from network |

## Setup

### Prerequisites

- Android Studio (latest)
- An Arm 64-bit Android device (API 26+) or an x86_64 emulator for testing
- A compatible model file:
  - `.pte` format (ExecuTorch) — requires a separate tokenizer file
  - `.gguf` format (llama.cpp) — self-contained (tokenizer embedded)

### Build

```bash
git clone https://github.com/meowshmalloww/PocketIDE.git
cd PocketIDE
./gradlew assembleDebug
```

### Run

1. Connect an Android device or start an emulator (API 26+)
2. Install: `./gradlew installDebug`
3. Open PocketIDE
4. Go to **Settings** (gear icon in activity bar) → **On-Device Model** → enter the path to your `.pte` or `.gguf` model file (and tokenizer for `.pte`)
5. Return to the editor, type a prompt in the AI Chat panel (e.g., "Write a function that prints Fibonacci numbers")
6. The AI generates code on-device and inserts it into a new file
7. Press the **Run** button in the Terminal panel to execute the code
8. If execution fails, press **Repair** to have the AI fix the error and re-run

### Obtaining a Model

PocketIDE supports two model formats and offers in-app download from HuggingFace:

**Option 1: Download from URL (in-app)**

Go to **Settings → On-Device Model → Download from URL**, paste a HuggingFace direct download link, choose a prompt template, and tap Download. The model is saved to app-internal storage and auto-configured.

URL format: `https://huggingface.co/<user>/<repo>/resolve/main/<file>.gguf`

**Option 2: Manual file placement**

**ExecuTorch (.pte)** — requires a separate tokenizer file:
- Export with KleidiAI + XNNPACK for best Arm performance (see [Arm Optimization Pipeline](#arm-optimization-pipeline))
- **Llama 3.2 1B Instruct** (INT4) — recommended for devices with 4GB+ RAM
- **Qwen 3 0.6B** (INT4) — lighter alternative for budget devices
- Export instructions: [ExecuTorch LLM Android docs](https://docs.pytorch.org/executorch/stable/llm/run-on-android.html)

**llama.cpp (.gguf)** — self-contained (no tokenizer needed):
- Any HuggingFace GGUF model (e.g., Llama 3.2 1B Instruct GGUF, Qwen 2.5 0.5B GGUF)
- Download from [HuggingFace](https://huggingface.co/models?other=gguf)

Place the model file(s) on your device's storage and point to them in Settings.

## ES6-to-ES5 Transpilation

Since the JavaScript runtime uses Mozilla Rhino (ES5 only), PocketIDE automatically transpiles modern JavaScript to ES5-compatible syntax before execution:

- `let` / `const` → `var`
- Arrow functions → `function` expressions
- Template literals (backticks) → string concatenation
- `for...of` loops → index-based `for` loops
- Default parameters → `if (param === undefined)` checks
- ES6+ polyfills injected: `Object.assign`, `Object.values`, `Object.entries`, `Array.prototype.find`, `Array.prototype.findIndex`, `Array.prototype.includes`, `Array.prototype.flat`, `String.prototype.includes`, `String.prototype.startsWith`, `String.prototype.endsWith`, `String.prototype.repeat`, `String.prototype.padStart`, `String.prototype.padEnd`, `Number.isInteger`, `Number.isNaN`

Strings and comments are protected from transpilation to prevent code corruption.

## Hardware API

Any script (JavaScript, TypeScript, Python, Lua, Java) can call the on-device Android hardware bridge via the global `hardware` object. All methods are safe: no continuous listeners are kept alive after a call returns.

### Method reference

| Method | Description | Return |
|---|---|---|
| `hardware.toast(msg)` | Short toast | void |
| `hardware.toastLong(msg)` | Long toast | void |
| `hardware.vibrate(ms)` | Vibrate for N ms (default 200) | void |
| `hardware.setFlashlight(bool)` | Turn torch on/off | `true` if OK |
| `hardware.batteryLevel()` | Battery percent 0..100 | Int (-1 if N/A) |
| `hardware.isCharging()` | Charging status | Bool |
| `hardware.clipboardGet()` | Read primary clipboard | String |
| `hardware.clipboardSet(text)` | Write clipboard | void |
| `hardware.screenBrightness()` | System brightness 0..255 | Int |
| `hardware.screenInfo()` | `Width: Xpx, Height: Ypx, Density: Zx` | String |
| `hardware.networkType()` | `wifi` / `cellular` / `ethernet` / `none` | String |
| `hardware.isOnline()` | Any transport active | Bool |
| `hardware.storageFree()` | Free internal-storage bytes | Long |
| `hardware.storageTotal()` | Total internal-storage bytes | Long |
| `hardware.readSensor(type, ms)` | One-shot sensor read | String (CSV of values) |
| `hardware.listSensors()` | Available sensors | String |
| `hardware.openUrl(url)` | Open URL in browser | Bool |
| `hardware.getDeviceInfo()` | Full device summary | String |

Sensor types accepted by `readSensor`: `accelerometer`, `gyroscope`, `light`, `pressure`, `proximity`, `magnetic`.

### Examples

**JavaScript** — turn on flashlight for 3 seconds, then buzz:
```javascript
hardware.setFlashlight(true);
hardware.toast("Flashlight ON");
java.lang.Thread.sleep(3000);
hardware.setFlashlight(false);
hardware.vibrate(500);
console.log("Battery: " + hardware.batteryLevel() + "%");
```

**Python** — clipboard round-trip:
```python
hardware.clipboardSet("Hello from PocketIDE!")
print(hardware.clipboardGet())
print("Online:", hardware.isOnline())
```

**Lua** — read the accelerometer:
```lua
local reading = hardware.readSensor("accelerometer", 1000)
print("Accelerometer:", reading)
hardware.toast("Battery " .. hardware.batteryLevel() .. "%")
```

**Java** (BeanShell) — device dashboard:
```java
System.out.println(hardware.getDeviceInfo());
if (hardware.batteryLevel() < 20 && !hardware.isCharging()) {
    hardware.toastLong("Battery low — plug in!");
}
```

### Permissions

The manifest declares `VIBRATE` and `CAMERA` (for flashlight). Sensors, network status, battery, clipboard, storage stats, and screen info require no runtime permissions.

## Project Structure

```
PocketIDE/
├── app/src/main/java/com/pocketide/
│   ├── MainActivity.kt                      # Entry point
│   ├── data/
│   │   ├── ai/
│   │   │   ├── AiConfig.kt                  # Model config data class + enums
│   │   │   ├── AiConfigRepository.kt        # SharedPreferences persistence
│   │   │   ├── AiService.kt                 # On-device inference orchestrator
│   │   │   ├── AiResponseParser.kt          # PLAN/FILENAME/code-block parser
│   │   │   ├── BackendInfo.kt               # Arm CPU feature detection (XNNPACK/KleidiAI)
│   │   │   ├── ContextManager.kt           # Context window management (sliding window, compression, RAG)
│   │   │   ├── ExecutorchLlmRunner.kt       # ExecuTorch LlmModule wrapper
│   │   │   ├── LlamaCppRunner.kt            # llama.cpp GGUF wrapper
│   │   │   ├── LlmRunner.kt                 # Unified runner interface + dispatcher
│   │   │   ├── ModelDownloader.kt           # HuggingFace model download with progress
│   │   │   └── PromptFormatter.kt           # Llama 3 / Qwen / Plain templates
│   │   ├── execution/
│   │   │   └── CodeExecutor.kt              # Multi-language code execution
│   │   ├── hardware/
│   │   │   └── HardwareBridge.kt            # Device hardware access (flashlight, vibrate)
│   │   ├── model/                           # Data classes (Language, CodeFile, etc.)
│   │   └── repository/                      # File I/O on internal storage
│   └── ui/
│       ├── components/                      # ActivityBar, AiChatPanel, TerminalPanel, TopTabBar, etc.
│       ├── editor/                          # CodeEditorField + SyntaxHighlighter
│       ├── navigation/                      # NavHost routes
│       ├── screens/
│       │   ├── editor/                      # EditorScreen + EditorViewModel + EditorUiState
│       │   ├── settings/                    # SettingsScreen
│       │   └── benchmark/                   # (Reserved for benchmark screen)
│       └── theme/                           # Colors, typography, theme ViewModel
├── docs/planning/                           # Research and planning documents
├── gradle/libs.versions.toml                # Version catalog
└── README.md
```

## SWARM Mode (Multi-Agent Pipeline)

When **SWARM** mode is selected in the AI chat panel, PocketIDE orchestrates a multi-agent pipeline that mirrors a real development team:

### Pipeline Steps

1. **Architect** — Receives the user's request and generates a high-level plan (what files to create, what approach to take, which language to use).
2. **Coder** — Takes the Architect's plan and generates the actual code following the PLAN/FILENAME/code block format.
3. **Auto-Execute** — The generated code is automatically executed in the sandbox.
4. **Validator (Autonomous Repair Loop)** — If execution fails, the Validator sends the error details (error type, line, column, stderr) back to the AI for repair. This loop repeats up to `maxRepairIterations` times (configurable in Settings, default 3) or until the code executes successfully.

### Agent Status Indicators

Each agent's status is displayed in real-time via status badges in the chat panel:
- **IDLE** — Agent hasn't started yet
- **LOADING/GENERATING** — Agent is actively working
- **DONE** — Agent completed successfully
- **ERROR** — Agent encountered an error

### Single vs. Swarm Mode

| Feature | Single Model | Swarm Agent |
|---|---|---|
| Pipeline | One AI call → response | Architect → Coder → Validator |
| Auto-execute | No | Yes (after Coder generates) |
| Auto-repair | Manual (Repair button) | Autonomous (up to N iterations) |
| Best for | Quick code, questions, plans | Complex apps, multi-step tasks |

## Context Window Management

Small on-device LLMs (0.5B–7B) typically support 2K–8K tokens of context. PocketIDE includes a **ContextManager** that intelligently manages this limited budget to handle complex, multi-file projects.

### How It Works

1. **Token Estimation** — Rough heuristic (~4 chars/token) to budget context allocation.
2. **Sliding Window** — Keeps the most recent conversation messages intact; older messages are dropped when the budget is exceeded.
3. **History Summarization** — Dropped messages are compressed into a single summary line (e.g., "User asked: create a calculator app...") so the AI retains context without consuming the full token budget.
4. **Code Context Injection (RAG-style)** — Snippets of open project files are injected into the system prompt:
   - The **active file** gets full content included (or truncated if too large).
   - **Other files** get summaries (first 15 lines + line count).
   - This gives the AI multi-file awareness without exceeding the context window.
5. **Token Budget Allocation** — The context window is divided across:
   - System prompt (always included in full)
   - Code context (40% of remaining budget)
   - Conversation history (60% of remaining budget)
   - User message (always included in full)
   - Response generation (25% of window, capped at 2048 tokens)

### Configurable Settings

| Setting | Default | Range | Description |
|---|---|---|---|
| Context window size | 4096 | 2K–128K | Match to your model's context length |
| Inject code context | On | On/Off | Include open file snippets in prompts |
| Summarize old history | On | On/Off | Compress dropped messages into summaries |

### Supported Context Window Sizes

The slider in Settings offers these presets: **2K, 4K, 8K, 16K, 32K, 64K, 128K** tokens.

For models like Qwen 2.5 0.5B (32K context) or Llama 3.2 1B (128K context), set the context window size to match. The ContextManager will then include more conversation history and code context, enabling the AI to handle larger, more complex projects.

> **Note:** Setting a context window larger than what the model actually supports will cause the model to truncate or ignore excess tokens. Always match this to your model's documented context length.

## Roadmap

### Planned

- **Settings file picker** — SAF `OpenDocument` contract to browse and select model files
- **Benchmark screen** — parse `statsJson` from ExecuTorch (tokens/sec, TTFT, peak memory) and render charts
- **Sora Editor integration** — replace custom `BasicTextField` with full-featured Sora editor (auto-complete, bracket matching, find/replace)
- **Model bundling** — ship a small default model in APK assets for zero-config setup
- **Adaptive performance** — use Android Performance APIs to adjust thread count and batch size based on thermal state and battery level
- **Python interpreter** — bundled Python 3 interpreter for native execution instead of JS transpilation

## License

Apache License 2.0 — see [LICENSE](LICENSE)

## Acknowledgments

- [ExecuTorch](https://github.com/pytorch/executorch) — PyTorch on-device inference framework
- [kotlinllamacpp](https://github.com/ljcamargo/kotlinllamacpp) — Kotlin bindings for llama.cpp
- [Mozilla Rhino](https://github.com/mozilla/rhino) — JavaScript engine for Java
- [LuaJ](https://github.com/luaj/luaj) — Lua interpreter for Java
- [BeanShell](https://github.com/beanshell/beanshell) — Java scripting interpreter
- [Jetpack Compose](https://developer.android.com/jetpack/compose) — Declarative UI toolkit
