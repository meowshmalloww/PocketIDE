package com.pocketide.data.ai

import android.content.Context
import androidx.core.content.edit

private const val PREFS_NAME = "pocketide_prefs"
private const val KEY_MODEL_PATH = "ai_model_path"
private const val KEY_TOKENIZER_PATH = "ai_tokenizer_path"
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
            modelPath = prefs.getString(KEY_MODEL_PATH, default.modelPath) ?: default.modelPath,
            tokenizerPath = prefs.getString(KEY_TOKENIZER_PATH, default.tokenizerPath) ?: default.tokenizerPath,
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
            putString(KEY_MODEL_PATH, config.modelPath)
            putString(KEY_TOKENIZER_PATH, config.tokenizerPath)
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
}
