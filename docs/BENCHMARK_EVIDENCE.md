# PocketIDE physical device benchmark evidence

This document records the submission headline from completed PocketIDE schema 9 reports captured on July 18, 2026. It separates measured facts, derived metrics, and unsupported acceleration claims. The [exact physical-phone text exports and SHA-256-indexed JSON transcription](benchmarks/README.md) are published beside this summary.

## Test system

| Item | Value |
|---|---|
| Device | LGE LM-Q620 |
| SoC | Qualcomm SM6150 |
| Android | 12, API 31 |
| ABI | arm64-v8a |
| Model | Qwen2.5 Coder 1.5B Instruct GGUF |
| Quantization | Q4_0 |
| Model file size | 1,016.8 MB |
| GGUF context and batch | 1536 and 128 |
| llama.cpp path | CPU, zero GPU layers |
| Loader-selected library | `librnllama_v8_2_dotprod.so` |

The native wrapper does not expose a live worker count. Thread values below are therefore described as **load configured**, not actual or verified native workers.

## Corrected Deep result

The earlier benchmark changed a generation setting while the wrapper fixed `n_threads` when the native context was created. Schema 9 corrected this by recreating and warming the native context for every configured profile. Saved results from older protocols were invalidated.

The Deep run screened configured profiles from one through eight threads, confirmed the finalists in counterbalanced order, and selected two threads.

| Metric | Four configured threads | Two configured threads | Change |
|---|---:|---:|---:|
| Measured generations | 2 | 6 | |
| Median decode throughput | 8.12 tok/s | 11.73 tok/s | +44.4% |
| Mean decode throughput | 8.12 tok/s | 11.66 tok/s | |
| Decode range | 8.11 to 8.14 tok/s | 11.22 to 11.86 tok/s | |
| Decode coefficient of variation | 0.2% | 1.9% | |
| Median TTFT | 132 ms | 87 ms | -34.1% |
| TTFT p95 | 133 ms | 96 ms | |
| Mean maximum sampled process PSS | 1,780.7 MB | 1,915.5 MB | +7.6% |
| Median process CPU to wall ratio | 3.62x | 1.98x | |

The selected profile increased sampled PSS. PocketIDE reports that tradeoff instead of presenting throughput as a memory improvement.

### Derived process CPU work

Process CPU time is summed across threads. From the measured per-generation counters:

* Two-thread mean process CPU time was 21,684.8 ms for 128 output tokens, or 169.4 ms per output token.
* Four-thread mean process CPU time was 57,035.0 ms for 128 output tokens, or 445.6 ms per output token.
* The selected two-thread profile therefore used 62.0% less measured process CPU time per output token.

This is a process CPU-work metric. It is not app-only electrical energy and is not presented as a battery-life percentage.

## Native llama.cpp microbenchmark

At the selected two-thread profile, five repetitions plus an internal warmup measured:

| Native scope | Result |
|---|---:|
| Prompt processing, pp128 | 46.82 tok/s |
| Token generation, tg32 | 11.88 tok/s |
| Combined reported score | 29.46 tok/s |

The native microbenchmark measures kernel throughput. It is not TTFT or full application latency.

## Sustained evidence

The separate Sustained run used the same device, model, context, and selected profile.

| Metric | Result |
|---|---:|
| Selected profile median | 11.48 tok/s |
| Four-thread comparison | 8.20 tok/s |
| Selected profile improvement | 40.0% |
| First-half median | 11.37 tok/s |
| Second-half median | 11.65 tok/s |
| Throughput retained | 102.5% |
| Sustained-segment battery temperature | 28.1 C to 28.9 C |
| Maximum Android thermal status | none |

The one-decrement Android fuel-gauge energy estimate is intentionally not used as a submission headline. Android `BatteryManager` counters describe the whole phone and cannot isolate PocketIDE from display, radios, and other system work.

## Timing and reporting boundaries

* Warmups are excluded from reported profile statistics.
* Decode tok/s starts after the first emitted token.
* TTFT excludes model loading and prompt formatting.
* The fixed GGUF prompt can reuse the runtime prefix cache after warmup.
* Process PSS is the maximum of start, first-token, and finish samples.
* The benchmark records requested and load-configured thread counts separately.
* Normal chat uses the saved winning profile for the exact device, model file, runtime version, and loader-selected Arm native library.

## Acceleration statement

The measured GGUF path is CPU inference with an Armv8.2 dot-product library selected by the loader. GPU layers were zero. PocketIDE does not claim verified NPU, GPU, KleidiAI kernel, ExecuTorch delegate, or native live-worker acceleration from these reports.

PTE results are kept separate because the tested PTE file is a different model and Android ExecuTorch `LlmModule` does not expose context, worker, or delegate controls for this export. It is not used as a before-and-after comparison for the GGUF optimization claim.

## Reproduce in PocketIDE

1. Install the final PocketIDE APK on an Arm64 Android phone.
2. Import and select a compatible GGUF model.
3. Close other applications, unplug the phone, and let it cool.
4. Open AI Assistant and the benchmark dialog.
5. Run Quick to establish a candidate profile.
6. Run Deep evidence to screen and confirm profiles.
7. Run Sustained evidence to measure retention and thermal behavior.
8. Inspect the in-app Summary and copy the full text report and JSON.
