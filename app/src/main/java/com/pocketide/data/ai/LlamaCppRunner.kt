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

    private val mutex = Mutex()
    private var llamaContext: LlamaContext? = null
    private var loadedModelPath: String? = null
    private var loadedTemperature = Float.NaN
    private var loadedContextLength = 0
    private var loadedThreadCount = 0

    override suspend fun ensureLoaded(
        modelPath: String,
        tokenizerPath: String,
        temperature: Float,
        options: LlmRunner.LoadOptions,
    ): LlmRunner.LoadResult = withContext(dispatcher) {
        mutex.withLock {
            val contextLength = options.contextLength.coerceAtLeast(MIN_CONTEXT_LENGTH)
            val threads = options.threadCount
                .takeIf { it > 0 }
                ?.coerceAtMost(Runtime.getRuntime().availableProcessors())
                ?: DEFAULT_THREADS.coerceAtMost(Runtime.getRuntime().availableProcessors())
            if (modelMatches(modelPath, temperature, contextLength, threads)) {
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
                        "n_batch" to contextLength.coerceAtMost(512),
                        "n_threads" to threads,
                        "n_gpu_layers" to 0,
                        "use_mlock" to false,
                        "use_mmap" to true,
                        "vocab_only" to false,
                    ),
                )
                llamaContext = nativeContext
                loadedModelPath = modelPath
                loadedTemperature = temperature
                loadedContextLength = contextLength
                loadedThreadCount = threads
                Log.i(TAG, "Loaded ${modelFile.name}: ctx=$contextLength, actualThreads=$threads, mmap=true")
                LlmRunner.LoadResult.Success
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to load GGUF model", error)
                releaseLocked()
                LlmRunner.LoadResult.Error(
                    "GGUF initialization failed: ${error.message ?: error.javaClass.simpleName}. " +
                        "Verify the file is a complete, supported GGUF and leave at least 1.5 GB free RAM.",
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
                LlmRunner.GenerateResult.Success(
                    text = nativeText?.takeIf { it.isNotEmpty() } ?: output.toString(),
                    statsJson = JSONObject(result).toString(),
                    generatedTokenCount = generated,
                    promptTokenCount = promptTokens,
                    actualThreadCount = loadedThreadCount,
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

    fun actualThreadCount(): Int = loadedThreadCount

    fun tokenize(text: String): Int? = runCatching { llamaContext?.tokenize(text)?.size }.getOrNull()

    fun nativeBench(promptTokens: Int = 128, generatedTokens: Int = 32, repetitions: Int = 3): String? =
        runCatching { llamaContext?.bench(promptTokens, generatedTokens, 1, repetitions) }.getOrNull()

    private fun modelMatches(path: String, temperature: Float, contextLength: Int, threads: Int): Boolean =
        llamaContext != null && loadedModelPath == path && loadedTemperature == temperature &&
            loadedContextLength == contextLength && loadedThreadCount == threads

    private fun releaseLocked() {
        runCatching { llamaContext?.stopCompletion() }
        runCatching { llamaContext?.release() }
        llamaContext = null
        loadedModelPath = null
        loadedTemperature = Float.NaN
        loadedContextLength = 0
        loadedThreadCount = 0
    }

    companion object {
        private const val TAG = "LlamaCppRunner"
        private const val MIN_CONTEXT_LENGTH = 512
        private const val DEFAULT_THREADS = 4
        private val NEXT_CONTEXT_ID = AtomicInteger(1)
    }
}
