# PocketIDE Demo Script

Use Code mode with the Qwen GGUF model and Single agent mode for the short recording. Run Quick Benchmark once before recording so PocketIDE saves the fastest measured thread count for this phone and model.

## Before recording

1. Close other applications and let the phone cool.
2. Turn battery saver off.
3. Confirm that Qwen2.5 Coder 1.5B Q4_0 is active in Settings.
4. Run Quick Benchmark and keep its Report and JSON.
5. Unplug the phone, keep screen brightness fixed, run Sustained evidence, and keep its Report and JSON. Do this before recording rather than making judges wait through the suite.
6. Force close and reopen PocketIDE so the demonstration includes a real cold model load using the saved calibration.

## Prompt one: interactive calculator

```text
Create one Python file named main.py. Build an interactive calculator that repeatedly asks the user to enter +, minus, multiply, divide, power, or q. For every operation ask for two numbers, validate numeric input, handle division by zero, print the result, and return to the menu. Entering q must print Goodbye and exit. Use input only, use no external packages, and keep the complete program under 50 lines.
```

Run the generated file and enter:

```text
multiply
12
8
q
```

The terminal must show `96` and then `Goodbye`.

## Prompt two: Android device health scanner

```text
Create one Python file named phone_check.py. Build a device health scanner using the PocketIDE hardware object. Read getDeviceInfo, batteryLevel, isCharging, networkType, storageFree, and readSensor with accelerometer for 1000 milliseconds. Give every hardware call its own try except block so a missing sensor cannot stop the program. Print a clear report, vibrate for 200 milliseconds, and show a toast containing Scan complete. Use no external packages and keep the complete program under 45 lines.
```

Run the generated file. The script must print the available phone data, finish even if the accelerometer is unavailable, vibrate, and show the `Scan complete` toast.

## Recording order

1. Show the active local model in Settings.
2. Turn off network access or state clearly that inference is local.
3. Generate and run the calculator, including terminal input.
4. Generate and run the device health scanner.
5. Open Benchmark and show the saved Summary cards for decode speed, TTFT, selected threads, sustained retention, device energy, temperature, and Arm runtime evidence. Briefly open Report to prove the complete evidence remains available.
6. End on the editor and terminal, not on a static slide.

Do not record Swarm mode for the three minute submission. It performs several sequential generations and is better shown in a longer follow up demonstration.
