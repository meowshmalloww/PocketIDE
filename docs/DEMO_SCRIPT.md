# PocketIDE Demo Transcript

This script matches the two programs shown in the recorded Devpost demonstration. The improved final edit is 1 minute 47 seconds and places concise evidence cards beside the original physical-phone footage. Use this transcript for captions, the YouTube description, or a new recording if one is needed.

## What the video demonstrates

The Emergency Phone Signal proves that a locally generated Python file can read real phone state and trigger Android actions through PocketIDE's injected `hardware` object. The Emergency Action Menu proves that a generated program can remain active, accept repeated terminal input, choose different code paths, and exit normally.

The examples are deliberately compact. They are reliable enough for a small on device model, easy to verify visually, and demonstrate the complete local workflow instead of only showing an AI chat response. The emergency content is illustrative software output, not certified safety advice.

## Prompt one: Emergency Phone Signal

```text
Create phone_signal.py. Do not import anything. PocketIDE provides a global hardware object. Print EMERGENCY PHONE CHECK. In one try except print Battery and hardware.batteryLevel(). In another try except print Network and hardware.networkType(). In separate try except blocks call hardware.vibrate(300) and hardware.toast("Emergency check complete"). On every failure print Unavailable. Keep it under 24 lines. Create runnable code only.
```

Visible proof:

* PocketIDE creates `phone_signal.py` locally.
* Running it prints `EMERGENCY PHONE CHECK`.
* The terminal shows the phone's battery and network results, or `Unavailable` if an individual call fails.
* The phone vibrates and displays `Emergency check complete` as an Android toast.

## Prompt two: Emergency Action Menu

```text
Create main.py. Build an emergency action menu. Repeatedly print 1 FIRE, 2 FLOOD, 3 MEDICAL, and Q QUIT. Read one choice with input. Accept each number or word. For fire print Leave now and call emergency services. For flood print Move to higher ground. For medical print Call emergency services and give first aid if trained. For q print Stay safe and exit. Otherwise print Invalid choice. Use no packages and keep it under 25 lines. Create runnable code only.
```

Recorded terminal inputs:

```text
1
2
q
```

Visible proof:

* `main.py` keeps running after each choice.
* Input `1` selects the fire branch.
* Input `2` selects the flood branch.
* Input `q` prints `Stay safe` and exits normally.

## Narration transcript

```text
This is an LGE LM Q620 running Android 12 on a Qualcomm SM6150 Arm64 platform. This physical phone is in airplane mode, with no inference server.

PocketIDE runs Qwen2.5 Coder 1.5B Q4_0 locally. The prompt, inference, source code, and execution all remain on the device.

First, the model creates phone_signal.py. The program reads the phone's real battery and network state, then activates vibration and an Android toast through PocketIDE's hardware bridge.

Next, it creates an emergency action menu. The same process stays active while I enter fire, flood, and quit, follows each branch, and exits normally. This proves real interactive terminal execution.

The optimization is measured on this same phone. The Deep benchmark selected two threads and improved decode speed from 8.12 to 11.73 tokens per second, which is 44.4 percent faster. Time to first token dropped from 132 to 87 milliseconds, a 34.1 percent reduction. CPU time per output token fell by 62 percent.

In the sustained test, throughput improved by 40 percent, from 8.20 to 11.48 tokens per second. Temperature rose by only 0.8 degrees Celsius, with no reported thermal throttling.

These are real CPU results using the selected Armv8.2 dot product library. I make no NPU or GPU claim, and the complete reports and JSON are public.

PocketIDE turns an Arm phone into a private coding tool when a laptop, server, or reliable network is unavailable.
```

## Recommended shot order

1. Show airplane mode and the active local Qwen model.
2. Show the Emergency Phone Signal prompt and the real generation.
3. Open and run `phone_signal.py`. Keep the battery, network, and toast visible long enough to read.
4. Open and run `main.py`. Enter `1`, `2`, and `q` while the same terminal process remains active.
5. Show the benchmark Summary, then briefly show the exportable Report and JSON.
6. Finish on PocketIDE and the public repository address.

Keep the finished video below three minutes. If generation footage is shortened, label the sped up portion clearly and use footage from the same real on device run. Do not describe either program as a certified emergency or safety tool.

## Final edited video

The final edit preserves the original narration, generated files, hardware result, and repeated terminal input. It removes only inactive model wait time and an unrelated settings detour. The side panel makes the following facts readable on a desktop display:

* Physical LGE LM Q620 running offline with no inference server
* Qwen2.5 Coder 1.5B Q4_0 running locally
* Generated Python reaching battery, network, vibration, and toast APIs
* One live terminal process accepting repeated input
* Deep result of 8.12 to 11.73 tok/s and 132 to 87 ms TTFT
* Sustained result of 11.48 tok/s with a 0.8 C temperature increase and no reported throttling
* CPU-only scope with no NPU or GPU claim

The final media file is `C:\Users\wenje\Downloads\PocketIDE Video Final.mp4`. Upload that file, not the longer source recording.
