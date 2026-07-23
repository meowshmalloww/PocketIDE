# Verification evidence

This record separates deterministic software verification from physical Arm performance. It does not convert emulator results into phone performance claims.

## Final local verification

Date: July 22, 2026

Command:

```powershell
.\gradlew.bat verifySubmission
```

Result:

* 213 JVM and Robolectric tests passed
* 0 failures, 0 errors, and 0 skipped
* Android lint completed with 0 errors
* Debug APK assembly completed
* The pinned KotlinLlamaCpp source fork compiled for both `arm64-v8a` and `x86_64`

Android command on a clean API 36 x86_64 AVD:

```powershell
.\gradlew.bat connectedDebugAndroidTest `
  --project-prop "android.testInstrumentationRunnerArguments.gguf_model_path=/data/local/tmp/model-smoke.gguf" `
  --project-prop "android.testInstrumentationRunnerArguments.gguf_context=512"
```

Result:

* 19 Android instrumentation tests passed
* 0 failures, 0 errors, and 0 skipped
* The suite covered CPython execution, interactive terminal input, sibling imports, JavaScript, the supported TypeScript subset, Lua, SQL, Android shell, BeanShell Java, localhost preview, hardware bridge behavior, benchmark UI, cancellation, and process-exit recovery
* A real 1,066,227,264 byte Qwen2.5 Coder 1.5B Q4_0 GGUF with SHA-256 `aa8353e0d0fca3a0041828701e90db7635197400f040676d11d7798665fa316e` loaded through JNI, generated non-empty output, and released successfully
* A second real-model test verified that selecting a replacement releases the previous native GGUF mapping before memory planning

The model smoke run used a 512 token context on the x86_64 emulator. It proves the packaged JNI and lifecycle path, not Arm throughput. The physical LGE benchmark reports remain the source for Arm performance, memory, thermal, and device-energy claims.

## Public clean-clone reproduction

Public commit: `8c2cb6235cf0e22d92b4874e007873959f941c9b`

The repository was cloned into a new temporary directory with no project `local.properties`. `ANDROID_HOME` was set to the installed Android SDK as documented in the README, then the public verification task was run with no source or configuration edits:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat verifySubmission
```

Result:

* Working tree remained clean
* 30 JVM test suites ran
* 213 tests passed with 0 failures, 0 errors, and 0 skipped
* Lint completed with 0 errors and 40 non-blocking warnings
* Default dual-ABI debug APK assembly completed at 138,394,400 bytes

The first automated attempt was externally terminated by a command timeout during the from-scratch C++ build. That interruption left a partial APK and a Windows lint-cache file lock. Following Gradle's documented recovery, the daemon was stopped, the temporary app outputs were cleaned, and the same untouched clone completed successfully with `--no-daemon`. No project file was changed to obtain the passing result.

## Final Arm64 demo artifact

The final phone-only artifact was assembled with:

```powershell
.\gradlew.bat --project-prop "pocketide.abi=arm64-v8a" :app:assembleDebug
```

Artifact: `PocketIDE-demo-final.apk`

* Size: 92,248,182 bytes
* SHA-256: `3d707cfd9a71ac9bd5914120675a6518c64bfa6f58f24f13b9e8ef27986f8303`
* Application ID: `com.pocketide`
* Version: `1.0` (`1`)
* Minimum Android API: 26
* Native ABI: `arm64-v8a` only
* APK Signature Scheme v2 verification: passed
* 16 KiB page-alignment verification: passed

The APK uses the Android debug certificate and is intended for direct hackathon testing, not Play Store distribution.

## Final video artifact

`PocketIDE Video Final.mp4` was rendered from the narrated physical-phone recording without replacing the demonstrated outputs. Only inactive generation waits and a settings detour were removed. The terminal input sequence remains visible.

* Duration: 107.29 seconds
* Video: H.264, 1920 by 1080, 24 frames per second
* Audio: AAC, 48 kHz stereo
* Integrated loudness: -16.4 LUFS
* True peak: -4.4 dBFS
* SHA-256: `f735e64600d67d2987e27b0293aee37a0e85e669a77489ded74209ef921f83a1`
* Full decode validation: passed with no reported media errors

## What is not claimed

The automated suite validates software contracts and known runtime paths. It is not a statistically valid coding-quality pass rate for every prompt a 1.5B model may receive. Model output quality still depends on the prompt, model, quantization, context, and device. A fixed physical prompt-quality suite should publish its failures and retries before PocketIDE claims a model task success percentage.
