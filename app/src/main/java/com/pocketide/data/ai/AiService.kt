package com.pocketide.data.ai

sealed class AiResult {
    data class Success(val content: String, val statsJson: String? = null) : AiResult()
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
 * The runner instances are owned by the caller (typically a ViewModel scoped
 * to the process) so that model weights are loaded once and reused.
 */
class AiService(
    private val executorchRunner: ExecutorchLlmRunner,
    private val llamaCppRunner: LlamaCppRunner,
    private val config: AiConfig,
) {

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

        val runner: LlmRunner = when (config.modelFormat) {
            ModelFormat.PTE -> executorchRunner
            ModelFormat.GGUF -> llamaCppRunner
            ModelFormat.UNKNOWN -> return AiResult.Error(
                "Unsupported model format. Use .pte or .gguf files.",
            )
        }

        when (val load = runner.ensureLoaded(
            modelPath = config.modelPath,
            tokenizerPath = config.tokenizerPath,
            temperature = config.temperature,
        )) {
            is LlmRunner.LoadResult.Success -> Unit
            is LlmRunner.LoadResult.Error -> return AiResult.Error(load.message)
        }

        val prompt = PromptFormatter.format(
            template = config.promptTemplate,
            systemPrompt = systemPrompt,
            history = history,
            userMessage = userMessage,
        )

        val sink = LlmRunner.TokenSink { token ->
            onToken?.invoke(token)
        }

        return when (val gen = runner.generate(prompt, config.maxSeqLen, sink)) {
            is LlmRunner.GenerateResult.Success ->
                AiResult.Success(content = gen.text, statsJson = gen.statsJson)
            is LlmRunner.GenerateResult.Error ->
                AiResult.Error(gen.message)
        }
    }
}
