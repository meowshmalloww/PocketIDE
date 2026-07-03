# PocketIDE

A mobile AI IDE that runs **entirely on-device** — no cloud, no API keys, no network calls. AI generates code, executes it, catches errors, and repairs itself, all powered by ExecuTorch on-device LLM inference optimized for Arm.

> **Arm AI Optimization Challenge 2026 — Track 3: Mobile AI**

## Features

### On-Device AI Inference

- **ExecuTorch integration** — `LlmModule` wrapper (`ExecutorchLlmRunner`) with coroutine-friendly load/generate/stop/close lifecycle, model instance reuse across requests, and mutex-serialized native calls
- **Prompt templating** — Llama 3 / 3.2 Instruct, Qwen 2.5 / Qwen 3 ChatML, and Plain templates with system + history + user message formatting
- **Structured response parsing** — AI responds in PLAN / FILENAME / code-block format; parsed into actionable file operations
- **Autonomous self-repair loop** — generates code → executes it → captures stderr → sends error context back to AI → re-executes, up to configurable max iterations
- **Configurable inference** — temperature, max sequence length, prompt template, quantization level (INT4 / INT8 / FP32), all persisted via SharedPreferences

### Code Execution

- **On-device JavaScript** — Mozilla Rhino engine with `console.log()` support, stdout/stderr capture, instruction-count-based infinite-loop protection
- **14 language definitions** — Python, JavaScript, TypeScript, Kotlin, Dart, SQL, HTML, CSS, Java, Lua, Shell, YAML, Markdown, JSON

### IDE Experience

- **Custom code editor** — `BasicTextField` with syntax highlighting, line numbers, and synced scrolling
- **Multi-file project support** — file explorer, tab bar, create/delete/save operations with unsaved-change tracking
- **Project switcher** — create, switch, and delete projects with isolated file namespaces
- **AI chat overlay** — side panel with message bubbles, agent status indicators (Architect → Coder → Validator)
- **Responsive layout** — landscape (split view) and portrait (tab-switched full screen) modes
- **Dark/light theme** — VS Code-inspired neutral palette with persisted preference
- **Settings screen** — on-device model config, optimization toggles (power saving, thermal-aware, adaptive cores), agent repair iterations, sandbox language enable/disable

## Tech Stack

| Component | Technology |
|---|---|
| Language | Kotlin 2.2.10 |
| UI | Jetpack Compose + Material 3 |
| Code Editor | Custom `BasicTextField` + `SyntaxHighlighter` (Sora Editor declared, pending UI integration) |
| On-Device AI | ExecuTorch 1.0.0 (`LlmModule`) — XNNPACK + KleidiAI on Arm |
| JS Execution | Mozilla Rhino 1.7.15 |
| Navigation | Compose Navigation 2.8.5 |
| Persistence | SharedPreferences (config) + internal storage (files) |
| Build | Gradle + AGP 9.2.1 |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 36 |
| ABI Filters | `arm64-v8a` (target), `x86_64` (emulator testing) |

## Setup

### Prerequisites

- Android Studio (latest)
- An Arm 64-bit Android device (API 26+) or an x86_64 emulator for testing
- A compatible `.pte` model file and tokenizer (e.g., Llama 3.2 1B Instruct or Qwen 3 0.6B)

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
4. Go to **Settings** (gear icon in activity bar) → **On-Device Model** → enter the path to your `.pte` model file and tokenizer
5. Return to the editor, type a prompt in the AI Chat panel (e.g., "Write a function that prints Fibonacci numbers")
6. The AI generates code on-device and inserts it into a new file
7. Press the **Run** button in the Terminal panel to execute JavaScript code
8. If execution fails, press **Repair** to have the AI fix the error and re-run

### Obtaining a Model

PocketIDE uses ExecuTorch-exported `.pte` model files. Compatible models include:

- **Llama 3.2 1B Instruct** (INT4) — recommended for devices with 4GB+ RAM
- **Qwen 3 0.6B** (INT4) — lighter alternative for budget devices

Export instructions: [ExecuTorch LLM Android docs](https://docs.pytorch.org/executorch/stable/llm/run-on-android.html)

Place the `.pte` and tokenizer files on your device's storage and point to them in Settings.

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
│   │   │   ├── ExecutorchLlmRunner.kt       # LlmModule wrapper (load/generate/stop)
│   │   │   └── PromptFormatter.kt           # Llama 3 / Qwen / Plain templates
│   │   ├── execution/                       # Rhino JS executor
│   │   ├── model/                           # Data classes (Language, CodeFile, etc.)
│   │   └── repository/                      # File I/O on internal storage
│   └── ui/
│       ├── components/                      # ActivityBar, AiChatPanel, TerminalPanel, etc.
│       ├── editor/                          # CodeEditorField + SyntaxHighlighter
│       ├── navigation/                      # NavHost routes
│       ├── screens/
│       │   ├── editor/                      # EditorScreen + EditorViewModel
│       │   ├── settings/                    # SettingsScreen
│       │   └── benchmark/                   # (Reserved for benchmark screen)
│       └── theme/                           # Colors, typography, theme ViewModel
├── docs/
│   ├── PROGRESS.md                          # Implementation & verification log
│   └── DECISIONS.md                         # Architecture decisions document
├── gradle/libs.versions.toml                # Version catalog
└── README.md
```

## Roadmap

### In Progress

- **Settings file picker** — SAF `OpenDocument` contract to browse and copy `.pte` + tokenizer into `filesDir/models/`
- **Live token streaming** — surface `onToken` callbacks in the chat panel so tokens appear as they're generated
- **Benchmark screen** — parse `statsJson` from ExecuTorch (tokens/sec, TTFT, peak memory) and render charts

### Planned

- **Multi-language execution** — Python via Chaquopy or bundled interpreter
- **Sora Editor integration** — replace custom `BasicTextField` with full-featured Sora editor (auto-complete, bracket matching, find/replace)
- **Model bundling** — ship a small default model in APK assets for zero-config setup
- **Adaptive performance** — use Android Performance APIs to adjust thread count and batch size based on thermal state and battery level

See [docs/PROGRESS.md](docs/PROGRESS.md) for a detailed implementation log and [docs/DECISIONS.md](docs/DECISIONS.md) for architecture decisions.

## License

Apache License 2.0 — see [LICENSE](LICENSE)

## Acknowledgments

- [ExecuTorch](https://github.com/pytorch/executorch) — PyTorch on-device inference framework
- [Mozilla Rhino](https://github.com/mozilla/rhino) — JavaScript engine for Java
- [Sora Editor](https://github.com/Rosemoe/sora-editor) — Android code editor (declared, pending integration)
- [Jetpack Compose](https://developer.android.com/jetpack/compose) — Declarative UI toolkit
