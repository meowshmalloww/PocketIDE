# PocketIDE model backends

PocketIDE supports two different on-device model paths. A file extension is not proof of a particular accelerator; the model export and bundled runtime decide the backend.

## GGUF

GGUF models run through KotlinLlamaCpp/llama.cpp. The current app sets `n_gpu_layers=0`, so this is a CPU path. The runtime selects an Arm64 native library from the phone's reported ISA features, including dot-product or i8mm variants when present. The benchmark records the selected library plus the requested and context load configured thread counts. The wrapper does not expose native worker introspection.

The current runtime does not expose proof that a KleidiAI kernel was invoked. PocketIDE therefore reports KleidiAI as **not verified**, even when the CPU has relevant ISA features.

## ExecuTorch PTE on Arm CPU

An open test candidate is [Llama 3.2 1B Instruct SpinQuant INT4](https://huggingface.co/executorch-community/Llama-3.2-1B-Instruct-SpinQuant_INT4_EO8-ET/tree/main). Its repository contains:

- `Llama-3.2-1B-Instruct-SpinQuant_INT4_EO8.pte` (about 1.14 GB)
- `tokenizer.model`

This is an ExecuTorch/Arm CPU artifact, not a Qualcomm NPU artifact. It uses the Llama 3.2 Community License. Runtime/export compatibility and PocketIDE integration still require physical-device testing before making performance claims.

PocketIDE can benchmark a compatible PTE directly. It records ExecuTorch's real prompt/generated token counters, resets the model context before every repetition, and explicitly reports that the Android 1.0.1 API does not expose its worker count or prove which delegate executed. The llama.cpp `pp/tg` microbenchmark is GGUF-only and is not presented as PTE evidence.

## Qualcomm QNN / NPU

A generic `.pte` cannot be relabeled as an NPU model. Qualcomm execution requires all of the following:

1. Export and lower the model specifically to the ExecuTorch Qualcomm backend for the target SoC.
2. Bundle the Qualcomm-enabled ExecuTorch AAR and matching QNN runtime libraries in the APK.
3. Keep the QNN SDK used for compilation compatible with the QNN runtime shipped in the app.
4. Verify the packaged QNN libraries and device logs; successful `.pte` loading alone is not proof of NPU execution.

The official [ExecuTorch Qualcomm backend guide](https://docs.pytorch.org/executorch/stable/backends-qualcomm.html) compiles models for a named target SoC. Its current [Llama 3 QNN tutorial](https://docs.pytorch.org/executorch/stable/llm/build-run-llama3-qualcomm-ai-engine-direct-backend.html) produces `llama_qnn.pte`, requires QNN SDK 2.28 or newer, and currently targets a 16 GB Qualcomm device. PocketIDE does not yet bundle this QNN-specific runtime, so the app must not claim NPU acceleration today.
