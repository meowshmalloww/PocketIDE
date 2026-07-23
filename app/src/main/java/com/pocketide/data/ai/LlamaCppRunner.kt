package com.pocketide.data.ai

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.nehuatl.llamacpp.LlamaContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** GGUF runner with explicit llama.cpp context/thread control and native token statistics. */
class LlamaCppRunner(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = RunnerDispatcher,
) : LlmRunner {

    data class LoadedProfile(
        val contextLength: Int,
        val batchSize: Int,
        val threadCount: Int,
        val kvCacheTypeK: GgufKvCacheType,
        val kvCacheTypeV: GgufKvCacheType,
        val flashAttention: String,
    )

    private val mutex = Mutex()
    @Volatile private var llamaContext: LlamaContext? = null
    @Volatile private var loadedModelPath: String? = null
    @Volatile private var loadedTemperature = Float.NaN
    @Volatile private var loadedContextLength = 0
    @Volatile private var loadedBatchSize = 0
    @Volatile private var loadedThreadCount = 0
    @Volatile private var loadedKvCacheTypeK = GgufKvCacheType.F16
    @Volatile private var loadedKvCacheTypeV = GgufKvCacheType.F16
    @Volatile private var loadedFlashAttention = "auto"

    override suspend fun ensureLoaded(
        modelPath: String,
        tokenizerPath: String,
        temperature: Float,
        options: LlmRunner.LoadOptions,
    ): LlmRunner.LoadResult = withContext(dispatcher) {
        mutex.withLock {
            val contextLength = options.contextLength.coerceAtLeast(MIN_CONTEXT_LENGTH)
            val batchSize = options.batchSize.coerceIn(1, contextLength)
            val kvCacheTypeK = options.kvCacheTypeK
            val kvCacheTypeV = options.kvCacheTypeV
            val flashAttention = normalizeFlashAttention(options.flashAttention)
            val threads = options.threadCount
                .takeIf { it > 0 }
                ?.coerceAtMost(Runtime.getRuntime().availableProcessors())
                ?: DEFAULT_THREADS.coerceAtMost(Runtime.getRuntime().availableProcessors())
            if (modelMatches(
                    modelPath,
                    temperature,
                    contextLength,
                    batchSize,
                    threads,
                    kvCacheTypeK,
                    kvCacheTypeV,
                    flashAttention,
                )
            ) {
                return@withLock LlmRunner.LoadResult.Success
            }

            val modelFile = File(modelPath)
            if (!modelFile.isFile) {
                return@withLock LlmRunner.LoadResult.Error("Model file not found: $modelPath")
            }

            releaseLocked()
            try {
                val descriptor = context.contentResolver.openFileDescriptor(Uri.fromFile(modelFile), "r")
                    ?: return@withLock LlmRunner.LoadResult.Error("Cannot open GGUF model: $modelPath")
                val fd = descriptor.detachFd()
                descriptor.close()
                val nativeContext = LlamaContext(
                    NEXT_CONTEXT_ID.getAndIncrement(),
                    mapOf(
                        "model" to modelPath,
                        "model_fd" to fd,
                        "embedding" to false,
                        "n_ctx" to contextLength,
                        "n_batch" to batchSize,
                        "n_threads" to threads,
                        "n_gpu_layers" to 0,
                        "use_mlock" to false,
                        "use_mmap" to true,
                        "vocab_only" to false,
                        "cache_type_k" to kvCacheTypeK.nativeName,
                        "cache_type_v" to kvCacheTypeV.nativeName,
                        "flash_attn_type" to flashAttention,
                    ),
                )
                // Publish ownership before validating native evidence so the catch path can
                // release a partially accepted context instead of leaking its model mapping.
                llamaContext = nativeContext
                val details = nativeContext.modelDetails
                val actualContext = (details["actual_context"] as? Number)?.toInt()
                val actualBatch = (details["actual_batch"] as? Number)?.toInt()
                val actualKvK = parseKvCacheType(details["kv_cache_type_k"] as? String)
                val actualKvV = parseKvCacheType(details["kv_cache_type_v"] as? String)
                val actualFlash = normalizeFlashAttention(details["flash_attention"] as? String)
                check(actualContext == contextLength) {
                    "llama.cpp created context $actualContext instead of requested $contextLength"
                }
                check(actualBatch == batchSize) {
                    "llama.cpp created batch $actualBatch instead of requested $batchSize"
                }
                check(actualKvK == kvCacheTypeK && actualKvV == kvCacheTypeV) {
                    "llama.cpp created KV ${actualKvK.nativeName}/${actualKvV.nativeName} instead of " +
                        "${kvCacheTypeK.nativeName}/${kvCacheTypeV.nativeName}"
                }
                if (flashAttention == "on") {
                    check(actualFlash == "on") {
                        "llama.cpp did not enable Flash Attention required by quantized KV"
                    }
                }
                loadedModelPath = modelPath
                loadedTemperature = temperature
                loadedContextLength = contextLength
                loadedBatchSize = batchSize
                loadedThreadCount = threads
                loadedKvCacheTypeK = actualKvK
                loadedKvCacheTypeV = actualKvV
                loadedFlashAttention = actualFlash
                Log.i(
                    TAG,
                    "Loaded ${modelFile.name}: ctx=$contextLength, batch=$batchSize, " +
                        "loadConfiguredThreads=$threads, kv=${actualKvK.nativeName}/" +
                        "${actualKvV.nativeName}, flash=$actualFlash, mmap=true",
                )
                LlmRunner.LoadResult.Success
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to load GGUF model", error)
                releaseLocked()
                val legacyRepackQuant = modelFile.name.lowercase().let { name ->
                    listOf("q4_0_4_4", "q4_0_4_8", "q4_0_8_8").any(name::contains)
                }
                val advice = if (legacyRepackQuant) {
                    "This legacy repacked quant is no longer supported by current llama.cpp. " +
                        "Download the standard Q4_0 or Q4_K GGUF instead; runtime repacking is automatic."
                } else {
                    "Verify that this is a complete single-file GGUF using a supported architecture " +
                        "and current quantization. Standard Q4_0 and Q4_K files are supported."
                }
                LlmRunner.LoadResult.Error(
                    "GGUF initialization failed: ${error.message ?: error.javaClass.simpleName}. $advice",
                )
            }
        }
    }

    override suspend fun generate(
        prompt: String,
        seqLen: Int,
        sink: LlmRunner.TokenSink,
        options: LlmRunner.GenerationOptions,
    ): LlmRunner.GenerateResult = withContext(dispatcher) {
        mutex.withLock {
            val nativeContext = llamaContext
                ?: return@withLock LlmRunner.GenerateResult.Error("Model not loaded")
            val output = StringBuilder()
            nativeContext.setTokenCallback { token ->
                output.append(token)
                sink.onToken(token)
            }
            try {
                val result = nativeContext.completion(
                    mapOf(
                        "prompt" to prompt,
                        "temperature" to if (options.deterministic) 0.0 else loadedTemperature.toDouble(),
                        "n_predict" to seqLen,
                        "n_threads" to loadedThreadCount,
                        "emit_partial_completion" to true,
                        "ignore_eos" to options.ignoreEos,
                        "seed" to options.seed,
                    ),
                )
                val nativeText = result["text"] as? String
                val generated = (result["tokens_predicted"] as? Number)?.toInt()
                val promptTokens = (result["tokens_evaluated"] as? Number)?.toInt()
                val runtimeStats = JSONObject(result)
                    .put("pocketide_actual_context", loadedContextLength)
                    .put("pocketide_actual_batch", loadedBatchSize)
                    .put("pocketide_kv_cache_type_k", loadedKvCacheTypeK.nativeName)
                    .put("pocketide_kv_cache_type_v", loadedKvCacheTypeV.nativeName)
                    .put("pocketide_flash_attention", loadedFlashAttention)
                LlmRunner.GenerateResult.Success(
                    text = nativeText?.takeIf { it.isNotEmpty() } ?: output.toString(),
                    statsJson = runtimeStats.toString(),
                    generatedTokenCount = generated,
                    promptTokenCount = promptTokens,
                    configuredThreadCount = loadedThreadCount,
                )
            } catch (error: Throwable) {
                Log.e(TAG, "GGUF generation failed", error)
                LlmRunner.GenerateResult.Error(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    override fun stop() {
        runCatching { llamaContext?.stopCompletion() }
    }

    override suspend fun resetContext() = withContext(dispatcher) {
        runCatching { llamaContext?.stopCompletion() }
        Unit
    }

    override suspend fun release() = withContext(dispatcher) {
        mutex.withLock { releaseLocked() }
    }

    fun modelDetails(): Map<String, Any> = llamaContext?.modelDetails.orEmpty()

    fun configuredThreadCount(): Int = loadedThreadCount

    /** True when any GGUF mapping is resident, including a model other than the selected one. */
    fun hasLoadedModel(): Boolean = llamaContext != null

    /** Model identity only; context, batch, cache, and thread changes are handled as profile reloads. */
    fun hasLoadedModel(modelPath: String, temperature: Float): Boolean =
        llamaContext != null && loadedModelPath == modelPath && loadedTemperature == temperature

    /** Name set only after Android successfully loads the selected JNI library. */
    fun loadedNativeLibraryName(): String? =
        if (llamaContext != null) LlamaContext.loadedNativeLibraryName else null

    fun isLoaded(
        modelPath: String,
        temperature: Float,
        contextLength: Int,
        batchSize: Int,
        threadCount: Int,
        kvCacheTypeK: GgufKvCacheType = GgufKvCacheType.F16,
        kvCacheTypeV: GgufKvCacheType = GgufKvCacheType.F16,
        flashAttention: String = "auto",
    ): Boolean = modelMatches(
        modelPath,
        temperature,
        contextLength,
        batchSize,
        threadCount,
        kvCacheTypeK,
        kvCacheTypeV,
        normalizeFlashAttention(flashAttention),
    )

    /** Returns the exact live native profile without deriving a new profile from current free RAM. */
    fun loadedProfile(modelPath: String, temperature: Float): LoadedProfile? {
        if (llamaContext == null || loadedModelPath != modelPath || loadedTemperature != temperature) {
            return null
        }
        return LoadedProfile(
            contextLength = loadedContextLength,
            batchSize = loadedBatchSize,
            threadCount = loadedThreadCount,
            kvCacheTypeK = loadedKvCacheTypeK,
            kvCacheTypeV = loadedKvCacheTypeV,
            flashAttention = loadedFlashAttention,
        ).takeIf { it.contextLength > 0 && it.batchSize > 0 && it.threadCount > 0 }
    }

    fun tokenize(text: String): Int? = runCatching { llamaContext?.tokenize(text)?.size }.getOrNull()

    suspend fun nativeBench(
        promptTokens: Int = 128,
        generatedTokens: Int = 32,
        repetitions: Int = 3,
    ): String? = withContext(dispatcher) {
        mutex.withLock {
            runCatching {
                llamaContext?.bench(promptTokens, generatedTokens, 1, repetitions)
            }.onFailure { error ->
                Log.e(TAG, "Native llama.cpp benchmark failed", error)
            }.getOrNull()
        }
    }

    private fun modelMatches(
        path: String,
        temperature: Float,
        contextLength: Int,
        batchSize: Int,
        threadCount: Int,
        kvCacheTypeK: GgufKvCacheType,
        kvCacheTypeV: GgufKvCacheType,
        flashAttention: String,
    ): Boolean =
        llamaContext != null && loadedModelPath == path && loadedTemperature == temperature &&
            loadedContextLength == contextLength && loadedBatchSize == batchSize &&
            loadedThreadCount == threadCount && loadedKvCacheTypeK == kvCacheTypeK &&
            loadedKvCacheTypeV == kvCacheTypeV && loadedFlashAttention == flashAttention

    private fun releaseLocked() {
        runCatching { llamaContext?.stopCompletion() }
        runCatching { llamaContext?.release() }
        llamaContext = null
        loadedModelPath = null
        loadedTemperature = Float.NaN
        loadedContextLength = 0
        loadedBatchSize = 0
        loadedThreadCount = 0
        loadedKvCacheTypeK = GgufKvCacheType.F16
        loadedKvCacheTypeV = GgufKvCacheType.F16
        loadedFlashAttention = "auto"
    }

    companion object {
        private const val TAG = "LlamaCppRunner"
        private const val MIN_CONTEXT_LENGTH = 512
        private const val DEFAULT_THREADS = 4
        private val NEXT_CONTEXT_ID = AtomicInteger(1)

        private fun parseKvCacheType(value: String?): GgufKvCacheType =
            GgufKvCacheType.entries.firstOrNull { it.nativeName.equals(value, ignoreCase = true) }
                ?: GgufKvCacheType.F16

        private fun normalizeFlashAttention(value: String?): String = when (value?.lowercase()) {
            "on", "enabled" -> "on"
            "off", "disabled" -> "off"
            else -> "auto"
        }
    }
}
