# Fixed physical reliability protocol

Run this protocol on the final APK with Qwen2.5 Coder 1.5B Instruct Q4_0, temperature zero, the saved device thread calibration, and a new chat for every task. Do not retry silently.

Record for each task: first-attempt file completion, parser acceptance, validator repairs, execution result, expected-output match, elapsed generation time, process exit, and final pass or fail.

| ID | Capability | Fixed task | Pass condition |
|---|---|---|---|
| 1 | Python output | Create `main.py` that prints the sum of 17 and 25. | File saved, executes, prints 42. |
| 2 | Python input | Create a two-choice menu that accepts `1` and `q` repeatedly. | Same process accepts both inputs and exits normally. |
| 3 | Input validation | Ask for a positive number and handle invalid text without crashing. | Invalid text is rejected and a valid second input succeeds. |
| 4 | Multiple Python files | Create `main.py` and `storage.py` with a real sibling import. | Both files exist and `main.py` imports and calls the sibling API. |
| 5 | Persistence | Save and reload one JSON record with the standard library. | A second execution restores the record. |
| 6 | Hardware bridge | Read battery and network, then call vibration and toast without importing hardware. | Real values print and no `ModuleNotFoundError` occurs. |
| 7 | Hardware failure isolation | Call two hardware methods in separate `try/except` blocks. | Failure of one method does not skip the next. |
| 8 | Single-file web app | Create one HTML checklist with inline CSS, working buttons, percentage, and localStorage. | Buttons change state, percentage updates, and reload restores state. |
| 9 | Three-file web app | Create `index.html`, `style.css`, and `app.js`. | Preview loads; every script selector matches markup; interaction works. |
| 10 | JavaScript runtime | Create ES5 JavaScript that reads one terminal line and prints it uppercase. | Executes in Rhino without unsupported syntax. |
| 11 | TypeScript subset | Create simple typed variables and a typed function without modules or classes. | Compatibility stripping succeeds and Rhino executes the result. |
| 12 | Lua runtime | Read one value with `io.read()` and print it. | Terminal input reaches the same Lua process. |
| 13 | SQL runtime | Create a table, insert two rows, and select their sum. | SQLite returns the expected value. |
| 14 | Repair | Start with an unterminated generated file and ask PocketIDE to finish it. | Existing valid files remain untouched; only a complete repaired file is applied. |
| 15 | Cancellation and reload | Stop a long generation, then start a short generation. | Stop returns the UI to an idle state and the next request completes without restarting the app. |

Publish the denominator and every failure. For example, a truthful report should say `12/15 first attempt, 14/15 after one bounded repair, 0 process crashes`, not `93 percent reliable` without the raw task table.
