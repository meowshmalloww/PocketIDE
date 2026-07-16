package com.pocketide.data.ai

data class ModelEntry(
    val name: String,
    val modelPath: String,
    val tokenizerPath: String = "",
    val promptTemplate: PromptTemplate = PromptTemplate.AUTO,
) {
    val format: ModelFormat get() = when {
        modelPath.endsWith(".gguf", ignoreCase = true) -> ModelFormat.GGUF
        modelPath.endsWith(".pte", ignoreCase = true) -> ModelFormat.PTE
        else -> ModelFormat.UNKNOWN
    }

    val isConfigured: Boolean
        get() = when (format) {
            ModelFormat.GGUF -> modelPath.isNotBlank()
            ModelFormat.PTE -> modelPath.isNotBlank() && tokenizerPath.isNotBlank()
            ModelFormat.UNKNOWN -> false
        }
}

data class AiConfig(
    val models: List<ModelEntry> = emptyList(),
    val activeModelIndex: Int = 0,
    val temperature: Float = 0.6f,
    val maxSeqLen: Int = 1024,
    val promptTemplate: PromptTemplate = PromptTemplate.LLAMA3,
    val quantization: Quantization = Quantization.INT4,
    val powerSaving: Boolean = false,
    val thermalAware: Boolean = true,
    val adaptiveCores: Boolean = true,
    val maxRepairIterations: Int = 3,
    val contextWindowSize: Int = 4096,
    val enableCodeContext: Boolean = true,
    val enableHistorySummary: Boolean = true,
) {
    val activeModel: ModelEntry?
        get() = models.getOrNull(activeModelIndex)

    val modelPath: String
        get() = activeModel?.modelPath ?: ""

    val tokenizerPath: String
        get() = activeModel?.tokenizerPath ?: ""

    val modelFormat: ModelFormat
        get() = activeModel?.format ?: ModelFormat.UNKNOWN

    val isConfigured: Boolean
        get() = activeModel?.isConfigured ?: false
}

enum class ModelFormat(val displayName: String, val fileExtension: String) {
    PTE("ExecuTorch (.pte)", ".pte"),
    GGUF("llama.cpp (.gguf)", ".gguf"),
    UNKNOWN("Unknown", ""),
}

enum class Quantization(val displayName: String, val suffix: String) {
    INT4("INT4 (group_size=32)", "int4"),
    INT8("INT8", "int8"),
    FP32("FP32 (no quantization)", "fp32"),
}

enum class PromptTemplate(val displayName: String) {
    AUTO("Auto detect (recommended)"),
    LLAMA3("Llama 3 / 3.2 Instruct"),
    QWEN("Qwen 2.5 / Qwen 3 ChatML"),
    PLAIN("Plain (system + user concatenated)"),
}
