package com.pocketide.data.ai

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Unified interface for on-device LLM inference.
 *
 * Implementations:
 * - [ExecutorchLlmRunner] — runs .pte models through the delegates included in the ExecuTorch export
 * - [LlamaCppRunner] — runs .gguf models via llama.cpp (broad model ecosystem)
 *
 * The caller (typically [AiService]) selects the appropriate runner based on the
 * model file extension and delegates all inference calls through this interface.
 */
interface LlmRunner {

    data class LoadOptions(
        val contextLength: Int = 4096,
        val batchSize: Int = 512,
        val threadCount: Int = 0,
    )

    fun interface TokenSink {
        fun onToken(token: String)
    }

    data class GenerationOptions(
        val deterministic: Boolean = false,
        val ignoreEos: Boolean = false,
        val seed: Int = -1,
        /** Optional new-token cap for runtimes whose sequence API uses a total-length limit. */
        val maxOutputTokens: Int? = null,
    )

    sealed class LoadResult {
        data object Success : LoadResult()
        data class Error(val message: String) : LoadResult()
    }

    sealed class GenerateResult {
        data class Success(
            val text: String,
            val statsJson: String?,
            val generatedTokenCount: Int? = null,
            val promptTokenCount: Int? = null,
            /** Thread count configured when the native context was loaded, if controlled by PocketIDE. */
            val configuredThreadCount: Int? = null,
        ) : GenerateResult()
        data class Error(val message: String) : GenerateResult()
    }

    suspend fun ensureLoaded(
        modelPath: String,
        tokenizerPath: String,
        temperature: Float,
        options: LoadOptions = LoadOptions(),
    ): LoadResult

    suspend fun generate(
        prompt: String,
        seqLen: Int,
        sink: TokenSink,
        options: GenerationOptions = GenerationOptions(),
    ): GenerateResult

    fun stop()

    suspend fun resetContext()

    suspend fun release()
}

/** Shared default dispatcher for all runners. */
internal val RunnerDispatcher: CoroutineDispatcher = Dispatchers.Default
