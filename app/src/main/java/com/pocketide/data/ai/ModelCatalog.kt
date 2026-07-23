package com.pocketide.data.ai

import java.io.File

data class ModelCatalogAsset(
    val id: String,
    val remoteFileName: String,
    val localFileName: String,
    val downloadUrl: String,
    val expectedBytes: Long,
    val sha256: String,
)

data class ModelCatalogEntry(
    val id: String,
    val displayName: String,
    val description: String,
    val sourceUrl: String,
    val licenseLabel: String,
    val promptTemplate: PromptTemplate,
    val modelAsset: ModelCatalogAsset,
    val tokenizerAsset: ModelCatalogAsset? = null,
) {
    val assets: List<ModelCatalogAsset> = listOfNotNull(modelAsset, tokenizerAsset)
    val totalBytes: Long = assets.sumOf { it.expectedBytes }

    fun installedModel(modelsDirectory: File): ModelEntry = ModelEntry(
        name = displayName,
        modelPath = File(modelsDirectory, modelAsset.localFileName).absolutePath,
        tokenizerPath = tokenizerAsset
            ?.let { File(modelsDirectory, it.localFileName).absolutePath }
            .orEmpty(),
        promptTemplate = promptTemplate,
    )
}

object ModelCatalog {
    val qwenCoder = ModelCatalogEntry(
        id = "qwen25_coder_15b_q4_0",
        displayName = "Qwen2.5-Coder 1.5B Q4_0",
        description = "Coding-focused GGUF for PocketIDE's llama.cpp backend.",
        sourceUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF",
        licenseLabel = "Apache-2.0",
        promptTemplate = PromptTemplate.QWEN,
        modelAsset = ModelCatalogAsset(
            id = "model",
            remoteFileName = "qwen2.5-coder-1.5b-instruct-q4_0.gguf",
            localFileName = "qwen2.5-coder-1.5b-instruct-q4_0.gguf",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_0.gguf?download=true",
            expectedBytes = 1_066_227_264L,
            sha256 = "aa8353e0d0fca3a0041828701e90db7635197400f040676d11d7798665fa316e",
        ),
    )

    val qwenCoder3b = ModelCatalogEntry(
        id = "qwen25_coder_3b_q4_0",
        displayName = "Qwen2.5-Coder 3B Q4_0 smaller",
        description = "Smaller legacy 3B Q4_0 GGUF for higher-memory phones; loading is preflighted on the device.",
        sourceUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct-GGUF",
        licenseLabel = "Qwen Research License",
        promptTemplate = PromptTemplate.QWEN,
        modelAsset = ModelCatalogAsset(
            id = "model",
            remoteFileName = "qwen2.5-coder-3b-instruct-q4_0.gguf",
            localFileName = "qwen2.5-coder-3b-instruct-q4_0.gguf",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/qwen2.5-coder-3b-instruct-q4_0.gguf?download=true",
            expectedBytes = 1_997_879_744L,
            sha256 = "282085f05511706c7b59b32ccabfc452f214771076298c29183e595c576417e0",
        ),
    )

    val qwenCoder3bQuality = ModelCatalogEntry(
        id = "qwen25_coder_3b_q4_k_m",
        displayName = "Qwen2.5-Coder 3B Q4_K_M quality",
        description = "Balanced-quality 3B GGUF for higher-memory phones; loading is preflighted on the device.",
        sourceUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct-GGUF",
        licenseLabel = "Qwen Research License",
        promptTemplate = PromptTemplate.QWEN,
        modelAsset = ModelCatalogAsset(
            id = "model",
            remoteFileName = "qwen2.5-coder-3b-instruct-q4_k_m.gguf",
            localFileName = "qwen2.5-coder-3b-instruct-q4_k_m.gguf",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/qwen2.5-coder-3b-instruct-q4_k_m.gguf?download=true",
            expectedBytes = 2_104_932_800L,
            sha256 = "724fb256bec1ff062b2f65e4569e871ad2e95ab2a3989723d1769c54294730b7",
        ),
    )

    val llamaSpinQuant = ModelCatalogEntry(
        id = "llama32_1b_spinquant_int4",
        displayName = "Llama 3.2 1B SpinQuant INT4",
        description = "Arm-optimized ExecuTorch PTE plus its required tokenizer.",
        sourceUrl = "https://huggingface.co/executorch-community/Llama-3.2-1B-Instruct-SpinQuant_INT4_EO8-ET",
        licenseLabel = "Llama 3.2 Community License",
        promptTemplate = PromptTemplate.LLAMA3,
        modelAsset = ModelCatalogAsset(
            id = "model",
            remoteFileName = "Llama-3.2-1B-Instruct-SpinQuant_INT4_EO8.pte",
            localFileName = "Llama-3.2-1B-Instruct-SpinQuant_INT4_EO8.pte",
            downloadUrl = "https://huggingface.co/executorch-community/Llama-3.2-1B-Instruct-SpinQuant_INT4_EO8-ET/resolve/main/Llama-3.2-1B-Instruct-SpinQuant_INT4_EO8.pte?download=true",
            expectedBytes = 1_135_951_488L,
            sha256 = "8715cdba9e91f6bede00cc5f2d6b12397b95225da5630c4972a8da03001cda3b",
        ),
        tokenizerAsset = ModelCatalogAsset(
            id = "tokenizer",
            remoteFileName = "tokenizer.model",
            localFileName = "Llama-3.2-1B-Instruct-SpinQuant-tokenizer.model",
            downloadUrl = "https://huggingface.co/executorch-community/Llama-3.2-1B-Instruct-SpinQuant_INT4_EO8-ET/resolve/main/tokenizer.model?download=true",
            expectedBytes = 2_183_982L,
            sha256 = "82e9d31979e92ab929cd544440f129d9ecd797b69e327f80f17e1c50d5551b55",
        ),
    )

    val entries: List<ModelCatalogEntry> =
        listOf(qwenCoder, qwenCoder3bQuality, qwenCoder3b, llamaSpinQuant)

    fun find(id: String): ModelCatalogEntry? = entries.firstOrNull { it.id == id }
}

enum class CatalogDownloadPhase {
    AVAILABLE,
    QUEUED,
    DOWNLOADING,
    PAUSED,
    VERIFYING,
    INSTALLED,
    FAILED,
}

data class CatalogDownloadState(
    val phase: CatalogDownloadPhase,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val detail: String? = null,
) {
    val progress: Float
        get() = if (totalBytes <= 0L) 0f else {
            (downloadedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        }
}
