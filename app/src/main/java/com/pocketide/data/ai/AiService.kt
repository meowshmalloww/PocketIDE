package com.pocketide.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class AiResult {
    data class Success(val content: String) : AiResult()
    data class Error(val message: String) : AiResult()
}

data class ChatTurn(val role: String, val content: String)

/**
 * On-device AI inference service. Uses ExecuTorch for local model execution.
 * No network calls — fully offline.
 */
class AiService(private val config: AiConfig) {

    @Suppress("UNUSED_PARAMETER")
    suspend fun chatCompletion(
        systemPrompt: String,
        history: List<ChatTurn>,
        userMessage: String,
    ): AiResult = withContext(Dispatchers.Default) {
        if (!config.isConfigured) {
            return@withContext AiResult.Error(
                "No local model configured. Add a .pte model file in Settings to enable on-device AI.",
            )
        }

        // TODO: ExecuTorch integration — load .pte model, run inference, stream tokens.
        // For now, return an error indicating the model is configured but inference
        // is not yet wired up.
        AiResult.Error(
            "Local model found at ${config.modelPath} but on-device inference is not yet implemented. " +
                "ExecuTorch integration pending.",
        )
    }
}
