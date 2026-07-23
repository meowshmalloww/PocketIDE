# PocketIDE long context validation

This document separates implemented optimization from results that still require a
physical Android run. PocketIDE does not simulate long context or report an indexed
project as native model context.

## Supported limits

| Runtime and model | Declared or exported limit | PocketIDE behavior |
|---|---:|---|
| Qwen2.5 Coder 1.5B Instruct GGUF | 32,768 tokens | Clamps to model metadata and device memory |
| IBM PowerMoE 3B GGUF | 4,096 tokens | Clamps to GraniteMoE metadata and requires the mapped expert working set to fit available RAM |
| Downloadable Llama 3.2 1B SpinQuant PTE | 2,048 tokens | Preserves the fixed export limit |
| Other GGUF models | GGUF metadata limit | Uses that model's own declared limit |

The official Qwen GGUF card states a 32,768 token full context. Its 131,072 token
example applies to non GGUF deployment and is not evidence that this checkpoint can
retain 131K tokens. The downloadable PTE export recipe sets both `max_seq_length` and
`max_context_length` to 2,048. Changing a slider cannot change that exported graph.
IBM's PowerMoE configuration declares 4,096 positions. Its 800M active parameters
reduce expert computation per token; they do not turn its 1.942 GB Q4_K_S file into a
1 GB weight mapping or extend the model to 16K or 32K.

Sources:

* [Qwen2.5 Coder 1.5B Instruct GGUF model card](https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF)
* [IBM PowerMoE 3B model card](https://huggingface.co/ibm-research/PowerMoE-3b)
* [ExecuTorch LLM export documentation](https://docs.pytorch.org/executorch/stable/llm/export-llm.html)
* [Published Llama 3.2 SpinQuant PTE export recipe](https://huggingface.co/executorch-community/Llama-3.2-1B-Instruct-SpinQuant_INT4_EO8-ET/blob/main/Export_Recipe_Llama_3_2_1B_Instruct_SpinQuant_INT4_EO8.ipynb)

## Real GGUF memory optimization

Qwen2.5 Coder 1.5B has 28 layers, 2 KV heads, and a head dimension of 128. Its
unquantized F16 KV cache uses 28 KiB per token. PocketIDE now selects a real Q8_0 KV
cache above 4K and requests Flash Attention from llama.cpp. The runtime then reports
the context, cache types, batch, and Flash Attention mode it actually created. A
mismatch fails visibly instead of being recorded as successful acceleration.

| Native context | F16 KV cache | Q8_0 KV cache used above 4K |
|---:|---:|---:|
| 4,096 | 112 MiB | 60 MiB |
| 8,192 | 224 MiB | 119 MiB |
| 16,384 | 448 MiB | 238 MiB |
| 32,768 | 896 MiB | 476 MiB |
| 65,536 | 1,792 MiB | 952 MiB |
| 131,072 | 3,584 MiB | 1,904 MiB |

The 65K and 131K rows are memory arithmetic, not support claims for the selected Qwen
model. They also exclude model weights, native work buffers, Android, language
runtimes, and the editor. Flash Attention reduces attention working memory; it does
not remove the persistent KV cache. llama.cpp documents `--cache-type-k`,
`--cache-type-v`, and `--flash-attn` in its official CLI reference.

Sources:

* [llama.cpp CLI options](https://github.com/ggml-org/llama.cpp/blob/master/tools/cli/README.md)
* [llama.cpp tensor encoding sizes](https://github.com/ggml-org/llama.cpp/wiki/Tensor-Encoding-Schemes)

## Device policy

PocketIDE applies these upper bounds before checking the selected model and current
available memory:

| Total device RAM | Native context ceiling |
|---:|---:|
| Up to 4.5 GiB | 8K |
| Above 4.5 through 6.5 GiB | 16K |
| Above 6.5 through 10 GiB | 32K |
| Above 10 through 14 GiB | 65K |
| Above 14 GiB | 131K |

These are safety ceilings, not promises. GGUF weights are loaded with `mmap=true` and
`mlock=false`. The planner therefore keeps two separate checks. Immediate Android
`availMem` must cover estimated KV and native runtime headroom, while total device
memory must cover the mapped model, KV, existing process PSS, and an Android reserve.
This avoids falsely requiring every file byte as immediate anonymous memory while
still refusing a mapping that exceeds safe physical capacity. Android low memory
state refuses a cold or profile changing load. An already loaded matching profile
may be reused so a temporary low memory callback does not force another large
allocation.

Sparse MoE models receive an additional sustained working set guard. Active parameter
count describes computation per token, not the total expert weights that routing may
touch across a generation. For MoE cold loads, immediate headroom therefore includes
the complete mapped GGUF weight size, KV cache, and Android runtime reserve. This is
intentionally stricter than the dense model mmap admission rule.

The exact local `PowerMoE-3b.Q4_K_S.gguf` file was also validated outside PocketIDE
with a separate llama.cpp CPU runtime. It loaded at 4,096 context and generated real
output, ruling out a corrupt GGUF. Its observed desktop process working set increased
from 876.7 MiB after load to 2,106.3 MiB after a 96 token generation. This is diagnostic
evidence, not an Android performance benchmark, but it demonstrates why a phone
reporting only about 1,294 MiB available can be killed despite 800M active parameters.

For the 4 GB LGE test phone, 8K Q8 is the highest new candidate. It is not validated
until the physical test below succeeds. The current PTE remains 2K because its export
is fixed and uses an estimated 128 MiB FP32 KV cache at that length.

## Large project memory

PocketIDE now scans the local project and deterministically selects query relevant
files, symbols, imports, errors, and overlapping source chunks. This can search a
project containing far more source tokens than the model context, but only selected
evidence is inserted into the native prompt. The settings label this as indexed
project memory, and the retrieved prompt records scanned source size separately from
the active native context.

This approach follows repository retrieval research such as
[RepoCoder](https://arxiv.org/abs/2303.12570) and
[Repoformer](https://proceedings.mlr.press/v235/wu24a.html). It is not a claim that the
transformer retained every indexed token.

## Physical validation gate

1. Select the Qwen GGUF and request 8K on the 4 GB LGE phone.
2. Close other apps, cold start PocketIDE, and run Quick Benchmark.
3. Confirm the dashboard and JSON report `effective_context = 8192`, model limit
   `32768`, K and V cache `q8_0`, Flash Attention `on`, and about 119 MiB estimated KV.
4. Complete two cold AI generations and one multi file code generation without an
   Android process exit.
5. Run Deep and Sustained evidence. Record throughput, first token latency, PSS,
   battery change, temperature, and thermal state.
6. If native initialization refuses the plan or Android terminates the process, keep
   4K as the validated limit and retain the failed report as honest evidence.
7. On a device with at least 8 GB RAM, repeat at 16K and then 32K. Do not claim 32K
   from a build or unit test alone.

StreamingLLM and YaRN are relevant research directions, but neither is used to label
this checkpoint as 65K or 131K. Streaming methods discard old token detail, while
context extrapolation needs model specific adaptation and quality evaluation.

* [StreamingLLM](https://arxiv.org/abs/2309.17453)
* [YaRN](https://arxiv.org/abs/2309.00071)
