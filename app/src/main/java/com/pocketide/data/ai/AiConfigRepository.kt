package com.pocketide.data.ai

import android.content.Context
import androidx.core.content.edit

private const val PREFS_NAME = "pocketide_prefs"
private const val KEY_MODELS = "ai_models"
private const val KEY_ACTIVE_MODEL_INDEX = "ai_active_model_index"
private const val KEY_TEMPERATURE = "ai_temperature"
private const val KEY_MAX_SEQ_LEN = "ai_max_seq_len"
private const val KEY_PROMPT_TEMPLATE = "ai_prompt_template"
private const val KEY_QUANTIZATION = "ai_quantization"
private const val KEY_POWER_SAVING = "ai_power_saving"
private const val KEY_THERMAL_AWARE = "ai_thermal_aware"
private const val KEY_ADAPTIVE_CORES = "ai_adaptive_cores"
private const val KEY_MAX_REPAIR = "ai_max_repair"
private const val KEY_CONTEXT_WINDOW = "ai_context_window"
private const val KEY_ENABLE_CODE_CONTEXT = "ai_enable_code_context"
private const val KEY_ENABLE_HISTORY_SUMMARY = "ai_enable_history_summary"

class AiConfigRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AiConfig {
        val default = AiConfig()
        return AiConfig(
            models = deserializeModels(prefs.getString(KEY_MODELS, "") ?: ""),
            activeModelIndex = prefs.getInt(KEY_ACTIVE_MODEL_INDEX, default.activeModelIndex),
            temperature = prefs.getFloat(KEY_TEMPERATURE, default.temperature),
            maxSeqLen = prefs.getInt(KEY_MAX_SEQ_LEN, default.maxSeqLen),
            promptTemplate = prefs.getString(KEY_PROMPT_TEMPLATE, default.promptTemplate.name)
                ?.let { runCatching { PromptTemplate.valueOf(it) }.getOrNull() } ?: default.promptTemplate,
            quantization = prefs.getString(KEY_QUANTIZATION, default.quantization.name)
                ?.let { runCatching { Quantization.valueOf(it) }.getOrNull() } ?: default.quantization,
            powerSaving = prefs.getBoolean(KEY_POWER_SAVING, default.powerSaving),
            thermalAware = prefs.getBoolean(KEY_THERMAL_AWARE, default.thermalAware),
            adaptiveCores = prefs.getBoolean(KEY_ADAPTIVE_CORES, default.adaptiveCores),
            maxRepairIterations = prefs.getInt(KEY_MAX_REPAIR, default.maxRepairIterations),
            contextWindowSize = prefs.getInt(KEY_CONTEXT_WINDOW, default.contextWindowSize),
            enableCodeContext = prefs.getBoolean(KEY_ENABLE_CODE_CONTEXT, default.enableCodeContext),
            enableHistorySummary = prefs.getBoolean(KEY_ENABLE_HISTORY_SUMMARY, default.enableHistorySummary),
        )
    }

    fun save(config: AiConfig) {
        prefs.edit {
            putString(KEY_MODELS, serializeModels(config.models))
            putInt(KEY_ACTIVE_MODEL_INDEX, config.activeModelIndex)
            putFloat(KEY_TEMPERATURE, config.temperature)
            putInt(KEY_MAX_SEQ_LEN, config.maxSeqLen)
            putString(KEY_PROMPT_TEMPLATE, config.promptTemplate.name)
            putString(KEY_QUANTIZATION, config.quantization.name)
            putBoolean(KEY_POWER_SAVING, config.powerSaving)
            putBoolean(KEY_THERMAL_AWARE, config.thermalAware)
            putBoolean(KEY_ADAPTIVE_CORES, config.adaptiveCores)
            putInt(KEY_MAX_REPAIR, config.maxRepairIterations)
            putInt(KEY_CONTEXT_WINDOW, config.contextWindowSize)
            putBoolean(KEY_ENABLE_CODE_CONTEXT, config.enableCodeContext)
            putBoolean(KEY_ENABLE_HISTORY_SUMMARY, config.enableHistorySummary)
        }
    }

    private fun serializeModels(models: List<ModelEntry>): String {
        return models.joinToString("\n") { entry ->
            "${entry.name}\t${entry.modelPath}\t${entry.tokenizerPath}\t${entry.promptTemplate.name}"
        }
    }

    private fun deserializeModels(data: String): List<ModelEntry> {
        if (data.isBlank()) return emptyList()
        return data.lines().mapNotNull { line ->
            val parts = line.split("\t")
            if (parts.size < 2) return@mapNotNull null
            val name = parts[0]
            val modelPath = parts[1]
            val tokenizerPath = parts.getOrElse(2) { "" }
            val template = parts.getOrElse(3) { "LLAMA3" }
                .let { runCatching { PromptTemplate.valueOf(it) }.getOrNull() } ?: PromptTemplate.LLAMA3
            ModelEntry(name = name, modelPath = modelPath, tokenizerPath = tokenizerPath, promptTemplate = template)
        }
    }
}
