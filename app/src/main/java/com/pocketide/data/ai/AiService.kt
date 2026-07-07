package com.pocketide.data.ai

import android.content.Context

sealed class AiResult {
    data class Success(
        val content: String,
        val statsJson: String? = null,
        val benchmark: InferenceBenchmark.BenchmarkResult? = null,
        val tuning: InferenceTuning? = null,
    ) : AiResult()
    data class Error(val message: String) : AiResult()
}

data class ChatTurn(val role: String, val content: String)

/**
 * On-device AI inference service. Routes to the appropriate [LlmRunner]
 * based on the model file extension:
 * - `.pte` → [ExecutorchLlmRunner] (ExecuTorch, NPU acceleration on Snapdragon)
 * - `.gguf` → [LlamaCppRunner] (llama.cpp, broad model ecosystem from HuggingFace)
 *
 * No network calls — fully offline.
 *
 * Optimization pipeline:
 * 1. [AdaptiveInferenceTuner] reads thermal state, battery, memory, and CPU cores
 *    to produce an [InferenceTuning] that adjusts seqLen and thread count.
 * 2. [KvCacheManager] checks whether the KV cache will fit in available heap memory,
 *    reducing seqLen or triggering a context reset if needed.
 * 3. [InferenceBenchmark] measures TTFT, tokens/sec, and memory delta in real time.
 */
class AiService(
    private val executorchRunner: ExecutorchLlmRunner,
    private val llamaCppRunner: LlamaCppRunner,
    private val config: AiConfig,
    private val context: Context? = null,
) {

    private val benchmark = InferenceBenchmark()
    private val kvCacheManager = KvCacheManager.forModelSize(0.5e9f)

    suspend fun chatCompletion(
        systemPrompt: String,
        history: List<ChatTurn>,
        userMessage: String,
        onToken: ((String) -> Unit)? = null,
    ): AiResult {
        if (!config.isConfigured) {
            return AiResult.Error(
                "No on-device model configured. Add a .pte or .gguf model file " +
                    "in Settings to enable AI.",
            )
        }

        val activeModel = config.activeModel
            ?: return AiResult.Error("No active model selected.")

        val runner: LlmRunner = when (activeModel.format) {
            ModelFormat.PTE -> executorchRunner
            ModelFormat.GGUF -> llamaCppRunner
            ModelFormat.UNKNOWN -> return AiResult.Error(
                "Unsupported model format. Use .pte or .gguf files.",
            )
        }

        when (val load = runner.ensureLoaded(
            modelPath = activeModel.modelPath,
            tokenizerPath = activeModel.tokenizerPath,
            temperature = config.temperature,
        )) {
            is LlmRunner.LoadResult.Success -> Unit
            is LlmRunner.LoadResult.Error -> return AiResult.Error(load.message)
        }

        val template = activeModel.promptTemplate
        val prompt = PromptFormatter.format(
            template = template,
            systemPrompt = systemPrompt,
            history = history,
            userMessage = userMessage,
        )

        // Step 1: Adaptive tuning based on device conditions
        val tuning = AdaptiveInferenceTuner.tune(config, context)
        var effectiveSeqLen = tuning.seqLen

        // Step 2: KV cache memory check
        when (val kvDecision = kvCacheManager.checkMemory(effectiveSeqLen)) {
            is KvCacheManager.KvCacheDecision.Proceed -> Unit
            is KvCacheManager.KvCacheDecision.ReduceSeqLen -> {
                effectiveSeqLen = kvDecision.newSeqLen
            }
            is KvCacheManager.KvCacheDecision.ResetContext -> {
                runner.resetContext()
                kvCacheManager.reset()
                effectiveSeqLen = effectiveSeqLen.coerceAtMost(256)
            }
        }

        // Step 3: Benchmark measurement
        benchmark.start()

        val sink = LlmRunner.TokenSink { token ->
            benchmark.onToken()
            onToken?.invoke(token)
        }

        val genResult = runner.generate(prompt, effectiveSeqLen, sink)
        val benchResult = benchmark.finish()
        kvCacheManager.recordGeneration(benchResult.tokenCount)

        return when (genResult) {
            is LlmRunner.GenerateResult.Success ->
                AiResult.Success(
                    content = genResult.text,
                    statsJson = genResult.statsJson,
                    benchmark = benchResult,
                    tuning = tuning,
                )
            is LlmRunner.GenerateResult.Error ->
                AiResult.Error(genResult.message)
        }
    }

    companion object {
        private const val TAG = "AiService"
    }
}
