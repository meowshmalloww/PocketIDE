package com.pocketide.data.ai

import android.content.Context
import android.os.BatteryManager

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
 * Optimization settings (powerSaving, thermalAware, adaptiveCores) are applied
 * to adjust the generation sequence length at runtime:
 * - **powerSaving**: Halves maxSeqLen to reduce compute and battery drain.
 * - **thermalAware**: Reads battery temperature; reduces maxSeqLen when device is hot.
 * - **adaptiveCores**: Uses [Runtime.availableProcessors] to scale maxSeqLen —
 *   more cores allow full-length generation, fewer cores trigger a reduction.
 */
class AiService(
    private val executorchRunner: ExecutorchLlmRunner,
    private val llamaCppRunner: LlamaCppRunner,
    private val config: AiConfig,
    private val context: Context? = null,
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

        val activeModel = config.activeModel
            ?: return AiResult.Error("No active model selected.")

        val runner: LlmRunner = when (activeModel.format) {
            ModelFormat.PTE -> executorchRunner
            ModelFormat.GGUF -> llamaCppRunner
            ModelFormat.UNKNOWN -> return AiResult.Error(
                "Unsupported model format. Use .pte or .gguf files.",
            )
        }

        when (val load = runner.ensureLoaded(
            modelPath = activeModel.modelPath,
            tokenizerPath = activeModel.tokenizerPath,
            temperature = config.temperature,
        )) {
            is LlmRunner.LoadResult.Success -> Unit
            is LlmRunner.LoadResult.Error -> return AiResult.Error(load.message)
        }

        val template = activeModel.promptTemplate
        val prompt = PromptFormatter.format(
            template = template,
            systemPrompt = systemPrompt,
            history = history,
            userMessage = userMessage,
        )

        val optimizedSeqLen = applyOptimizations(config.maxSeqLen)

        val sink = LlmRunner.TokenSink { token ->
            onToken?.invoke(token)
        }

        return when (val gen = runner.generate(prompt, optimizedSeqLen, sink)) {
            is LlmRunner.GenerateResult.Success ->
                AiResult.Success(content = gen.text, statsJson = gen.statsJson)
            is LlmRunner.GenerateResult.Error ->
                AiResult.Error(gen.message)
        }
    }

    /**
     * Applies optimization settings to adjust the generation sequence length.
     * Returns the adjusted sequence length.
     */
    private fun applyOptimizations(baseSeqLen: Int): Int {
        var seqLen = baseSeqLen

        if (config.powerSaving) {
            seqLen = (seqLen / 2).coerceAtLeast(128)
        }

        if (config.thermalAware && context != null) {
            val batteryTemp = readBatteryTemperature(context)
            if (batteryTemp > THERMAL_THRESHOLD_CELSIUS) {
                val reductionFactor = 1f - ((batteryTemp - THERMAL_THRESHOLD_CELSIUS) / 20f).coerceAtMost(0.5f)
                seqLen = (seqLen * reductionFactor).toInt().coerceAtLeast(64)
            }
        }

        if (config.adaptiveCores) {
            val cores = Runtime.getRuntime().availableProcessors()
            if (cores <= 4) {
                seqLen = (seqLen * 0.75f).toInt().coerceAtLeast(64)
            }
        }

        return seqLen
    }

    private fun readBatteryTemperature(ctx: Context): Float {
        return try {
            val intent = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            if (temp > 0) temp / 10f else 25f
        } catch (e: Exception) {
            25f
        }
    }

    companion object {
        private const val THERMAL_THRESHOLD_CELSIUS = 38f
    }
}
