================================================================================
                    POCKETIDE — MASTER DECISION DOCUMENT
              Read this before we start coding. Edit freely.
================================================================================

Last updated: July 7, 2026

This document consolidates EVERYTHING you need to review before we start
building. You can edit this file, share it with other AI agents for input,
and use it as the single source of truth for all decisions.


================================================================================
TABLE OF CONTENTS
================================================================================

1.  Project Overview
2.  Confirmed Decisions (verify these are correct)
3.  Pending Decisions (you need to choose)
4.  Architecture Summary
5.  Tech Stack Summary
6.  Model Strategy (detailed comparison)
7.  Framework Comparison (detailed)
8.  Battery & Hardware Strategy
9.  Timeline & Phases
10. Submission Checklist
11. Open Questions / Risks
12. Questions to Ask Other AI Agents


================================================================================
1. PROJECT OVERVIEW
================================================================================

WHAT: PocketIDE — a mobile AI IDE that runs a multi-agent micro-swarm of
small AI models entirely on-device. The app writes, executes, validates,
and repairs code autonomously — all offline, on an Arm-powered phone.

TRACK: Track 3 (Mobile AI) — On-device, offline, Arm-powered

THE "WOW": An Android phone in airplane mode writes code, catches its own
errors, repairs them, and runs the final result — no internet, no cloud,
no laptop. Works even on a 2019 budget phone with 4GB RAM.

THE "OPTIMIZATION": Multiple ultra-compact models (50M-500M params)
quantized to INT4, exported via ExecuTorch with KleidiAI acceleration,
running sequentially with flat memory ceiling on mid-range Arm devices.
Battery-efficient inference using adaptive core selection and
thermal-aware scheduling.

JUDGING CRITERIA (weighted):
  1. Technological Implementation:  40 pts  (HEAVIEST — optimization work)
  2. WOW Factor:                    25 pts  (airplane mode demo, budget device)
  3. Potential Impact:              20 pts  (open-source artifacts, reusability)
  4. User/Developer Experience:     15 pts  (clean repo, installable APK, UI)

TIE-BREAKER: Highest Technological Implementation score wins ties.

JUDGES (Arm engineers):
  - Avin Zarlez (Arm Staff SW Engineer)
  - Michael Hall (Arm Principal SW Engineer) — teaches ExecuTorch + KleidiAI
  - Gabriel Peterson (Arm Senior ML Engineer)
  - Rani Chowdary Mandepudi (Software Engineer)

PRIZE: $8,000 total ($3,000 Overall Winner + $1,000 Best Mobile AI)


================================================================================
2. CONFIRMED DECISIONS (verify these are correct)
================================================================================

[x] App name: PocketIDE
[x] Package name: com.pocketide
[x] Platform: Android only (arm64-v8a)
[x] App language: Kotlin
[x] UI framework: Jetpack Compose + Material 3
[x] UI theme: Dark theme (with light/dark toggle)
[x] UI layout: Chat panel (left) + code editor (right) = split view
[ ] Code editor library: Sora Editor (tree-sitter, auto-completion, diagnostics)
    — NOT YET integrated. Currently using custom BasicTextField + SyntaxHighlighter.
[x] Native layer: C++ via JNI / Android NDK (ExecuTorch AAR)
[x] minSdk: 26 (Android 8.0) — covers 95%+ of devices
[x] targetSdk: 36
[x] License: Apache 2.0
[x] GitHub repo: https://github.com/meowshmalloww/PocketIDE
[x] Code execution sandbox: 7 languages — JavaScript (Rhino), TypeScript,
    Python (transpiled to JS), Lua (LuaJ), SQL (SQLite), Java (BeanShell),
    Shell (ProcessBuilder)
[x] Multi-file project support: Yes (sequential generation)
[x] Both AI-generated and user-edited code: Yes
[x] Agent visualization: Show agent activity with colors
    — Architect = blue, Coder = green, Validator = orange
[x] Benchmarking: Real-time per-message display — TTFT, tokens/sec,
    memory delta, inference strategy. InferenceBenchmark wraps every
    generation call. (Dedicated benchmark screen still planned.)
[ ] Fine-tuning: LoRA via Hugging Face TRL + PEFT — NOT YET DONE
[x] Training hardware: A100/L40 cloud GPU or 4080M local
[x] Model hosting: Hugging Face (open-source .pte and .gguf files)
[x] Quantization: INT4 groupwise (group_size=32)
[x] Hardware bridge: IMPLEMENTED — 30+ Android hardware APIs (flashlight,
    GPS, TTS, Bluetooth, camera, sensors, file I/O, localhost HTTP server,
    notifications, screen control, audio tone, vibration patterns).
    See docs/planning/HARDWARE_BRIDGE.md.
[x] Multi-language support: Bridge is language-agnostic (JS, TS, Python,
    Lua, Java). Same hardware.* API surface for all.
[x] AI optimization pipeline: AdaptiveInferenceTuner (thermal/battery/memory/cores),
    KvCacheManager (memory estimation + eviction), InferenceBenchmark (TTFT/tok/s/mem),
    AgentContextPruner (role-specific context for SWARM).
[x] Platform scope: Android first (primary submission). Mac Desktop
    port if time permits (Compose Desktop, same Kotlin/Rhino codebase).
    iOS is a stretch goal — requires Swift UI rewrite + JavaScriptCore
    (no Rhino on iOS). Not planned for hackathon timeframe.

If any of these are wrong, tell me before we start coding.


================================================================================
3. PENDING DECISIONS (you need to choose)
================================================================================

--------------------------------------------------------------------------------
DECISION 1: INFERENCE FRAMEWORK
--------------------------------------------------------------------------------

You said "we can go with ExecuTorch" but also "not decided yet."

Options:

  A) EXECUTORCH ONLY
     + Arm's official framework, judges know it (Michael Hall teaches it)
     + KleidiAI enabled by default = instant Arm optimization story
     + 50KB runtime footprint (smallest)
     + INT4 groupwise quantization (8da4w mode)
     + mmap loading by default
     + Official Android AAR on Maven Central
     + Qwen3-0.6B officially supported (added May 2025)
     + Llama 3.2 1B officially supported with pre-built .pte files
     - Qwen2.5-Coder-0.5B export has had issues (may be fixed in 1.0+)
     - No GPU/NPU acceleration (CPU only with XNNPACK)
     - Building AAR from source requires NDK + CMake setup
     - Documentation can be sparse for non-Llama models

  B) LITERT-LM ONLY
     + Qwen2.5-0.5B officially supported, pre-converted models on Hugging Face
     + GPU acceleration (OpenCL) — up to 19x faster than llama.cpp on GPU
     + NPU acceleration (Qualcomm, MediaTek, Google Tensor)
     + Kotlin API: com.google.ai.edge.litertlm (stable)
     + Multi-Token Prediction (MTP) for decode speedup
     + LoRA support for on-device inference
     + 3x faster than llama.cpp on CPU
     - NOT the framework Arm's judges teach
     - Google's ecosystem, not Arm's (less "Arm optimization" recognition)
     - KleidiAI integration is indirect (through XNNPACK)
     - Less control over low-level CPU optimization

  C) BOTH (ExecuTorch primary + LiteRT-LM fallback)
     + Build with ExecuTorch first, fall back to LiteRT-LM if export fails
     + Can benchmark both frameworks = extra optimization comparison
     - More work, two integration paths

  D) START WITH EXECUTORCH, DECIDE LATER
     + Begin with ExecuTorch, if it fails switch to LiteRT-LM
     + Pragmatic, doesn't lock in prematurely

  MY RECOMMENDATION: A (ExecuTorch only)
  REASON: This is an Arm hackathon. Judge Michael Hall teaches ExecuTorch.
  KleidiAI is built in. If Qwen2.5-Coder export fails, we use Qwen3-0.6B
  (officially supported) or Llama 3.2 1B (pre-built .pte). We don't need
  GPU/NPU — CPU optimization with KleidiAI IS the story.

  YOUR CHOICE: ______

--------------------------------------------------------------------------------
DECISION 2: BASE MODEL
--------------------------------------------------------------------------------

You said "research and not decide, decide later."

Options:

  1) QWEN2.5-CODER-0.5B (best code quality under 1B)
     + 0.49B params, Apache 2.0
     + HumanEval: 28% pass@1, MBPP: 52.9% pass@1
     + Code-specific training (5.5T tokens of code)
     + 32K context length
     + Llama-compatible architecture
     + INT4: ~130-280MB
     - ExecuTorch export has had issues (GitHub #9353, #14810)
       (likely fixed in ExecuTorch 1.0+, but unconfirmed)
     - Not officially tested in ExecuTorch demo app
     - Tight for 4GB RAM (LG Q70)

  2) QWEN3-0.6B (officially supported by ExecuTorch)
     + 0.6B params, Apache 2.0
     + Officially supported by ExecuTorch (added May 2025)
     + Unsloth confirms .pte export works (472MB)
     + Newer architecture
     - NOT code-specific (general purpose model)
     - No published HumanEval/MBPP scores
     - 472MB .pte is tight for 4GB RAM
     - Would need LoRA fine-tuning for code generation

  3) MIXED MODELS BY AGENT ROLE
     + Architect: SmolLM2-135M (small, planning doesn't need code knowledge)
       - 135M params, INT4: ~35-70MB, fits in L3 cache
       - ExecuTorch 1.0 export confirmed working
     + Coder: Qwen2.5-Coder-0.5B or Qwen3-0.6B (needs code ability)
     + Validator: SmolLM2-360M (error analysis, mid-size)
       - 360M params, INT4: ~90-180MB
     + Optimal memory usage per task
     + Shows intelligent model selection = optimization story
     - More complex (multiple export pipelines, different tokenizers)
     - SmolLM2 models are NOT code-specific (need fine-tuning)

  4) QWEN2.5-CODER-0.5B + SMOLLM2-135M FALLBACK
     + Use Qwen2.5-Coder on devices with 6GB+ RAM
     + Use SmolLM2-135M on LG Q70 (4GB RAM)
     + Adaptive model selection based on available RAM
     + Another optimization story: "auto-selects model based on device"
     - Two export pipelines needed
     - SmolLM2-135M code quality is very low without fine-tuning

  5) LLAMA 3.2 1B (safest, most documented)
     + Official ExecuTorch support, pre-built .pte files on Hugging Face
     + Arm's own learning path uses this model
     + KleidiAI + SME2 benchmarks well documented
     - 1B is larger, INT4: ~350-780MB
     - Too big for LG Q70 (4GB RAM)
     - NOT code-specific
     - Llama license (more restrictive than Apache 2.0)

  6) TRY QWEN2.5-CODER FIRST, FALLBACK AS NEEDED
     + Try Qwen2.5-Coder-0.5B export first (best code quality)
     + If ET export fails → try Qwen3-0.6B (officially supported)
     + If that fails → use LiteRT-LM with Qwen2.5-Coder (pre-converted)
     + Last resort → Llama 3.2 1B (pre-built .pte, guaranteed)
     + Pragmatic, doesn't lock in prematurely

  MY RECOMMENDATION: 6 (try Qwen2.5-Coder first, fallback as needed)
  REASON: Keeps best code quality as primary, has clear fallback chain.
  For LG Q70 specifically, use SmolLM2-135M or SmolLM2-360M (only viable
  for 4GB RAM). This gives us TWO optimization stories: "works on budget
  phone" + "adaptive model selection by device capability."

  YOUR CHOICE: ______

--------------------------------------------------------------------------------
DECISION 3: MODEL DEPLOYMENT STRATEGY
--------------------------------------------------------------------------------

How do we deploy 3 agent models?

  A) ONE BASE MODEL + 3 LORA ADAPTERS (swapped at runtime)
     + Base INT4: ~130-280MB + 3 LoRA adapters: ~15-45MB = ~300MB total
     + KEY optimization story: 70% memory reduction vs 3 separate models
     + All agents share same tokenizer
     - Requires ExecuTorch runtime LoRA support (NEEDS VERIFICATION)
     - May not be supported — if not, fall back to B

  B) THREE SEPARATE .pte FILES (loaded sequentially)
     + 3 × 130-280MB on disk, only 1 in memory at a time
     + Simpler, guaranteed to work
     + Memory ceiling = size of largest single model
     - More disk space (but only 1 model in RAM at a time)
     - Less impressive optimization story

  C) ONE SINGLE MODEL FOR ALL 3 ROLES (simplest)
     + Single model, different system prompts for each agent role
     + Least disk space, least complexity
     - Less specialized (one model does everything)
     - Weaker optimization story (no model selection logic)

  MY RECOMMENDATION: A (one base + LoRA), fall back to B if unsupported
  REASON: If ExecuTorch supports runtime LoRA swapping, this is a massive
  optimization story (70% memory reduction). If not, sequential loading
  of 3 separate files still works and maintains flat memory ceiling.

  YOUR CHOICE: ______

--------------------------------------------------------------------------------
DECISION 4: SME2 DEVICE AVAILABILITY
--------------------------------------------------------------------------------

Can you get a Galaxy S24 (Snapdragon 8 Gen 3) or similar for benchmarking?

SME2 gives 17-73% speedup on LLM inference. Having an SME2 device lets us
show a 3-tier optimization story: NEON → i8mm → SME2.

  A) Yes, my friend has a Galaxy S24 — I can borrow it
  B) No, I can't get one — focus on NEON + i8mm optimizations
  C) Not sure yet, I'll ask

Devices with SME2:
  - Samsung Galaxy S24/S24+/S24 Ultra (Snapdragon 8 Gen 3)
  - Samsung Galaxy S25 series (Snapdragon 8 Elite)
  - OnePlus 12 (Snapdragon 8 Gen 3)
  - Vivo X300 Pro (MediaTek Dimensity 9300) — Arm's own benchmark device

  YOUR CHOICE: ______

--------------------------------------------------------------------------------
DECISION 5: FINE-TUNING SCOPE
--------------------------------------------------------------------------------

How much fine-tuning do we do?

  A) PRE-TRAINED ONLY (fastest, fewer optimization points)
     + No training needed, use models as-is
     + Faster to build
     - No "quality improvement" benchmark story
     - Models may not be good enough for code generation

  B) LORA FINE-TUNE ALL 3 AGENTS (more work, more points)
     + Architect: fine-tune on planning/task decomposition data
     + Coder: fine-tune on code generation from specs
     + Validator: fine-tune on error analysis + repair instructions
     + Can show before/after quality benchmarks
     - More work (3 training runs, 3 datasets to prepare)
     - Need GPU access

  C) LORA FINE-TUNE ONLY THE CODER AGENT (balanced)
     + Focus fine-tuning effort where it matters most (code generation)
     + Use pre-trained for Architect and Validator
     + Can show before/after quality for Coder
     - Less comprehensive optimization story

  D) AGENT DISTILLATION (most ambitious)
     + Use Qwen2.5-Coder-32B as teacher → 0.5B as student
     + Generate training data from large model trajectories
     + Small agents can match next-tier larger models
     - Most complex, needs teacher model access
     - More training time

  MY RECOMMENDATION: B (fine-tune all 3 agents)
  REASON: Fine-tuning is where "Model quality" optimization points come
  from. Showing before/after HumanEval scores for each agent is a strong
  benchmark artifact. With cloud GPU access, 0.5B LoRA training takes
  hours, not days.

  YOUR CHOICE: ______

--------------------------------------------------------------------------------
DECISION 6: CODE COMPLEXITY SCOPE
================================================================================

What complexity of code should the IDE generate?

  A) SINGLE-FILE SCRIPTS ONLY (functions, algorithms, utilities)
     + Realistic for 0.5B model
     + Faster to implement, easier to test
     - Less impressive demo

  B) MULTI-FILE SMALL PROJECTS (2-5 files)
     + More impressive demo
     + Sequential generation (file by file)
     - More complex orchestration
     - 0.5B model may struggle with consistency across files

  C) FULL APP SCAFFOLDING (project templates)
     + Most impressive demo
     - Not realistic for 0.5B model
     - Too ambitious for hackathon timeline

  MY RECOMMENDATION: B (multi-file small projects)
  REASON: Multi-file support is a differentiator. We generate files
  sequentially — the Architect agent plans the file structure, then the
  Coder generates each file one at a time. This is realistic for a 0.5B
  model and impressive for a demo.

  YOUR CHOICE: ______


================================================================================
4. ARCHITECTURE SUMMARY (7 LAYERS)
================================================================================

LAYER 1: USER INTERFACE
  - Kotlin + Jetpack Compose + Material 3
  - Dark theme, code-focused, Cursor/Windsurf-inspired layout
  - Chat panel (left) + code editor panel (right) = split view
  - Code editor: Sora Editor (tree-sitter syntax highlighting, auto-completion)
  - Console output + error display (bottom panel)
  - Agent status indicators: colored by agent (blue/green/orange)
  - Settings (model selection, quantization level, power saving mode)
  - Battery level indicator + energy consumption tracking

LAYER 2: ORCHESTRATION (Swarm Orchestrator)
  - Deterministic state machine (NOT AI-driven — rigid scheduling)
  - Sequential Execution Loop: Load Agent → Execute → Unload → Next Agent
  - Active Memory Pruning: purge model weights after each agent runs
  - Flat memory ceiling regardless of swarm complexity
  - Flow: Architect → Coder → Validator → (Repair loop if needed)
  - Thermal monitor: reduces threads/clock when throttling detected
  - Adaptive core selection: big cores for prefill, little cores for decode

LAYER 3: AI AGENTS (The Micro-Swarm)
  - Agent 1 (Architect, blue): Breaks down user prompt into structured plan
  - Agent 2 (Coder, green): Generates code from structured plan
  - Agent 3 (Validator, orange): Analyzes errors, generates repair instructions
  - Each agent: 50M-500M param model, task-specific fine-tuned
  - INT4/INT8 quantized, exported to .pte via ExecuTorch

LAYER 4: INFERENCE & OPTIMIZATION
  - ExecuTorch runtime (50KB footprint)
  - KleidiAI micro-kernels (NEON/i8mm/SME2 acceleration)
  - XNNPACK backend for Android CPU
  - Ahead-of-time compiled .pte model graphs
  - mmap loading for memory efficiency
  - Runtime feature detection: NEON (always), i8mm (if available), SME2 (if available)
  - Graceful fallback: SME2 → i8mm → NEON baseline

LAYER 5: INTEGRATION BRIDGE
  - JNI bridge between Kotlin app and C++ ExecuTorch runtime
  - ExecuTorch AAR library: org.pytorch:executorch-android (Maven Central)
  - Model loading/unloading lifecycle management
  - Token streaming from inference to UI
  - Error trace capture from sandbox to validator agent

LAYER 6: APPLICATION SANDBOX
  - Local code execution environment (isolated)
  - Python (Chaquopy) + JavaScript (QuickJS) at launch
  - Captures stdout, stderr, exit codes
  - Timeout enforcement + crash isolation
  - No network access (true offline validation)
  - tree-sitter for syntax validation before execution

LAYER 7: BENCHMARKING & PROOF ARTIFACTS
  - Built-in benchmark mode: tokens/sec, TTFT, memory RSS, model size, energy/token
  - Before/after optimization comparison logs
  - Battery consumption tracking (Android BatteryManager API)
  - Thermal throttling curve (performance over time)
  - Compare: FP32 vs INT8 vs INT4, with/without KleidiAI, big vs little cores

AUTONOMOUS VALIDATION LOOP:

  [User Prompt]
       ↓
  [Architect Agent: Plan]
       ↓
  [Coder Agent: Generate Code]
       ↓
  [Sandbox: Execute Code]
       ↓
  [Pass?] → YES → [Output to user]
       ↓ NO
  [Validator Agent: Analyze Error]
       ↓
  [Coder Agent: Repair Code]
       ↓
  (loop back to Sandbox: Execute Code)

  Loop continues until:
    1. Code executes successfully → display result
    2. Max iterations reached → show best attempt
    3. No improvement detected → show error + last attempt


================================================================================
5. TECH STACK SUMMARY
================================================================================

| Layer              | Technology                              | Status       |
|--------------------|-----------------------------------------|--------------|
| IDE                | Android Studio                          | DONE         |
| App Language       | Kotlin 2.2.10                           | DONE         |
| Native Language    | C++ (via JNI/NDK)                       | DONE         |
| UI Framework       | Jetpack Compose + Material 3            | DONE         |
| Code Editor        | Custom BasicTextField + SyntaxHighlighter | DONE       |
|                    | Sora Editor (tree-sitter)               | PLANNED      |
| Inference Engine   | ExecuTorch 1.0.0 (XNNPACK + KleidiAI)   | DONE         |
| Inference Engine   | llama.cpp (kotlinllamacpp)              | DONE         |
| Model Format       | .pte + .gguf                            | DONE         |
| Base Model         | User-selectable (.pte or .gguf)         | DONE         |
| Quantization       | INT4 groupwise (group_size=32)          | DONE         |
| Fine-Tuning        | LoRA via Hugging Face TRL + PEFT        | NOT STARTED  |
| Training Hardware  | A100/L40 cloud or 4080M local           | CONFIRMED    |
| JS Sandbox         | Mozilla Rhino 1.7.15                    | DONE         |
| Lua Sandbox        | LuaJ 3.0.1                              | DONE         |
| Java Sandbox       | BeanShell 3.0                           | DONE         |
| SQL Sandbox        | Android SQLite                          | DONE         |
| Shell Sandbox      | ProcessBuilder (/system/bin/sh)         | DONE         |
| Python Sandbox     | Transpiled to JS via Rhino              | DONE         |
| Local HTTP Server  | NanoHTTPD 2.3.1                         | DONE         |
| JNI Bridge         | Android NDK + ExecuTorch AAR            | DONE         |
| Benchmarking       | InferenceBenchmark (TTFT, tok/s, mem)   | DONE         |
| Adaptive Tuning    | AdaptiveInferenceTuner (5 strategies)   | DONE         |
| KV Cache Mgmt      | KvCacheManager (estimation + eviction)  | DONE         |
| Context Pruning    | AgentContextPruner (role-specific)      | DONE         |
| Hardware Bridge    | 30+ Android hardware APIs               | DONE         |
| Unit Testing       | JUnit 4 + Robolectric (94 tests)        | DONE         |
| Model Hosting      | Hugging Face (open-source .pte/.gguf)   | DONE         |
| Code Repository    | GitHub (Apache 2.0)                     | DONE         |
| Build System       | Gradle + AGP 9.2.1                      | DONE         |
| Min Android SDK    | 26 (Android 8.0)                        | DONE         |
| Target SDK         | 36                                      | DONE         |
| Target ABI         | arm64-v8a + x86_64 (emulator)           | DONE         |

ALTERNATIVE STACK (if ExecuTorch fails):
  - Inference Engine: LiteRT-LM (Google AI Edge)
  - Model Format: .litertlm / .tflite
  - Kotlin API: com.google.ai.edge.litertlm
  - GPU Acceleration: LiteRT GPU Delegate (OpenCL)


================================================================================
6. MODEL STRATEGY (DETAILED COMPARISON)
================================================================================

BENCHMARK SCORES (HumanEval pass@1, Python code generation):

| Model                    | Size   | HumanEval | MBPP  | License     | Code-Specific |
|--------------------------|--------|-----------|-------|-------------|---------------|
| Qwen2.5-Coder-0.5B       | 0.49B  | 28.0%     | 52.9% | Apache 2.0  | YES           |
| Qwen2.5-Coder-1.5B       | 1.54B  | 43.9%     | 69.2% | Apache 2.0  | YES           |
| DeepSeek-Coder-1.3B      | 1.3B   | 34.8%     | 55.6% | DEEPSEEK    | YES           |
| Qwen3-0.6B               | 0.6B   | ~25-30%?  | ~50%? | Apache 2.0  | NO (general)  |
| SmolLM2-360M-Instruct    | 0.36B  | ~15-20%?  | ~30%? | Apache 2.0  | NO (general)  |
| SmolLM2-135M-Instruct    | 0.135B | ~5-10%?   | ~15%? | Apache 2.0  | NO (general)  |
| Llama 3.2 1B Instruct    | 1.0B   | ~17%      | ~43%  | Llama License| NO (general)  |

NOTE: SmolLM2 and Qwen3 scores are estimates — no official HumanEval
numbers published. SmolLM2 is general, not code-trained. Qwen3 is general
but newer architecture.

MODEL SIZE ON DISK (INT4 QUANTIZED):

| Model                    | FP32    | INT8    | INT4         | Fits 4GB RAM? |
|--------------------------|---------|---------|--------------|---------------|
| SmolLM2-135M             | ~540MB  | ~135MB  | ~35-70MB     | YES           |
| SmolLM2-360M             | ~1.4GB  | ~360MB  | ~90-180MB    | YES           |
| Qwen2.5-Coder-0.5B       | ~1.9GB  | ~500MB  | ~130-280MB   | TIGHT         |
| Qwen3-0.6B               | ~2.3GB  | ~600MB  | ~150-472MB   | TIGHT         |
| Llama 3.2 1B             | ~4GB    | ~1GB    | ~350-780MB   | NO            |

EXECUTORCH SUPPORT STATUS:

| Model                    | ExecuTorch .pte | LiteRT-LM | Notes                    |
|--------------------------|-----------------|-----------|--------------------------|
| Qwen2.5-Coder-0.5B       | Issues (may be  | Yes       | ET export bugs in 0.7,   |
|                          | fixed in 1.0+)  |           | fixed in 1.0 (unconfirmed)|
| Qwen3-0.6B               | Official        | Yes       | Added May 2025, Unsloth  |
|                          |                 |           | confirms 472MB .pte works |
| Llama 3.2 1B             | Official        | Yes       | Pre-built .pte on HF     |
|                          |                 |           | Arm's learning path model |
| SmolLM2-135M             | Works (1.0+)    | No        | User confirmed ET 1.0    |
|                          |                 |           | export works after debug |
| SmolLM2-360M             | Unconfirmed     | No        | Likely works with ET 1.0 |

ON-ANDROID BENCHMARKS (from research):

| Model              | Device           | Framework | Prefill tk/s | Decode tk/s | Memory  |
|--------------------|------------------|-----------|--------------|-------------|---------|
| Qwen2.5-0.5B       | S24 Ultra (CPU)  | LiteRT    | 251          | 30          | 1363MB  |
| Qwen3-0.6B         | Vivo X300 Pro    | LiteRT    | 165          | 9           | ?       |
| Llama 3.2 1B       | Various          | llama.cpp | 204          | 48          | ~1.5GB  |
| Qwen2.5-Coder-0.5B | SD 8 Gen 1 (GPU) | MLC       | ?            | 4.5         | ~276MB  |

FINE-TUNING PLAN (if we fine-tune):
  - Method: LoRA (Low-Rank Adaptation)
  - Config: r=16, alpha=32, dropout=0.05, target=q_proj+v_proj
  - Optimizer: paged_adamw_8bit
  - Datasets:
    - Architect: task decomposition / planning data
    - Coder: HumanEval, MBPP, CodeAlpaca, the-stack-smol
    - Validator: error analysis + code repair data
  - Agent distillation: Qwen2.5-Coder-32B as teacher → 0.5B as student
  - Training: single GPU, hours not days
  - Post-training quantization: INT4 groupwise (group_size=32)
  - Export to .pte via ExecuTorch with XNNPACK + KleidiAI


================================================================================
7. FRAMEWORK COMPARISON (DETAILED)
================================================================================

| Feature              | ExecuTorch       | LiteRT-LM        | llama.cpp       |
|----------------------|------------------|------------------|-----------------|
| Arm Judge Alignment  | 5/5              | 3/5              | 2/5             |
| KleidiAI Integration | 5/5 (native)     | 3/5 (via XNN)    | 4/5 (built-in)  |
| Qwen2.5-0.5B Support | 3/5 (issues)     | 5/5 (native)     | 4/5             |
| Qwen3-0.6B Support   | 5/5 (official)   | 4/5              | 4/5             |
| GPU Acceleration     | 1/5 (no)         | 5/5 (yes)        | 1/5 (no)        |
| NPU Acceleration     | 2/5 (QNN only)   | 5/5 (yes)        | 1/5 (no)        |
| Android AAR          | 5/5 (Maven)      | 5/5 (Maven)      | 2/5 (manual)    |
| Ease of Use          | 3/5              | 5/5              | 3/5             |
| Runtime Footprint    | 5/5 (50KB)       | 3/5              | 3/5             |
| Low-Level CPU Access | 5/5 (C++)        | 2/5              | 5/5 (C++)       |
| Battery Efficiency   | 4/5              | 3/5              | 3/5             |
| License              | Apache 2.0       | Apache 2.0       | MIT             |

KEY INSIGHT: Judge Michael Hall literally teaches ExecuTorch + KleidiAI
code-alongs on Arm's learning platform. Using ExecuTorch gives us instant
recognition in the "Technological Implementation" category (40% of score).


================================================================================
8. BATTERY & HARDWARE STRATEGY
================================================================================

TARGET DEVICES (3 tiers):

  TIER 1 — WOW FACTOR: LG Q70 (Snapdragon 675, 4GB RAM)
    - ARMv8.2-A, NEON only (no i8mm, no SME2)
    - 4GB RAM = extreme constraint
    - Needs SmolLM2-135M or ultra-optimized 0.5B
    - "Runs on a 2019 budget phone" = incredible WOW factor

  TIER 2 — MAIN BENCHMARK: Snapdragon 8 Gen 1 (e.g., Samsung S22)
    - ARMv9.0-A, NEON + i8mm (KleidiAI acceleration works)
    - 8-12GB RAM = comfortable for 0.5B model
    - Primary demo and benchmarking device
    - No SME2 (needs Cortex-X3/A715 or newer)

  TIER 3 — MAXIMUM OPTIMIZATION PROOF: Galaxy S24 (Snapdragon 8 Gen 3)
    - ARMv9.2-A, NEON + i8mm + SME2
    - Can show 3-tier optimization: NEON → i8mm → SME2 (17-73% speedup)
    - Matches Arm's own benchmark devices
    - Only if user can acquire this device

BATTERY OPTIMIZATIONS:

  1. Sequential execution = lower peak power (only 1 model active)
  2. INT4 quantization = 8x less data movement = proportional energy savings
  3. Adaptive core selection: big cores for prefill, little cores for decode
     - Decode is memory-bound, doesn't need big cores
     - 23% energy savings with no speed loss (MNN-AECS research)
  4. Thermal-aware inference: monitor temp, reduce threads when throttling
     - 44% performance drop from thermal throttling within minutes
  5. mmap loading: only load needed weights into memory
  6. Small models in cache: 35-70MB model fits in L3 cache = 10-100x less energy
  7. Battery-aware UI: show battery level, power saving mode option

BENCHMARK METRICS TO REPORT:
  - Energy per token (mWh/token) — Android BatteryManager API
  - Total energy per code generation task (mWh)
  - Battery percentage consumed per task
  - Sustained vs burst performance (tokens/sec over 5 min)
  - Thermal throttling impact (performance curve over time)
  - Compare: FP32 vs INT4, big cores vs little cores, 1 thread vs 4 threads


================================================================================
9. TIMELINE & PHASES (4+ WEEKS)
================================================================================

Phase 1: Research & Setup (3-5 days)
  [ ] Set up ExecuTorch dev environment
  [ ] Export model to .pte with INT4 quantization
  [ ] Build basic Android app shell with ExecuTorch JNI
  [ ] Verify model loads + generates text on target device
  [ ] Set up Android Studio project, Gradle, NDK, CMake

Phase 2: Core Inference + Single Agent (5-7 days)
  [ ] Implement ExecuTorch inference pipeline in app
  [ ] Build code editor UI with Sora Editor (syntax highlighting)
  [ ] Build chat + code split view layout
  [ ] Get single model generating code from prompts
  [ ] Benchmark: tokens/sec, memory, TTFT, energy/token

Phase 3: Multi-Agent Swarm (5-7 days)
  [ ] Implement sequential execution orchestrator
  [ ] Fine-tune 3 agent variants (Architect, Coder, Validator)
  [ ] Implement memory load/unload lifecycle
  [ ] Add agent activity visualization (colored indicators)
  [ ] Benchmark: memory ceiling stability

Phase 4: Sandbox + Repair Loop (5-7 days)
  [ ] Integrate Python (Chaquopy) + JavaScript (QuickJS) execution sandbox
  [ ] Implement autonomous validation loop
  [ ] Test: generate → execute → repair → success
  [ ] Benchmark: repair success rate, iterations to fix

Phase 5: Optimization & Benchmarks (3-5 days)
  [ ] Run benchmarks on all available devices
  [ ] Compare: FP32 vs INT8 vs INT4, with/without KleidiAI
  [ ] Battery efficiency benchmarks (mWh/token)
  [ ] Generate proof artifact screenshots
  [ ] Optimize for LG Q70 (4GB RAM) if possible

Phase 6: Polish & Submit (3-5 days)
  [ ] Clean repo, README, build instructions
  [ ] Upload models to Hugging Face
  [ ] Record demo video (airplane mode, <3 minutes)
  [ ] Write project description
  [ ] Submit


================================================================================
10. SUBMISSION CHECKLIST
================================================================================

[x] Public GitHub repo with Apache 2.0 license (visible in About section)
    STATUS: DONE — https://github.com/meowshmalloww/PocketIDE

[x] Working Android APK (compiles, installs, runs in airplane mode)
    STATUS: DONE — `./gradlew assembleDebug` BUILD SUCCESSFUL (July 7, 2026)
    - On-device inference via ExecuTorch (.pte) and llama.cpp (.gguf)
    - 7-language code execution sandbox
    - 30+ hardware bridge APIs
    - 3-stage AI optimization pipeline (tuner + KV cache + benchmark)
    - 94 unit tests passing

[ ] Open-source optimized .pte models on Hugging Face
    STATUS: NOT STARTED — users can download models in-app from HuggingFace URLs

[ ] Project Write-Up:
  [x] Project Overview (what + why it should win) — in README.md
  [x] Functionality/Output (what it does + final output) — in README.md
  [x] Setup Instructions (build/run/validate on Arm device) — in README.md
  [ ] Detailed optimization write-up (before/after benchmarks)
    STATUS: PARTIAL — README documents the pipeline, needs measured benchmarks

[ ] Proof Artifacts (Track 3 requirement):
  [x] Benchmark metrics (tokens/sec, TTFT, memory delta) — InferenceBenchmark
      displays per-message in AI chat panel
  [ ] Memory usage comparisons (flat ceiling proof) — KvCacheManager implemented,
      needs sustained benchmark screenshots
  [ ] Model size comparisons (FP32 vs INT8 vs INT4) — documented, needs screenshots
  [ ] Battery efficiency metrics (mWh/token, energy per task) — AdaptiveInferenceTuner
      reads battery level/temp, but energy metrics not yet exported
  [ ] Arm-specific: with/without KleidiAI, with/without SME2 — BackendInfo detects
      capabilities, needs comparative benchmark screenshots
    STATUS: PARTIAL — infrastructure built, needs measured screenshots on real devices

[ ] Optional: 3-minute demo video on YouTube/Vimeo
  (airplane mode → generate code → execute → repair → run)
    STATUS: NOT STARTED


================================================================================
11. OPEN QUESTIONS / RISKS
================================================================================

RISK 1: Qwen2.5-Coder-0.5B ExecuTorch export may fail
  MITIGATION: Fallback to Qwen3-0.6B (officially supported) or
              Llama 3.2 1B (pre-built .pte) or LiteRT-LM

RISK 2: 4GB RAM on LG Q70 may be too tight for 0.5B model
  MITIGATION: Use SmolLM2-135M (35-70MB INT4) on low-end devices
              Adaptive model selection based on available RAM

RISK 3: ExecuTorch may not support runtime LoRA adapter swapping
  MITIGATION: Use 3 separate .pte files loaded sequentially
              Still maintains flat memory ceiling

RISK 4: 0.5B model code quality may be insufficient
  MITIGATION: LoRA fine-tune on code generation datasets
              Self-repair loop catches and fixes errors
              Agent distillation from Qwen2.5-Coder-32B teacher

RISK 5: Thermal throttling degrades performance during sustained inference
  MITIGATION: Thermal-aware inference (reduce threads when hot)
              Sequential execution with cooldown between agents
              Benchmark sustained vs burst performance

RISK 6: Timeline pressure (4+ weeks is enough but ambitious)
  MITIGATION: If time runs short, prioritize:
    1. Optimization benchmarks (INT4 + KleidiAI) — 40% of score
    2. Offline app working in airplane mode — WOW factor
    3. Self-repair loop — WOW factor
    4. Multi-agent swarm — WOW factor
    5. Fine-tuned models on Hugging Face — Impact


================================================================================
12. QUESTIONS TO ASK OTHER AI AGENTS
================================================================================

Copy-paste these to ChatGPT, Gemini, Claude, or Perplexity for second opinions:

PROMPT 1 — Framework choice:
"I'm building an Android app for the Arm AI Optimization Challenge 2026
(Track 3: Mobile AI). I need on-device LLM inference for code generation.
Should I use ExecuTorch (Arm's framework, KleidiAI built in, judges know
it, but Qwen2.5-Coder-0.5B export has had issues) or LiteRT-LM (Google's
framework, easier Qwen support, GPU/NPU acceleration, but less Arm-
specific)? The judges are Arm engineers who teach ExecuTorch. My target
device is a phone with 4GB RAM (LG Q70, Snapdragon 675)."

PROMPT 2 — Model choice:
"I need a sub-1B parameter model for on-device code generation on Android.
Options: Qwen2.5-Coder-0.5B (28% HumanEval, code-specific, but ExecuTorch
export issues), Qwen3-0.6B (officially supported by ExecuTorch, but not
code-specific), SmolLM2-135M (tiny, fits 4GB RAM, but not code-specific).
I plan to LoRA fine-tune for 3 agent roles: Architect (planning), Coder
(code generation), Validator (error analysis). Which model should I use?"

PROMPT 3 — LoRA swapping:
"Does ExecuTorch support runtime LoRA adapter swapping? I want to load
one base model (Qwen2.5-Coder-0.5B INT4) and swap between 3 LoRA adapters
at runtime (Architect, Coder, Validator) to save memory. If not supported,
what's the alternative? Three separate .pte files loaded sequentially?"

PROMPT 4 — Battery optimization:
"I'm building an Android app that runs LLM inference on-device. I need
to optimize for battery efficiency. What techniques work best? I'm
already planning: INT4 quantization, sequential model execution, mmap
loading. What about adaptive core selection (big cores for prefill,
little cores for decode)? How do I implement this in Kotlin/C++ via
JNI? Is there an Android API for CPU core affinity?"

PROMPT 5 — Sora Editor vs alternatives:
"I'm building an Android code editor app with Jetpack Compose. Should I
use Sora Editor (Rosemoe/sora-editor, 1000+ stars, tree-sitter support,
auto-completion, but it's a traditional Android View not Compose) or
compose-code-editor (Qawaz, pure Compose, simpler, no auto-completion)?
I need syntax highlighting for Python and JavaScript, and ideally
diagnostic markers for showing AI-generated errors. Which is better
for a hackathon project?"

PROMPT 6 — Competitive landscape:
"Does any existing Android app do AI-powered code generation + execution
+ autonomous repair entirely on-device, fully offline? Search for Acode
+ AI plugins, Layla, MLC chat apps, Termux + local LLM setups, any
Cursor/Windsurf equivalent for Android. What's the current state of
on-device code generation on mobile?"


================================================================================
                    END OF DECISION DOCUMENT
================================================================================

Review this document, make your choices, then tell me:
  1. Your decisions for each PENDING DECISION (1-6)
  2. Any CONFIRMED DECISIONS you want to change
  3. Any questions or concerns

Once you've decided, we start coding.
