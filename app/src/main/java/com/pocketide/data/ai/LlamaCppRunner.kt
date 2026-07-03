package com.pocketide.data.ai

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.nehuatl.llamacpp.LlamaHelper
import java.io.File

/**
 * Runs GGUF models on-device via llama.cpp (kotlinllamacpp library).
 *
 * This runner handles .gguf model files — the standard format on HuggingFace.
 * Unlike [ExecutorchLlmRunner] which requires .pte files + a separate tokenizer,
 * GGUF files are self-contained (tokenizer embedded in the model file).
 *
 * The kotlinllamacpp API is event-driven via [MutableSharedFlow], so this class
 * bridges that to the synchronous [LlmRunner] interface by collecting events
 * into a channel and awaiting completion.
 *
 * kotlinllamacpp API: https://github.com/ljcamargo/kotlinllamacpp
 */
class LlamaCppRunner(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = RunnerDispatcher,
) : LlmRunner {

    private val mutex = Mutex()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    private val llmFlow = MutableSharedFlow<LlamaHelper.LLMEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    private var llamaHelper: LlamaHelper? = null
    private var loadedModelPath: String? = null
    private var loadedTemperature: Float = Float.NaN

    override suspend fun ensureLoaded(
        modelPath: String,
        tokenizerPath: String,
        temperature: Float,
    ): LlmRunner.LoadResult = withContext(dispatcher) {
        mutex.withLock {
            if (modelMatches(modelPath, temperature)) {
                return@withLock LlmRunner.LoadResult.Success
            }

            val modelFile = File(modelPath)
            if (!modelFile.isFile) {
                return@withLock LlmRunner.LoadResult.Error("Model file not found: $modelPath")
            }

            releaseLocked()

            try {
                val helper = LlamaHelper(
                    contentResolver = context.contentResolver,
                    scope = scope,
                    sharedFlow = llmFlow,
                )
                val modelUri = Uri.fromFile(modelFile).toString()
                val loadComplete = CompletableDeferred<Unit>()

                helper.load(
                    path = modelUri,
                    contextLength = DEFAULT_CONTEXT_LENGTH,
                ) {
                    loadComplete.complete(Unit)
                }

                loadComplete.await()
                llamaHelper = helper
                loadedModelPath = modelPath
                loadedTemperature = temperature
                LlmRunner.LoadResult.Success
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load GGUF model", t)
                LlmRunner.LoadResult.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun generate(
        prompt: String,
        seqLen: Int,
        sink: LlmRunner.TokenSink,
    ): LlmRunner.GenerateResult = withContext(dispatcher) {
        mutex.withLock {
            val helper = llamaHelper
                ?: return@withLock LlmRunner.GenerateResult.Error("Model not loaded")

            val builder = StringBuilder()
            var errorMessage: String? = null
            val generationDone = CompletableDeferred<Unit>()

            try {
                helper.predict(prompt)

                llmFlow.collect { event ->
                    when (event) {
                        is LlamaHelper.LLMEvent.Ongoing -> {
                            builder.append(event.word)
                            sink.onToken(event.word)
                        }
                        is LlamaHelper.LLMEvent.Done -> {
                            generationDone.complete(Unit)
                            throw FlowCollectionComplete
                        }
                        is LlamaHelper.LLMEvent.Error -> {
                            errorMessage = "Generation error"
                            generationDone.complete(Unit)
                            throw FlowCollectionComplete
                        }
                        else -> {}
                    }
                }
            } catch (e: FlowCollectionComplete) {
                // Expected — flow collection interrupted after Done/Error
            } catch (t: Throwable) {
                return@withLock LlmRunner.GenerateResult.Error(
                    t.message ?: t.javaClass.simpleName,
                )
            }

            generationDone.await()

            val err = errorMessage
            if (err != null) {
                LlmRunner.GenerateResult.Error(err)
            } else {
                LlmRunner.GenerateResult.Success(builder.toString(), null)
            }
        }
    }

    override fun stop() {
        llamaHelper?.runCatching { stopPrediction() }
    }

    override suspend fun resetContext() {
        withContext(dispatcher) {
            mutex.withLock {
                llamaHelper?.runCatching { stopPrediction() }
            }
        }
    }

    override suspend fun release() {
        withContext(dispatcher) {
            mutex.withLock { releaseLocked() }
        }
    }

    private fun modelMatches(modelPath: String, temperature: Float): Boolean =
        llamaHelper != null &&
            loadedModelPath == modelPath &&
            loadedTemperature == temperature

    private fun releaseLocked() {
        val helper = llamaHelper ?: return
        runCatching { helper.stopPrediction() }
        llamaHelper = null
        loadedModelPath = null
        loadedTemperature = Float.NaN
    }

    private object FlowCollectionComplete : Throwable() {
        override fun fillInStackTrace(): Throwable = this
    }

    companion object {
        private const val TAG = "LlamaCppRunner"
        private const val DEFAULT_CONTEXT_LENGTH = 2048
    }
}
