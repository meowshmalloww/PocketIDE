package com.pocketide.data.ai

data class AiConfig(
    val modelPath: String = "",
    val quantization: Quantization = Quantization.INT4,
    val powerSaving: Boolean = false,
    val thermalAware: Boolean = true,
    val adaptiveCores: Boolean = true,
    val maxRepairIterations: Int = 3,
) {
    val isConfigured: Boolean get() = modelPath.isNotBlank()
}

enum class Quantization(val displayName: String, val suffix: String) {
    INT4("INT4 (group_size=32)", "int4"),
    INT8("INT8", "int8"),
    FP32("FP32 (no quantization)", "fp32"),
}
