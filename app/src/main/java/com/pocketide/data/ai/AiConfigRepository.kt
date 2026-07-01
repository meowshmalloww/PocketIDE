package com.pocketide.data.ai

import android.content.Context
import androidx.core.content.edit

private const val PREFS_NAME = "pocketide_prefs"
private const val KEY_BASE_URL = "ai_base_url"
private const val KEY_API_KEY = "ai_api_key"
private const val KEY_MODEL = "ai_model"

class AiConfigRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AiConfig {
        val default = AiConfig()
        return AiConfig(
            baseUrl = prefs.getString(KEY_BASE_URL, default.baseUrl) ?: default.baseUrl,
            apiKey = prefs.getString(KEY_API_KEY, default.apiKey) ?: default.apiKey,
            model = prefs.getString(KEY_MODEL, default.model) ?: default.model,
        )
    }

    fun save(config: AiConfig) {
        prefs.edit {
            putString(KEY_BASE_URL, config.baseUrl)
            putString(KEY_API_KEY, config.apiKey)
            putString(KEY_MODEL, config.model)
        }
    }
}
