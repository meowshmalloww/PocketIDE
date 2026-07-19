package com.pocketide.data.ai

import org.json.JSONObject

/** Exact counters and timestamps emitted by ExecuTorch's LLM runner stats callback. */
data class ExecutorchGenerationStats(
    val promptTokens: Int,
    val generatedTokens: Int,
    val inferenceStartMs: Long,
    val promptEvalEndMs: Long,
    val firstTokenMs: Long,
    val inferenceEndMs: Long,
) {
    companion object {
        fun parse(rawJson: String?): ExecutorchGenerationStats? {
            if (rawJson.isNullOrBlank()) return null
            return runCatching {
                val json = JSONObject(rawJson)
                ExecutorchGenerationStats(
                    promptTokens = json.getInt("prompt_tokens"),
                    generatedTokens = json.getInt("generated_tokens"),
                    inferenceStartMs = json.getLong("inference_start_ms"),
                    promptEvalEndMs = json.getLong("prompt_eval_end_ms"),
                    firstTokenMs = json.getLong("first_token_ms"),
                    inferenceEndMs = json.getLong("inference_end_ms"),
                )
            }.getOrNull()
        }
    }
}

/**
 * Counts emitted new tokens and tells the ExecuTorch callback when to stop.
 *
 * ExecuTorch 1.0 exposes a total sequence-length cap rather than a max-new-token API. Keeping
 * this limiter at the streaming boundary makes benchmark and interactive output caps exact even
 * when the prompt-token estimate is imperfect.
 */
internal class OutputTokenLimiter(maxOutputTokens: Int?) {
    private val limit = maxOutputTokens?.takeIf { it > 0 }

    var emittedTokens: Int = 0
        private set

    fun accept(): Decision {
        if (limit != null && emittedTokens >= limit) return Decision.DROP
        emittedTokens += 1
        return if (limit != null && emittedTokens >= limit) {
            Decision.EMIT_AND_STOP
        } else {
            Decision.EMIT
        }
    }

    enum class Decision {
        EMIT,
        EMIT_AND_STOP,
        DROP,
    }
}
