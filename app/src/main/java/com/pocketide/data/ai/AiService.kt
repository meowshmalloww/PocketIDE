package com.pocketide.data.ai

import android.content.Context

sealed class AiResult {
    data class Success(
        val content: String,
        val statsJson: String? = null,
        val benchmark: InferenceBenchmark.BenchmarkResult? = null,
        val tuning: InferenceTuning? = null,
        val pipelineLog: String? = null,
    ) : AiResult()
    data class Error(val message: String) : AiResult()
}

data class ChatTurn(val role: String, val content: String)

data class BenchmarkRunOptions(
    val profile: String,
    val threadCount: Int,
    val isWarmup: Boolean,
    val generatedTokens: Int = 96,
)

/**
 * On-device AI inference service. Routes to the appropriate [LlmRunner]
 * based on the model file extension:
 * - `.pte` → [ExecutorchLlmRunner] (ExecuTorch; acceleration depends on delegates baked into the export)
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
    private val benchmarkSession = BenchmarkSession()
    private val threadCalibrationRepository = context?.let(::ThreadCalibrationRepository)
    private var kvCacheManager: KvCacheManager = KvCacheManager.forModelSize(0.5e9f)
    private var modelArchitecture: ModelSpec.Architecture? = null
    private var pipelineLogBuilder = StringBuilder()

    val session: BenchmarkSession get() = benchmarkSession

    suspend fun chatCompletion(
        systemPrompt: String,
        history: List<ChatTurn>,
        userMessage: String,
        onToken: ((String) -> Unit)? = null,
        benchmarkOptions: BenchmarkRunOptions? = null,
    ): AiResult {
        pipelineLogBuilder = StringBuilder()
        if (!config.isConfigured) {
            return AiResult.Error(
                "No on-device model configured. Add a .pte or .gguf model file " +
                    "in Settings to enable AI.",
            )
        }

        val activeModel = config.activeModel
            ?: return AiResult.Error("No active model selected.")

        // Detect model architecture from file name for accurate KV cache estimation
        val arch = ModelSpec.detect(activeModel.modelPath)
        modelArchitecture = arch
        if (kvCacheManager.estimateKvCacheBytes(1) == 0L ||
            kvCacheManager.numLayers != arch.numLayers
        ) {
            kvCacheManager = KvCacheManager.forArchitecture(arch)
            logPipeline("KV cache manager initialized for ${arch.displayName}: " +
                "layers=${arch.numLayers}, hidden=${arch.hiddenDim}, " +
                "kvHeads=${arch.numKvHeads}, headDim=${arch.headDim}")
        }

        // Set model info in benchmark session
        val modelFile = java.io.File(activeModel.modelPath)
        benchmarkSession.setModelInfo(
            path = activeModel.modelPath,
            format = activeModel.format.displayName,
            quantization = config.quantization.displayName,
            sizeBytes = if (modelFile.exists()) modelFile.length() else 0,
        )

        val runner: LlmRunner = when (activeModel.format) {
            ModelFormat.PTE -> executorchRunner
            ModelFormat.GGUF -> llamaCppRunner
            ModelFormat.UNKNOWN -> return AiResult.Error(
                "Unsupported model format. Use .pte or .gguf files.",
            )
        }

        val requestedContextLength = config.contextWindowSize
            .coerceIn(MIN_CONTEXT_LENGTH, arch.maxContextLength)
        logPipeline(
            "Model context: requested=${config.contextWindowSize}, loaded=$requestedContextLength " +
                "(model max=${arch.maxContextLength})",
        )

        // Tune before model loading because llama.cpp applies CPU threads when the context is created.
        val savedCalibration = if (benchmarkOptions == null && activeModel.format == ModelFormat.GGUF) {
            threadCalibrationRepository?.load(activeModel.modelPath)
        } else {
            null
        }
        if (savedCalibration != null) {
            logPipeline(
                "Using measured ${savedCalibration.threadCount}-thread profile " +
                    "(${"%.2f".format(savedCalibration.medianTokensPerSecond)} tok/s calibration median)",
            )
        }
        val adaptiveTuning = AdaptiveInferenceTuner.tune(
            config = config,
            context = context,
            calibratedThreadCount = savedCalibration?.threadCount,
        )
        val tuning = benchmarkOptions?.let {
            adaptiveTuning.copy(
                seqLen = it.generatedTokens,
                threadCount = it.threadCount.coerceIn(1, adaptiveTuning.cpuCores),
                strategy = InferenceStrategy.BENCHMARK_FIXED,
            )
        } ?: adaptiveTuning
        logPipeline("[1] Adaptive tuning: ${tuning.summary()}")

        when (val load = runner.ensureLoaded(
            modelPath = activeModel.modelPath,
            tokenizerPath = activeModel.tokenizerPath,
            temperature = config.temperature,
            options = LlmRunner.LoadOptions(
                contextLength = requestedContextLength,
                threadCount = tuning.threadCount,
            ),
        )) {
            is LlmRunner.LoadResult.Success -> Unit
            is LlmRunner.LoadResult.Error -> return AiResult.Error(load.message)
        }

        val template = resolvePromptTemplate(activeModel)
        val prompt = PromptFormatter.format(
            template = template,
            systemPrompt = systemPrompt,
            history = history,
            userMessage = userMessage,
        )

        var effectiveSeqLen = tuning.seqLen

        // Step 2: KV cache memory check
        val kvDecision = kvCacheManager.checkMemory(effectiveSeqLen, context)
        when (kvDecision) {
            is KvCacheManager.KvCacheDecision.Proceed ->
                logPipeline("[2] KV cache: OK (${"%.1f".format(kvDecision.estimatedKvCacheMb)}MB estimated)")
            is KvCacheManager.KvCacheDecision.ReduceSeqLen -> {
                effectiveSeqLen = kvDecision.newSeqLen
                logPipeline("[2] KV cache: REDUCED seqLen to ${kvDecision.newSeqLen} (${kvDecision.reason})")
            }
            is KvCacheManager.KvCacheDecision.ResetContext -> {
                runner.resetContext()
                kvCacheManager.reset()
                effectiveSeqLen = effectiveSeqLen.coerceAtMost(256)
                logPipeline("[2] KV cache: RESET (${kvDecision.reason})")
            }
        }
        logPipeline("[2] KV cache estimate: ${kvCacheManager.currentBytesPerElement()} " +
            "bytes/element (native precision is controlled by the model/runtime)")

        // Estimate prompt tokens for logging
        val promptTokenEstimate = (prompt.length / 4) + 1
        logPipeline("[3] Prompt: ${prompt.length} chars, ~${promptTokenEstimate} tokens, " +
            "effectiveSeqLen=$effectiveSeqLen, requestedThreads=${tuning.threadCount}")

        // Step 3: Benchmark measurement
        benchmark.start()

        val sink = LlmRunner.TokenSink { token ->
            benchmark.onToken()
            onToken?.invoke(token)
        }

        val genResult = runner.generate(
            prompt,
            effectiveSeqLen,
            sink,
            options = LlmRunner.GenerationOptions(
                deterministic = benchmarkOptions != null,
                ignoreEos = benchmarkOptions != null,
                seed = if (benchmarkOptions != null) 42 else -1,
            ),
        )
        val successfulGeneration = genResult as? LlmRunner.GenerateResult.Success
        val benchResult = benchmark.finish(successfulGeneration?.generatedTokenCount)
        kvCacheManager.recordGeneration(benchResult.tokenCount)

        // Record in benchmark session
        benchmarkSession.record(
            result = benchResult,
            tuning = tuning,
            kvCacheQuantized = kvCacheManager.isKvCacheQuantized(),
            kvCacheBytesPerElement = kvCacheManager.currentBytesPerElement(),
            promptTokenEstimate = successfulGeneration?.promptTokenCount ?: promptTokenEstimate,
            actualThreadCount = successfulGeneration?.actualThreadCount ?: tuning.threadCount,
            profile = benchmarkOptions?.profile ?: "Interactive",
            isWarmupOverride = benchmarkOptions?.isWarmup,
        )
        logPipeline("[4] Generation complete: ${benchResult.summary()}")
        logPipeline("[4] KV cache total tokens: ${kvCacheManager.totalTokensGenerated()}")

        val pipelineLog = pipelineLogBuilder.toString()
        pipelineLogBuilder = StringBuilder()

        return when (genResult) {
            is LlmRunner.GenerateResult.Success ->
                AiResult.Success(
                    content = genResult.text,
                    statsJson = genResult.statsJson,
                    benchmark = benchResult,
                    tuning = tuning,
                    pipelineLog = pipelineLog,
                )
            is LlmRunner.GenerateResult.Error ->
                AiResult.Error(genResult.message)
        }
    }

    private fun logPipeline(msg: String) {
        pipelineLogBuilder.appendLine(msg)
        android.util.Log.i(TAG, msg)
    }

    private fun resolvePromptTemplate(model: ModelEntry): PromptTemplate {
        if (model.promptTemplate != PromptTemplate.AUTO) return model.promptTemplate
        val fileName = model.modelPath.substringAfterLast('/').lowercase()
        val detected = when {
            fileName.contains("qwen") || fileName.contains("chatml") -> PromptTemplate.QWEN
            fileName.contains("llama") || fileName.contains("smollm") -> PromptTemplate.LLAMA3
            else -> PromptTemplate.PLAIN
        }
        logPipeline("Auto-detected ${detected.displayName} prompt template from model filename")
        return detected
    }

    companion object {
        private const val TAG = "AiService"
        private const val MIN_CONTEXT_LENGTH = 512
    }
}
