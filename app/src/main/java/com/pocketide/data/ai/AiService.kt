package com.pocketide.data.ai

import android.content.Context

sealed class AiResult {
    data class Success(
        val content: String,
        val statsJson: String? = null,
        val benchmark: InferenceBenchmark.BenchmarkResult? = null,
        val tuning: InferenceTuning? = null,
        val pipelineLog: String? = null,
        val resourcePlan: InferenceResourcePlan? = null,
    ) : AiResult()
    data class Error(
        val message: String,
        val resourcePlan: InferenceResourcePlan? = null,
    ) : AiResult()
}

enum class InferencePhase(val displayName: String) {
    IDLE("Ready"),
    PREPARING_PROMPT("Preparing prompt"),
    LOADING_MODEL("Loading local model"),
    READING_PROMPT("Reading prompt"),
    WRITING_CODE("Writing code"),
    APPLYING_FILES("Applying files"),
    COMPLETE("Complete"),
    CANCELLED("Cancelled"),
    FAILED("Failed"),
}

data class ChatTurn(val role: String, val content: String)

data class BenchmarkRunOptions(
    val profile: String,
    val threadCount: Int,
    val isWarmup: Boolean,
    val generatedTokens: Int = 96,
    val workload: String = "deterministic_code_generation",
    val phase: String = "calibration",
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
    private var pipelineLogBuilder = StringBuilder()

    val session: BenchmarkSession get() = benchmarkSession

    /** Selects one shared context and memory profile before prompt pruning or native loading. */
    fun prepareResourcePlan(
        benchmarkOptions: BenchmarkRunOptions? = null,
    ): InferenceResourcePlan? {
        val appContext = context ?: return null
        val activeModel = config.activeModel ?: return null
        val architecture = configureArchitecture(activeModel)
        val modelFile = java.io.File(activeModel.modelPath)
        val temperature = loadTemperature(activeModel.format, benchmarkOptions)
        val savedCalibration = if (benchmarkOptions == null && activeModel.format == ModelFormat.GGUF) {
            threadCalibrationRepository?.load(activeModel.modelPath)
        } else {
            null
        }
        val tuning = AdaptiveInferenceTuner.tune(
            config = config,
            context = context,
            calibratedThreadCount = savedCalibration?.threadCount,
        )
        val selectedThreads = benchmarkOptions?.threadCount ?: tuning.threadCount
        val requestedOutput = benchmarkOptions?.generatedTokens ?: tuning.seqLen
        val snapshot = DeviceMemorySnapshot.capture(appContext)
        val loadedGgufProfile = if (activeModel.format == ModelFormat.GGUF) {
            llamaCppRunner.loadedProfile(activeModel.modelPath, temperature)
        } else {
            null
        }
        val modelAlreadyLoaded = when (activeModel.format) {
            ModelFormat.GGUF -> loadedGgufProfile != null
            ModelFormat.PTE -> executorchRunner.isLoaded(
                activeModel.modelPath,
                activeModel.tokenizerPath,
                temperature,
            )
            ModelFormat.UNKNOWN -> false
        }
        val baseRequest = InferenceResourceRequest(
            format = activeModel.format,
            requestedContext = config.contextWindowSize,
            requestedOutputTokens = requestedOutput,
            selectedThreads = selectedThreads,
            modelSizeBytes = modelFile.takeIf { it.isFile }?.length() ?: 0L,
            architecture = architecture,
            modelAlreadyLoaded = modelAlreadyLoaded,
            loadedContextLength = loadedGgufProfile?.contextLength,
            loadedBatchSize = loadedGgufProfile?.batchSize,
            loadedThreadCount = loadedGgufProfile?.threadCount,
        )
        val plan = InferenceResourcePlanner.plan(baseRequest, snapshot)
        benchmarkSession.setModelInfo(
            path = activeModel.modelPath,
            format = activeModel.format.displayName,
            quantization = config.quantization.displayName,
            sizeBytes = modelFile.takeIf { it.isFile }?.length() ?: 0L,
        )
        benchmarkSession.setResourcePlan(plan)
        return plan
    }

    /** Interrupts an in-flight native generation. Safe to call from the UI thread. */
    fun stopGeneration() {
        executorchRunner.stop()
        llamaCppRunner.stop()
    }

    suspend fun chatCompletion(
        systemPrompt: String,
        history: List<ChatTurn>,
        userMessage: String,
        onToken: ((String) -> Unit)? = null,
        benchmarkOptions: BenchmarkRunOptions? = null,
        resourcePlan: InferenceResourcePlan? = null,
        onPhase: ((InferencePhase) -> Unit)? = null,
    ): AiResult {
        pipelineLogBuilder = StringBuilder()
        onPhase?.invoke(InferencePhase.PREPARING_PROMPT)
        if (!config.isConfigured) {
            return AiResult.Error(
                "No on-device model configured. Add a .pte or .gguf model file " +
                    "in Settings to enable AI.",
            )
        }

        val activeModel = config.activeModel
            ?: return AiResult.Error("No active model selected.")

        // Detect model architecture from file name for accurate KV cache estimation.
        configureArchitecture(activeModel)

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

        val plan = resourcePlan ?: prepareResourcePlan(benchmarkOptions)
            ?: return AiResult.Error("Could not inspect device memory before model loading.")
        benchmarkSession.setResourcePlan(plan)
        if (!plan.allowed) {
            return AiResult.Error(plan.blockingMessage(), plan)
        }

        val template = resolvePromptTemplate(activeModel)
        val prompt = PromptFormatter.format(
            template = template,
            systemPrompt = systemPrompt,
            history = history,
            userMessage = userMessage,
        )
        val promptTokenEstimate = (prompt.length / 4) + 1

        logPipeline(
            "Resource plan: requestedContext=${plan.requestedContext}, " +
                "effectiveContext=${plan.effectiveContext}, batch=${plan.batchSize}, " +
                "outputCap=${plan.maxOutputTokens}, coldLoad=${plan.coldLoad}, allowed=${plan.allowed}",
        )
        logPipeline("Resource reason: ${plan.reason}")

        // Resolve the normal-chat thread profile before generation. Benchmark runs can override it per call.
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
                seqLen = it.generatedTokens.coerceAtMost(plan.maxOutputTokens),
                threadCount = plan.selectedThreads.coerceIn(1, adaptiveTuning.cpuCores),
                strategy = InferenceStrategy.BENCHMARK_FIXED,
            )
        } ?: adaptiveTuning.copy(
            seqLen = adaptiveTuning.seqLen.coerceAtMost(plan.maxOutputTokens),
            threadCount = plan.selectedThreads.coerceIn(1, adaptiveTuning.cpuCores),
        )
        logPipeline("[1] Adaptive tuning: ${tuning.summary()}")

        onPhase?.invoke(InferencePhase.LOADING_MODEL)
        when (val load = runner.ensureLoaded(
            modelPath = activeModel.modelPath,
            tokenizerPath = activeModel.tokenizerPath,
            temperature = loadTemperature(activeModel.format, benchmarkOptions),
            options = LlmRunner.LoadOptions(
                contextLength = plan.effectiveContext,
                batchSize = plan.batchSize.takeIf { it > 0 } ?: DEFAULT_BATCH_SIZE,
                threadCount = tuning.threadCount,
            ),
        )) {
            is LlmRunner.LoadResult.Success -> Unit
            is LlmRunner.LoadResult.Error -> return AiResult.Error(load.message, plan)
        }

        // ExecuTorch keeps a KV context between calls. Reset it before every
        // measured PTE generation so runs are independent and memory does not
        // accumulate across a benchmark suite.
        if (benchmarkOptions != null && activeModel.format == ModelFormat.PTE) {
            runner.resetContext()
        }

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

        val runnerSequenceLength = if (activeModel.format == ModelFormat.PTE) {
            // ExecuTorch 1.0's generate(prompt, seqLen, ...) treats seqLen as
            // total sequence length, unlike llama.cpp's n_predict. Give the
            // native runner enough total space, then enforce the exact new-token
            // cap at the callback boundary in ExecutorchLlmRunner.
            (promptTokenEstimate + effectiveSeqLen + PTE_PROMPT_ESTIMATE_HEADROOM)
                .coerceIn(MIN_PTE_SEQUENCE_LENGTH, plan.effectiveContext)
        } else {
            effectiveSeqLen
        }
        logPipeline("[3] Prompt: ${prompt.length} chars, ~${promptTokenEstimate} tokens, " +
            "requestedOutput=$effectiveSeqLen, runnerSeqLen=$runnerSequenceLength, " +
            "requestedThreads=${tuning.threadCount}")

        // Step 3: Benchmark measurement
        onPhase?.invoke(InferencePhase.READING_PROMPT)
        benchmark.start()

        var emittedFirstToken = false
        val sink = LlmRunner.TokenSink { token ->
            benchmark.onToken()
            if (!emittedFirstToken) {
                emittedFirstToken = true
                onPhase?.invoke(InferencePhase.WRITING_CODE)
            }
            onToken?.invoke(token)
        }

        val genResult = runner.generate(
            prompt,
            runnerSequenceLength,
            sink,
            options = LlmRunner.GenerationOptions(
                deterministic = benchmarkOptions != null,
                ignoreEos = benchmarkOptions != null,
                seed = if (benchmarkOptions != null) 42 else -1,
                maxOutputTokens = effectiveSeqLen.takeIf { activeModel.format == ModelFormat.PTE },
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
            promptTokensExact = successfulGeneration?.promptTokenCount != null,
            configuredThreadCount = successfulGeneration?.configuredThreadCount
                ?: if (activeModel.format == ModelFormat.PTE) 0 else tuning.threadCount,
            profile = benchmarkOptions?.profile ?: "Interactive",
            isWarmupOverride = benchmarkOptions?.isWarmup,
            workload = benchmarkOptions?.workload ?: "interactive_chat",
            phase = benchmarkOptions?.phase ?: "interactive",
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
                    resourcePlan = plan,
                )
            is LlmRunner.GenerateResult.Error ->
                AiResult.Error(genResult.message, plan)
        }
    }

    /**
     * Runs the bundled runtime's native llama_decode benchmark in the existing
     * device-safe benchmark context. The native JSON records the actual thread
     * configuration. This is real model execution, not a simulated score.
     */
    suspend fun runNativeKernelBenchmark(
        threadCount: Int,
        promptTokens: Int = 128,
        generatedTokens: Int = 32,
        repetitions: Int = 3,
    ): NativeKernelBenchmark? {
        val activeModel = config.activeModel?.takeIf { it.format == ModelFormat.GGUF } ?: return null
        val plan = prepareResourcePlan(
            BenchmarkRunOptions(
                profile = "Native",
                threadCount = threadCount,
                isWarmup = false,
                generatedTokens = generatedTokens,
                workload = "native_llama_decode",
                phase = "native",
            ),
        ) ?: return null
        if (!plan.allowed) {
            logPipeline("Native benchmark blocked: ${plan.blockingMessage()}")
            return null
        }
        val loadResult = llamaCppRunner.ensureLoaded(
            modelPath = activeModel.modelPath,
            tokenizerPath = activeModel.tokenizerPath,
            temperature = config.temperature,
            options = LlmRunner.LoadOptions(
                contextLength = plan.effectiveContext,
                batchSize = plan.batchSize,
                threadCount = threadCount.coerceIn(1, Runtime.getRuntime().availableProcessors()),
            ),
        )
        if (loadResult is LlmRunner.LoadResult.Error) {
            logPipeline("Native benchmark load failed: ${loadResult.message}")
            return null
        }
        val raw = llamaCppRunner.nativeBench(
            promptTokens = promptTokens,
            generatedTokens = generatedTokens,
            repetitions = repetitions,
        ) ?: return null
        return NativeKernelBenchmark.parse(raw, repetitions)
    }

    private fun configureArchitecture(activeModel: ModelEntry): ModelSpec.Architecture {
        val arch = ModelSpec.detect(activeModel.modelPath)
        if (kvCacheManager.estimateKvCacheBytes(1) == 0L ||
            kvCacheManager.numLayers != arch.numLayers
        ) {
            kvCacheManager = KvCacheManager.forArchitecture(arch)
            logPipeline(
                "KV cache manager initialized for ${arch.displayName}: " +
                    "layers=${arch.numLayers}, hidden=${arch.hiddenDim}, " +
                    "kvHeads=${arch.numKvHeads}, headDim=${arch.headDim}",
            )
        }
        return arch
    }

    private fun loadTemperature(
        format: ModelFormat,
        benchmarkOptions: BenchmarkRunOptions?,
    ): Float = if (benchmarkOptions != null && format == ModelFormat.PTE) 0f else config.temperature

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
        private const val MIN_PTE_SEQUENCE_LENGTH = 128
        private const val PTE_PROMPT_ESTIMATE_HEADROOM = 16
        private const val DEFAULT_BATCH_SIZE = 512
    }
}
