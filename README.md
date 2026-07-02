# PocketIDE

A mobile AI IDE that writes, executes, validates, and repairs code with AI assistance. Built for Android with Kotlin and Jetpack Compose.

> **Arm AI Optimization Challenge 2026 — Track 3: Mobile AI**

## Features

- **AI code generation** — OpenAI-compatible chat completions API integration with structured PLAN/FILENAME/code response parsing
- **Autonomous self-repair loop** — generates code, executes it, catches errors, sends error context back to AI for repair, re-executes
- **On-device JavaScript execution** — real Rhino engine with `console.log()` support, stdout/stderr capture, and infinite-loop protection
- **Custom code editor** — `BasicTextField` with syntax highlighting for 14 languages, line numbers, and synced scrolling
- **Multi-file project support** — file explorer, tab bar, create/delete/save operations with unsaved-change tracking
- **Responsive layout** — landscape (split view) and portrait (tab-switched full screen) modes
- **Dark/light theme** — VS Code-inspired neutral palette with persisted preference
- **Settings screen** — AI config (base URL, API key, model), appearance, optimization toggles, language enable/disable

## Tech Stack

| Component | Technology |
|---|---|
| Language | Kotlin 2.2.10 |
| UI | Jetpack Compose + Material 3 |
| Code Editor | Custom `BasicTextField` + `SyntaxHighlighter` (Sora Editor declared, not yet integrated) |
| AI Backend | OpenAI-compatible HTTP API (`HttpURLConnection`) |
| JS Execution | Mozilla Rhino 1.7.15 |
| Navigation | Compose Navigation 2.8.5 |
| Persistence | SharedPreferences (config) + internal storage (files) |
| Build | Gradle + AGP 9.2.1 |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 36 |

## Setup

### Prerequisites

- Android Studio (latest)
- An OpenAI-compatible API key (e.g., OpenAI, Groq, Together AI)

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
4. Go to Settings (gear icon in activity bar) and configure your AI API key, base URL, and model
5. Return to editor, type a prompt in the AI Chat panel, and ask it to generate code
6. Press the Run button in the Terminal panel to execute JavaScript code

## Project Structure

```
PocketIDE/
├── app/src/main/java/com/pocketide/
│   ├── MainActivity.kt                      # Entry point
│   ├── data/
│   │   ├── ai/                              # AI service, config, response parser
│   │   ├── execution/                       # Rhino JS executor
│   │   ├── model/                           # Data classes (CodeFile, ChatMessage, etc.)
│   │   └── repository/                      # File I/O on internal storage
│   └── ui/
│       ├── components/                      # Reusable UI (ActivityBar, panels, tabs)
│       ├── editor/                          # CodeEditorField + SyntaxHighlighter
│       ├── navigation/                      # NavHost routes
│       ├── screens/editor/                  # EditorScreen + EditorViewModel
│       ├── screens/settings/                # SettingsScreen
│       └── theme/                           # Colors, typography, theme ViewModel
├── docs/
│   ├── PROGRESS.md                          # Implementation & verification log
│   └── DECISIONS.md                         # Architecture decisions document
├── gradle/libs.versions.toml                # Version catalog
└── README.md
```

See [docs/PROGRESS.md](docs/PROGRESS.md) for a detailed log of all implemented features, verification status, and code organization.

## License

Apache License 2.0 — see [LICENSE](LICENSE)

## Acknowledgments

- [Mozilla Rhino](https://github.com/mozilla/rhino) — JavaScript engine for Java
- [Sora Editor](https://github.com/Rosemoe/sora-editor) — Android code editor (declared, pending integration)
- [Jetpack Compose](https://developer.android.com/jetpack/compose) — Declarative UI toolkit
