package com.pocketide.data.ai

import android.content.Context
import androidx.core.content.edit

private const val PREFS_NAME = "pocketide_prefs"
private const val KEY_MODEL_PATH = "ai_model_path"
private const val KEY_QUANTIZATION = "ai_quantization"
private const val KEY_POWER_SAVING = "ai_power_saving"
private const val KEY_THERMAL_AWARE = "ai_thermal_aware"
private const val KEY_ADAPTIVE_CORES = "ai_adaptive_cores"
private const val KEY_MAX_REPAIR = "ai_max_repair"

class AiConfigRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AiConfig {
        val default = AiConfig()
        return AiConfig(
            modelPath = prefs.getString(KEY_MODEL_PATH, default.modelPath) ?: default.modelPath,
            quantization = prefs.getString(KEY_QUANTIZATION, default.quantization.name)
                ?.let { runCatching { Quantization.valueOf(it) }.getOrNull() } ?: default.quantization,
            powerSaving = prefs.getBoolean(KEY_POWER_SAVING, default.powerSaving),
            thermalAware = prefs.getBoolean(KEY_THERMAL_AWARE, default.thermalAware),
            adaptiveCores = prefs.getBoolean(KEY_ADAPTIVE_CORES, default.adaptiveCores),
            maxRepairIterations = prefs.getInt(KEY_MAX_REPAIR, default.maxRepairIterations),
        )
    }

    fun save(config: AiConfig) {
        prefs.edit {
            putString(KEY_MODEL_PATH, config.modelPath)
            putString(KEY_QUANTIZATION, config.quantization.name)
            putBoolean(KEY_POWER_SAVING, config.powerSaving)
            putBoolean(KEY_THERMAL_AWARE, config.thermalAware)
            putBoolean(KEY_ADAPTIVE_CORES, config.adaptiveCores)
            putInt(KEY_MAX_REPAIR, config.maxRepairIterations)
        }
    }
}
