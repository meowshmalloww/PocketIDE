package com.pocketide.data.ai

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmModule
import java.io.File
import kotlin.coroutines.resume

/**
 * Wraps ExecuTorch's [LlmModule] with a Kotlin coroutine-friendly API.
 *
 * The underlying [LlmModule.generate] call is synchronous and streams tokens
 * via a callback on the caller's thread. This class:
 *
 *  - Owns a single [LlmModule] instance and reloads only when the model
 *    or tokenizer path changes (loading a multi-hundred-MB .pte is expensive).
 *  - Serializes concurrent [generate] calls with a mutex so we never invoke
 *    the native runner reentrantly.
 *  - Runs blocking calls on a dedicated dispatcher.
 *  - Emits each token through [TokenSink.onToken] so callers can stream to UI.
 *
 * ExecuTorch on-device LLM API reference:
 *   https://docs.pytorch.org/executorch/stable/llm/run-on-android.html
 */
class ExecutorchLlmRunner(
    private val dispatcher: CoroutineDispatcher = RunnerDispatcher,
) : LlmRunner {

    private val mutex = Mutex()

    // Guarded by [mutex] after construction.
    @Volatile private var module: LlmModule? = null
    @Volatile private var loadedModelPath: String? = null
    @Volatile private var loadedTokenizerPath: String? = null
    @Volatile private var loadedTemperature: Float = Float.NaN

    /**
     * Ensures a module matching [modelPath] / [tokenizerPath] / [temperature]
     * is loaded, reusing the existing instance where possible.
     */
    override suspend fun ensureLoaded(
        modelPath: String,
        tokenizerPath: String,
        temperature: Float,
        options: LlmRunner.LoadOptions,
    ): LlmRunner.LoadResult = withContext(dispatcher) {
        mutex.withLock {
            if (moduleMatches(modelPath, tokenizerPath, temperature)) {
                return@withLock LlmRunner.LoadResult.Success
            }

            val modelFile = File(modelPath)
            if (!modelFile.isFile) {
                return@withLock LlmRunner.LoadResult.Error("Model file not found: $modelPath")
            }
            val tokenizerFile = File(tokenizerPath)
            if (!tokenizerFile.isFile) {
                return@withLock LlmRunner.LoadResult.Error("Tokenizer file not found: $tokenizerPath")
            }

            releaseLocked()

            try {
                BackendInfo.logBackendInfo()
                val newModule = LlmModule(modelPath, tokenizerPath, temperature)
                val loadCode = newModule.load()
                if (loadCode != 0) {
                    newModule.runCatching { resetNative() }
                    return@withLock LlmRunner.LoadResult.Error(
                        "LlmModule.load() failed with code $loadCode",
                    )
                }
                module = newModule
                loadedModelPath = modelPath
                loadedTokenizerPath = tokenizerPath
                loadedTemperature = temperature
                LlmRunner.LoadResult.Success
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load LlmModule", t)
                LlmRunner.LoadResult.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /**
     * Runs generation for [prompt] with [seqLen] as the total sequence-length cap. Streams tokens
     * through [sink] as they are produced, and returns the concatenated text
     * plus the runner's stats JSON on completion.
     */
    override suspend fun generate(
        prompt: String,
        seqLen: Int,
        sink: LlmRunner.TokenSink,
        options: LlmRunner.GenerationOptions,
    ): LlmRunner.GenerateResult = withContext(dispatcher) {
        mutex.withLock {
            val currentModule = module
                ?: return@withLock LlmRunner.GenerateResult.Error("Model not loaded")

            val builder = StringBuilder()
            val statsHolder = arrayOfNulls<String>(1)
            val errorHolder = arrayOfNulls<String>(1)
            val outputLimiter = OutputTokenLimiter(options.maxOutputTokens)
            var stoppedAtOutputLimit = false

            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    val callback = object : LlmCallback {
                        override fun onResult(token: String) {
                            val decision = outputLimiter.accept()
                            if (decision == OutputTokenLimiter.Decision.DROP) return
                            builder.append(token)
                            sink.onToken(token)
                            if (decision == OutputTokenLimiter.Decision.EMIT_AND_STOP) {
                                stoppedAtOutputLimit = true
                                runCatching { currentModule.stop() }
                            }
                        }

                        override fun onStats(stats: String) {
                            statsHolder[0] = stats
                        }
                    }
                    cont.invokeOnCancellation {
                        runCatching { currentModule.stop() }
                    }
                    try {
                        val status = currentModule.generate(prompt, seqLen, callback, false)
                        if (status != 0 && !stoppedAtOutputLimit) {
                            errorHolder[0] = "ExecuTorch generation failed with code $status"
                        }
                        if (cont.isActive) cont.resume(Unit)
                    } catch (t: Throwable) {
                        errorHolder[0] = t.message ?: t.javaClass.simpleName
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
            } catch (t: Throwable) {
                return@withLock LlmRunner.GenerateResult.Error(t.message ?: t.javaClass.simpleName)
            }

            val err = errorHolder[0]
            if (err != null) {
                LlmRunner.GenerateResult.Error(err)
            } else {
                val stats = ExecutorchGenerationStats.parse(statsHolder[0])
                LlmRunner.GenerateResult.Success(
                    text = builder.toString(),
                    statsJson = statsHolder[0],
                    generatedTokenCount = if (options.maxOutputTokens != null) {
                        outputLimiter.emittedTokens
                    } else {
                        stats?.generatedTokens ?: outputLimiter.emittedTokens
                    },
                    promptTokenCount = stats?.promptTokens,
                    configuredThreadCount = null,
                )
            }
        }
    }

    /** Interrupts an in-flight generation, if any. Safe to call from any thread. */
    override fun stop() {
        module?.runCatching { stop() }
    }

    fun isLoaded(modelPath: String, tokenizerPath: String, temperature: Float): Boolean =
        moduleMatches(modelPath, tokenizerPath, temperature)

    /** True when any PTE module is resident, including a model other than the selected one. */
    fun hasLoadedModel(): Boolean = module != null

    /** Clears the KV cache and resets generation state. */
    override suspend fun resetContext() {
        withContext(dispatcher) {
            mutex.withLock {
                module?.runCatching { resetContext() }
            }
        }
    }

    /** Releases the native module. Call from ViewModel.onCleared() or similar. */
    override suspend fun release() {
        withContext(dispatcher) {
            mutex.withLock { releaseLocked() }
        }
    }

    private fun moduleMatches(
        modelPath: String,
        tokenizerPath: String,
        temperature: Float,
    ): Boolean = module != null &&
        loadedModelPath == modelPath &&
        loadedTokenizerPath == tokenizerPath &&
        loadedTemperature == temperature

    private fun releaseLocked() {
        val current = module ?: return
        runCatching { current.stop() }
        runCatching { current.resetNative() }
            .onFailure { Log.w(TAG, "ExecuTorch native release failed", it) }
        module = null
        loadedModelPath = null
        loadedTokenizerPath = null
        loadedTemperature = Float.NaN
    }

    companion object {
        private const val TAG = "ExecutorchLlmRunner"
    }
}
