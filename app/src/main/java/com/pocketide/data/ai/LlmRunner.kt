package com.pocketide.data.ai

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Unified interface for on-device LLM inference.
 *
 * Implementations:
 * - [ExecutorchLlmRunner] — runs .pte models via ExecuTorch (NPU acceleration on Snapdragon)
 * - [LlamaCppRunner] — runs .gguf models via llama.cpp (broad model ecosystem)
 *
 * The caller (typically [AiService]) selects the appropriate runner based on the
 * model file extension and delegates all inference calls through this interface.
 */
interface LlmRunner {

    fun interface TokenSink {
        fun onToken(token: String)
    }

    sealed class LoadResult {
        data object Success : LoadResult()
        data class Error(val message: String) : LoadResult()
    }

    sealed class GenerateResult {
        data class Success(val text: String, val statsJson: String?) : GenerateResult()
        data class Error(val message: String) : GenerateResult()
    }

    suspend fun ensureLoaded(
        modelPath: String,
        tokenizerPath: String,
        temperature: Float,
    ): LoadResult

    suspend fun generate(
        prompt: String,
        seqLen: Int,
        sink: TokenSink,
    ): GenerateResult

    fun stop()

    suspend fun resetContext()

    suspend fun release()
}

/** Shared default dispatcher for all runners. */
internal val RunnerDispatcher: CoroutineDispatcher = Dispatchers.Default
