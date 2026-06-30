# PocketIDE

A mobile AI IDE that runs a multi-agent micro-swarm of small, specialized AI models entirely on-device. The app writes, executes, validates, and repairs code autonomously — all offline, on an Arm-powered Android phone.

> **Arm AI Optimization Challenge 2026 — Track 3: Mobile AI**

## Features

- **On-device AI code generation** — no cloud, no internet required
- **Multi-agent micro-swarm** — Architect, Coder, and Validator agents work sequentially
- **Autonomous self-repair loop** — generates code, executes it, catches errors, and fixes them
- **Code execution sandbox** — runs Python and JavaScript locally with stdout/stderr capture
- **Arm-optimized inference** — ExecuTorch + KleidiAI + XNNPACK with INT4 quantization
- **Battery-efficient** — adaptive core selection, thermal-aware inference, sequential model loading
- **Works on budget devices** — tested on LG Q70 (Snapdragon 675, 4GB RAM)

## Tech Stack

| Component | Technology |
|---|---|
| UI | Kotlin + Jetpack Compose + Material 3 |
| Code Editor | Sora Editor (tree-sitter syntax highlighting) |
| AI Inference | ExecuTorch (KleidiAI + XNNPACK) via JNI |
| AI Models | Qwen2.5-Coder-0.5B / Qwen3-0.6B (INT4 quantized) |
| Python Sandbox | Chaquopy |
| JS Sandbox | QuickJS |
| Native Layer | C++ via Android NDK |

## Setup Instructions

### Prerequisites

- Android Studio (latest)
- Android NDK r28+
- CMake 3.22+
- An Arm-powered Android device (arm64-v8a, Android 8.0+)

### Build

```bash
git clone https://github.com/meowshmalloww/PocketIDE.git
cd PocketIDE
# Open in Android Studio and build, or:
./gradlew assembleRelease
```

### Run

1. Connect your Arm-powered Android device
2. Enable USB debugging
3. Run from Android Studio or: `./gradlew installRelease`
4. Enable airplane mode
5. Open PocketIDE and start generating code

## Project Structure

```
PocketIDE/
├── app/                    # Android application (Kotlin + Compose)
│   └── src/main/
│       ├── java/com/pocketide/
│       │   ├── MainActivity.kt
│       │   └── ui/theme/   # Material 3 theme
│       └── res/            # Resources (layouts, strings, icons)
├── docs/
│   └── planning/           # Research & planning documents
│       ├── PROJECT_PLAN.txt
│       ├── TECH_STACK.txt
│       ├── RESEARCH_FINDINGS.txt
│       ├── FRAMEWORK_MODEL_COMPARISON.txt
│       ├── BATTERY_AND_HARDWARE.txt
│       ├── CLARIFYING_QUESTIONS.txt
│       ├── DEEP_RESEARCH_PROMPTS.txt
│       └── ARM Hackathon - Google Docs.txt
├── build.gradle.kts
├── settings.gradle.kts
└── LICENSE
```

## License

Apache License 2.0 — see [LICENSE](LICENSE)

## Acknowledgments

- [ExecuTorch](https://github.com/pytorch/executorch) — PyTorch on-device inference
- [KleidiAI](https://gitlab.arm.com/kleidi/kleidiai) — Arm AI acceleration
- [Sora Editor](https://github.com/Rosemoe/sora-editor) — Android code editor
- [Qwen2.5-Coder](https://huggingface.co/Qwen/Qwen2.5-Coder-0.5B) — Code generation model
