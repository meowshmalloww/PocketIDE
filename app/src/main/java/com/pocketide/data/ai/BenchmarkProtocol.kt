package com.pocketide.data.ai

import org.json.JSONObject
import java.security.MessageDigest

/** User-selectable evidence depth for the on-device benchmark. */
enum class BenchmarkDepth(val displayName: String, val description: String) {
    QUICK(
        "Quick",
        "A short real generation suite. GGUF compares thread profiles and native pp/tg; PTE measures its exported ExecuTorch backend.",
    ),
    DEEP(
        "Deep evidence",
        "More repeated real generations and stability statistics. GGUF also screens and confirms thread profiles; PTE keeps its fixed exported backend.",
    ),
    SUSTAINED(
        "Sustained evidence",
        "Repeated real generations on one selected profile to measure speed retention, device energy, battery temperature, and Android thermal status.",
    ),
}

/**
 * Reproducibility metadata for one benchmark session.
 *
 * The chat workload measures the complete app-to-model path. The optional native
 * microbenchmark separately measures llama.cpp prompt processing and token decode.
 */
data class BenchmarkProtocol(
    val depth: BenchmarkDepth,
    val backend: String = "llama.cpp / GGUF",
    val workload: String,
    val promptSha256: String,
    val generatedTokens: Int,
    val candidateThreads: List<Int>,
    val screeningMeasuredRuns: Int,
    val confirmationMeasuredRuns: Int,
    val sustainedMeasuredRuns: Int = 0,
    val confirmationThreads: List<Int> = emptyList(),
    val deterministicSeed: Int = 42,
    val temperature: Double = 0.0,
    val ignoreEos: Boolean = true,
    val threadControl: String =
        "llama.cpp context load-time n_threads; native context reloaded between profiles",
    val startedAtMs: Long = System.currentTimeMillis(),
) {
    companion object {
        fun promptSha256(prompt: String): String = MessageDigest.getInstance("SHA-256")
            .digest(prompt.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

/** Parsed output from KotlinLlamaCpp's real llama_decode microbenchmark. */
data class NativeKernelBenchmark(
    val promptTokens: Int,
    val generatedTokens: Int,
    val parallelSequences: Int,
    val repetitions: Int,
    val promptSeconds: Double,
    val promptTokensPerSecond: Double,
    val generationSeconds: Double,
    val generationTokensPerSecond: Double,
    val combinedSeconds: Double,
    val combinedTokensPerSecond: Double,
    val contextSize: Int,
    val batchSize: Int,
    val microBatchSize: Int,
    val threads: Int,
    val batchThreads: Int,
    val gpuLayers: Int,
    val flashAttentionMode: Int,
    val rawJson: String,
) {
    companion object {
        fun parse(rawJson: String, repetitions: Int): NativeKernelBenchmark? = runCatching {
            val objectStart = rawJson.indexOf('{')
            val objectEnd = rawJson.lastIndexOf('}')
            require(objectStart >= 0 && objectEnd >= objectStart)
            val json = JSONObject(rawJson.substring(objectStart, objectEnd + 1))
            val promptSpeed = json.optDouble("speed_pp", 0.0)
            val generationSpeed = json.optDouble("speed_tg", 0.0)
            val promptTokens = json.optInt("pp", 0)
            val generatedTokens = json.optInt("tg", 0)
            val parallelSequences = json.optInt("pl", 1).coerceAtLeast(1)
            require(
                promptSpeed > 0.0 && generationSpeed > 0.0 &&
                    promptTokens > 0 && generatedTokens > 0,
            )
            val promptSeconds = json.optDouble(
                "t_pp",
                promptTokens.toDouble() / promptSpeed,
            )
            val generationSeconds = json.optDouble(
                "t_tg",
                generatedTokens.toDouble() * parallelSequences / generationSpeed,
            )
            val combinedSeconds = json.optDouble("t", promptSeconds + generationSeconds)
            val combinedSpeed = json.optDouble(
                "speed",
                (promptTokens + generatedTokens * parallelSequences) / combinedSeconds,
            )
            NativeKernelBenchmark(
                promptTokens = promptTokens,
                generatedTokens = generatedTokens,
                parallelSequences = parallelSequences,
                repetitions = repetitions,
                promptSeconds = promptSeconds,
                promptTokensPerSecond = promptSpeed,
                generationSeconds = generationSeconds,
                generationTokensPerSecond = generationSpeed,
                combinedSeconds = combinedSeconds,
                combinedTokensPerSecond = combinedSpeed,
                contextSize = json.optInt("n_kv_max", 0),
                batchSize = json.optInt("n_batch", 0),
                microBatchSize = json.optInt("n_ubatch", 0),
                threads = json.optInt("n_threads", 0),
                batchThreads = json.optInt("n_threads_batch", 0),
                gpuLayers = json.optInt("n_gpu_layers", 0),
                flashAttentionMode = json.optInt("flash_attn", -1),
                rawJson = json.toString(),
            )
        }.getOrNull()
    }
}
