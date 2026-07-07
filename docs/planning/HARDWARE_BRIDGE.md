================================================================================
              POCKETIDE — HARDWARE BRIDGE FEATURE CONCEPT
       AI-Generated Code That Calls Android Hardware + Local Model
================================================================================

Last updated: July 7, 2026

STATUS: IMPLEMENTED — 30+ hardware APIs now available across JS, TS, Python,
Lua, and Java sandboxes. The `hardware` global object is injected into every
scripting engine by `CodeExecutor`. See `HardwareBridge.kt` for the full API.

--------------------------------------------------------------------------------
WHAT THIS IS
--------------------------------------------------------------------------------

PocketIDE's sandbox executes JS/TS/Python/Lua/Java code in isolation. The
hardware bridge extends the sandbox so AI-generated code can call real
Android hardware APIs — all offline.

This is a WOW-factor multiplier built on top of the working self-repair loop
and on-device AI inference pipeline.

--------------------------------------------------------------------------------
THE CONCEPT
--------------------------------------------------------------------------------

User asks the on-device LLM to build something. The LLM generates code.
The code runs in the sandbox. But the sandbox is not limited to pure
algorithms — it can call:

  1. Android hardware APIs (camera, flashlight, GPS, sensors, audio)
  2. The on-device LLM itself (recursive AI calls)
  3. Local storage and file system
  4. UI rendering (WebView for HTML/CSS/JS output)

If the generated code fails (permission denied, API misuse, wrong call),
the self-repair loop catches the error, feeds it back to the LLM, and the
LLM regenerates fixed code. Same loop as the core feature — just with
hardware calls instead of pure logic.

--------------------------------------------------------------------------------
EXAMPLE USE CASES (NOT JUST BLE)
--------------------------------------------------------------------------------

Example 1: Camera
  User: "Take a photo and count the number of faces in it"
  LLM generates JS:
    const photo = device.camera.takePhoto();
    const faces = device.vision.detectFaces(photo);
    print("Found " + faces.length + " faces");
  Sandbox runs → bridge calls Android Camera2 API + ML Kit
  Error? "Camera permission not granted"
  Self-repair → LLM adds permission request → re-run → works

Example 2: Flashlight SOS
  User: "Turn my flashlight into an SOS strobe"
  LLM generates JS:
    const morse = "... --- ...";
    for (const char of morse) {
      if (char === ".") { device.flashlight.on(); sleep(200); device.flashlight.off(); }
      if (char === "-") { device.flashlight.on(); sleep(600); device.flashlight.off(); }
      sleep(200);
    }
  Sandbox runs → bridge calls Android CameraManager.setTorchMode
  Works immediately — instant visual feedback for demo

Example 3: GPS + Local Storage
  User: "Save my current location to a file"
  LLM generates JS:
    const loc = device.location.getCurrent();
    const data = "Lat: " + loc.lat + ", Lng: " + loc.lng + ", Time: " + Date.now();
    device.files.write("my_location.txt", data);
    print("Saved: " + data);
  Sandbox runs → bridge calls Android FusedLocationProvider + internal storage

Example 4: Recursive LLM Call
  User: "Generate a sorting function, then ask yourself if it's correct"
  LLM generates JS:
    const sortCode = "function bubbleSort(arr) { ... }";
    const review = ai.ask("Review this code for bugs: " + sortCode);
    print("AI review: " + review);
    eval(sortCode);
    print(bubbleSort([3, 1, 2]));
  Sandbox runs → bridge calls the on-device LLM from within generated code
  The AI reviews its own generated code — meta-level self-repair

Example 5: Sensor Data
  User: "Read my accelerometer and detect if I'm walking"
  LLM generates JS:
    const samples = device.sensors.read("accelerometer", 100); // 100 samples
    const magnitude = samples.map(s => Math.sqrt(s.x**2 + s.y**2 + s.z**2));
    const variance = computeVariance(magnitude);
    print(variance > THRESHOLD ? "Walking" : "Still");
  Sandbox runs → bridge calls Android SensorManager

Example 6: Audio Tone
  User: "Play a 1000Hz tone for 2 seconds"
  LLM generates JS:
    device.audio.tone(1000, 2000);
    print("Done");
  Sandbox runs → bridge calls Android AudioTrack with generated sine wave

--------------------------------------------------------------------------------
BRIDGE ARCHITECTURE (IMPLEMENTED)
--------------------------------------------------------------------------------

The bridge is a single Kotlin class (`HardwareBridge.kt`, ~640 lines) injected
into each sandbox runtime as the global `hardware` object.

  ┌─────────────────────────────────────────────────┐
  │         AI-Generated Code (JS/TS/Python/Lua/Java) │
  │  hardware.setFlashlight(true)                    │
  │  hardware.readSensor("accelerometer", 1000)      │
  │  hardware.speak("Hello world")                   │
  └───────────────────┬─────────────────────────────┘
                      │ `hardware` global object
  ┌───────────────────▼─────────────────────────────┐
  │         HardwareBridge (Kotlin)                  │
  │  CameraManager     → flashlight, camera info     │
  │  Vibrator          → vibrate, vibratePattern     │
  │  BatteryManager    → battery level, temp, charge │
  │  ClipboardManager  → clipboard get/set           │
  │  Settings.System   → screen brightness           │
  │  ConnectivityManager → network type, online      │
  │  StatFs            → storage free/total          │
  │  TextToSpeech      → TTS speak/stop              │
  │  ToneGenerator     → audio beep                  │
  │  NotificationManager → notifications             │
  │  LocationManager   → GPS one-shot read           │
  │  BluetoothManager  → paired devices list         │
  │  SensorManager     → one-shot sensor reads       │
  │  NanoHTTPD         → localhost HTTP server       │
  │  File (filesDir)   → sandbox file I/O            │
  └───────────────────┬─────────────────────────────┘
                      │
  ┌───────────────────▼─────────────────────────────┐
  │              Android OS APIs                     │
  └─────────────────────────────────────────────────┘

Injection per language:
  JavaScript/TypeScript (Rhino): ScriptableObject.putProperty(scope, "hardware", bridge)
  Python (transpiled to JS): same as JS — `hardware` is available
  Lua (LuaJ): globals["hardware"] = bridge table
  Java (BeanShell): interpreter.set("hardware", bridge)

--------------------------------------------------------------------------------
PERMISSIONS (in AndroidManifest.xml)
--------------------------------------------------------------------------------

Declared:
  - VIBRATE
  - CAMERA (flashlight)
  - ACCESS_FINE_LOCATION
  - ACCESS_COARSE_LOCATION
  - BLUETOOTH
  - BLUETOOTH_CONNECT
  - POST_NOTIFICATIONS
  - WRITE_SETTINGS (screen brightness)
  - WAKE_LOCK (keep screen on)
  - RECORD_AUDIO (reserved for future audio capture)
  - HIGH_SAMPLING_RATE_SENSORS

No runtime permissions needed: sensors, network status, battery, clipboard,
storage stats, screen info.

--------------------------------------------------------------------------------
SELF-REPAIR INTERACTION
--------------------------------------------------------------------------------

The existing self-repair loop already works:

  Generate → Execute → Catch errors → Feed to LLM → Regenerate

With the hardware bridge, errors now include:
  - "Camera permission denied" → LLM adds permission check
  - "Sensor not available on this device" → LLM falls back to alternative
  - "File not found at /sdcard/..." → LLM fixes path
  - "AudioTrack init failed: invalid frequency" → LLM fixes parameter
  - "ML Kit model not loaded" → LLM removes vision call, uses pure JS

The self-repair loop is the same code path — it just handles a wider
range of error types. No new loop logic needed.

--------------------------------------------------------------------------------
LANGUAGE SUPPORT (IMPLEMENTED)
--------------------------------------------------------------------------------

The bridge is language-agnostic. The `hardware` global object is injected
into each sandbox runtime:

  JavaScript/TypeScript (Rhino):
    ScriptableObject.putProperty(scope, "hardware", hardwareBridge)
    // Generated code calls: hardware.setFlashlight(true)

  Python (transpiled to JS via Rhino):
    # Same as JS — `hardware` is available in transpiled output
    hardware.set_flashlight(True)  # transpiled to hardware.setFlashlight(true)

  Lua (LuaJ):
    globals["hardware"] = hardwareBridge
    -- Generated code calls: hardware.setFlashlight(true)

  Java (BeanShell):
    interpreter.set("hardware", hardwareBridge)
    // Generated code calls: hardware.setFlashlight(true);

The bridge API surface is the same regardless of language. The AI system
prompts include the full hardware API reference so the LLM generates correct
calls for whichever language the user selects.

--------------------------------------------------------------------------------
DEMO POTENTIAL (JUDGING CRITERIA IMPACT)
--------------------------------------------------------------------------------

WOW Factor (25 pts):
  "Watch this — I tell my phone to build an SOS strobe, and it writes
   code that turns on my flashlight. No internet. No laptop. The AI
   wrote it, ran it, and it works. Oh, it forgot the permission? It
   caught its own error and fixed it."

Potential Impact (20 pts):
  First mobile IDE where AI-generated code controls real hardware.
  Reusable bridge pattern for other edge AI projects.
  Open-source bridge API definition.

Technological Implementation (40 pts):
  The bridge itself is NOT the optimization story — the model + quant +
  KleidiAI is. But the bridge demonstrates the optimization is real:
  the model is fast enough to generate + repair hardware code in
  real-time on-device.

--------------------------------------------------------------------------------
BUILD STATUS
--------------------------------------------------------------------------------

Phase 1 (CORE): DONE
  - ExecuTorch + llama.cpp dual runtime
  - 7-language code execution sandbox
  - Self-repair loop
  - AI optimization pipeline (AdaptiveInferenceTuner, KvCacheManager,
    InferenceBenchmark, AgentContextPruner)
  - 94 unit tests passing
  - `./gradlew assembleDebug` BUILD SUCCESSFUL

Phase 2 (HARDWARE BRIDGE): DONE
  - 30+ hardware APIs implemented and wired into all 5 scripting engines
  - System prompts updated with full hardware API reference
  - AndroidManifest permissions declared
  - Self-repair loop catches hardware API errors

Phase 3 (STRETCH): NOT STARTED
  - Recursive AI calls (ai.ask from generated code)
  - WebView rendering for HTML/CSS output
  - Mac Desktop port (Compose Desktop)
  - LoRA fine-tuning

--------------------------------------------------------------------------------
RISKS
--------------------------------------------------------------------------------

  Risk: Bridge takes too long to build
  Mitigation: Start with 4 simple APIs (flashlight, vibration, GPS, camera)
  Each API bridge is ~50-100 lines of Kotlin. 4 APIs = ~400 lines.

  Risk: Android permissions complexity
  Mitigation: Pre-declare all permissions, request at startup, not per-execution.

  Risk: Self-repair loop gets stuck on hardware errors
  Mitigation: Limit repair iterations to 3. After 3 fails, show error + last attempt.

  Risk: Scope creep delays Phase 1
  Mitigation: DO NOT START Phase 2 until Phase 1 is working and benchmarked.
  The bridge is worthless without a working on-device model.

  Risk: 1.5B model can't generate good hardware API code
  Mitigation: Include bridge API docs in the system prompt so the LLM knows
  the exact function signatures. Fine-tune on Android API examples if needed.

================================================================================
