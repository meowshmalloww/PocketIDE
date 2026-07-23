package com.pocketide.data.ai

import java.io.File

/**
 * Detects model architecture parameters from model file names or sizes.
 *
 * Used by [KvCacheManager] to estimate KV cache memory and by [AdaptiveInferenceTuner]
 * to choose appropriate thread/seqLen heuristics.
 *
 * Supported model families:
 * - Qwen2.5 / Qwen3 (0.5B, 1.5B, 3B, 7B)
 * - SmolLM2 (135M, 360M, 1.7B)
 * - Llama 3.2 (1B, 3B)
 * - Phi-3.5 mini
 */
object ModelSpec {

    data class Architecture(
        val paramCountBillion: Float,
        val numLayers: Int,
        val hiddenDim: Int,
        val numKvHeads: Int,
        val headDim: Int,
        val maxContextLength: Int,
        val displayName: String,
        val source: String = "filename or file-size estimate",
        /** Fixed export precision when it is known. GGUF precision is selected at load time. */
        val exportedKvBytesPerElement: Int? = null,
        /** Exact GGUF general.architecture value when metadata is available. */
        val architectureId: String? = null,
        val expertCount: Int? = null,
        val expertUsedCount: Int? = null,
    ) {
        val paramCountMillion: Int get() = (paramCountBillion * 1000).toInt()

        /** Active experts reduce compute per token, but do not remove inactive weights from storage. */
        val isMixtureOfExperts: Boolean
            get() = (expertCount ?: 0) > 0 ||
                architectureId?.contains("moe", ignoreCase = true) == true
    }

    /**
     * Detects the model architecture from the model file name and/or file size.
     *
     * File name patterns: "Qwen2.5-Coder-1.5B-Instruct-Q4_0_4_4.gguf"
     * We parse the parameter count (e.g. "1.5B", "0.5B", "135M") and match
     * to known architectures. Falls back to file-size-based estimation.
     */
    fun detect(modelPath: String): Architecture {
        val fileName = File(modelPath).name.lowercase()

        // Try to parse parameter count from filename
        val paramCount = parseParamCount(fileName)

        if (fileName.endsWith(".gguf")) {
            GgufMetadataReader.read(File(modelPath))?.let { metadata ->
                return Architecture(
                    paramCountBillion = parseParamCount(metadata.sizeLabel.orEmpty()) ?: paramCount ?: 0f,
                    numLayers = metadata.blockCount,
                    hiddenDim = metadata.embeddingLength,
                    numKvHeads = metadata.attentionKvHeadCount,
                    headDim = metadata.attentionKeyLength,
                    maxContextLength = metadata.contextLength,
                    displayName = metadata.name?.takeIf { it.isNotBlank() }
                        ?: metadata.architecture,
                    source = "GGUF metadata",
                    architectureId = metadata.architecture,
                    expertCount = metadata.expertCount,
                    expertUsedCount = metadata.expertUsedCount,
                )
            }
        }

        // A PTE's context and KV layout are fixed when it is exported. The Java runner cannot
        // enlarge either value at runtime. The exact catalog artifact bundled by PocketIDE was
        // published with max_seq_length=2048, max_context_length=2048, FP32 model/cache dtype,
        // and no KV-cache quantization. Unknown PTEs stay conservative because ExecuTorch 1.0.1
        // does not expose their constant-method metadata through the Android LLM API.
        if (fileName.endsWith(".pte")) {
            val isPublishedSpinQuant = "llama-3.2-1b-instruct-spinquant_int4_eo8" in fileName ||
                "llama_3_2_1b_instruct_spinquant_int4_eo8" in fileName
            return Architecture(
                paramCountBillion = paramCount ?: 1.0f,
                numLayers = 16,
                hiddenDim = 2048,
                numKvHeads = 8,
                headDim = 64,
                maxContextLength = 2048,
                displayName = if (isPublishedSpinQuant) {
                    "Llama-3.2-1B SpinQuant PTE"
                } else {
                    "PTE export (conservative bound)"
                },
                source = if (isPublishedSpinQuant) {
                    "published ExecuTorch export recipe"
                } else {
                    "conservative PTE bound; export metadata unavailable through Android API"
                },
                exportedKvBytesPerElement = if (isPublishedSpinQuant) 4 else null,
            )
        }

        // Match to known architecture
        return matchArchitecture(paramCount, modelPath)
    }

    private fun parseParamCount(fileName: String): Float? {
        // Match patterns like "1.5b", "0.5b", "7b", "135m", "360m", "1.7b"
        val regex = Regex("(\\d+\\.?\\d*)(b|m)", RegexOption.IGNORE_CASE)
        val match = regex.find(fileName) ?: return null
        val value = match.groupValues[1].toFloatOrNull() ?: return null
        val unit = match.groupValues[2].lowercase()
        return when (unit) {
            "b" -> value
            "m" -> value / 1000f
            else -> null
        }
    }

    private fun matchArchitecture(paramCount: Float?, modelPath: String): Architecture {
        // Known architectures table
        // Format: (paramCount, layers, hiddenDim, kvHeads, headDim, maxContext, name)
        val known = listOf(
            Arch(0.135f, 30, 576, 4, 64, 2048, "SmolLM2-135M"),
            Arch(0.360f, 24, 960, 4, 64, 2048, "SmolLM2-360M"),
            Arch(0.5f, 24, 896, 2, 64, 32768, "Qwen2.5-0.5B"),
            Arch(1.0f, 16, 2048, 8, 64, 8192, "Llama-3.2-1B quantized release"),
            Arch(1.5f, 28, 1536, 2, 128, 32768, "Qwen2.5-1.5B"),
            Arch(1.7f, 24, 2048, 3, 64, 8192, "SmolLM2-1.7B"),
            Arch(3.0f, 36, 2048, 2, 128, 32768, "Qwen2.5-Coder-3B"),
            Arch(3.8f, 32, 3072, 8, 96, 4096, "Phi-3.5-mini"),
            Arch(7.0f, 28, 3584, 4, 128, 32768, "Qwen2.5-7B"),
        )

        if (paramCount != null) {
            // Find closest match by parameter count
            val closest = known.minByOrNull { kotlin.math.abs(it.params - paramCount) }
            if (closest != null && kotlin.math.abs(closest.params - paramCount) < 0.3f) {
                return Architecture(
                    paramCountBillion = closest.params,
                    numLayers = closest.layers,
                    hiddenDim = closest.hiddenDim,
                    numKvHeads = closest.kvHeads,
                    headDim = closest.headDim,
                    maxContextLength = closest.maxCtx,
                    displayName = closest.name,
                    source = "filename architecture table",
                )
            }
        }

        // Fallback: estimate from file size
        val modelFile = File(modelPath)
        val fileSizeMb = if (modelFile.exists()) modelFile.length() / (1024f * 1024f) else 0f
        return estimateFromFileSize(fileSizeMb)
    }

    private fun estimateFromFileSize(fileSizeMb: Float): Architecture {
        // Q4_0 quantization: ~0.6 bytes per parameter
        val estimatedParams = fileSizeMb / 0.6f / 1000f
        return when {
            estimatedParams <= 0.2f -> Architecture(0.135f, 30, 576, 4, 64, 2048, "SmolLM2-135M (estimated)", "file-size estimate")
            estimatedParams <= 0.6f -> Architecture(0.5f, 24, 896, 2, 64, 32768, "Qwen2.5-0.5B (estimated)", "file-size estimate")
            estimatedParams <= 1.2f -> Architecture(1.5f, 28, 1536, 2, 128, 32768, "Qwen2.5-1.5B (estimated)", "file-size estimate")
            estimatedParams <= 2.5f -> Architecture(3.0f, 36, 2048, 2, 128, 32768, "Qwen2.5-Coder-3B (estimated)", "file-size estimate")
            else -> Architecture(7.0f, 28, 3584, 4, 128, 32768, "Qwen2.5-7B (estimated)", "file-size estimate")
        }
    }

    private data class Arch(
        val params: Float,
        val layers: Int,
        val hiddenDim: Int,
        val kvHeads: Int,
        val headDim: Int,
        val maxCtx: Int,
        val name: String,
    )
}
