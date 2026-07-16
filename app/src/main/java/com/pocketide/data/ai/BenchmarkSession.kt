package com.pocketide.data.ai

import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Accumulates benchmark results across multiple inference calls into a session
 * that can be exported as a comprehensive report for hackathon judging.
 *
 * Tracks:
 * - Device hardware info (model, SoC, CPU features, RAM)
 * - Model info (name, format, quantization, size)
 * - Per-generation metrics (TTFT, tok/s, memory, strategy, thread count)
 * - Warmup detection (first generation is slower due to model loading)
 * - Aggregate statistics (min/max/avg tok/s, TTFT distribution)
 *
 * Usage:
 * ```
 * val session = BenchmarkSession()
 * session.setModelInfo(modelPath, "GGUF", "Q4_0", fileSizeBytes)
 * // ... after each generation:
 * session.record(benchmarkResult, tuning, kvCacheQuantized, promptTokenEstimate)
 * // ... export:
 * val report = session.exportReport()
 * ```
 */
class BenchmarkSession {

    private val entries = mutableListOf<Entry>()
    private var modelPath: String = ""
    private var modelFormat: String = ""
    private var modelQuantization: String = ""
    private var modelSizeBytes: Long = 0
    private var sessionStartTimeMs: Long = System.currentTimeMillis()
    private var heuristicThreadCount: Int? = null
    private var selectedCalibration: ThreadCalibration? = null

    data class Entry(
        val index: Int,
        val timestampMs: Long,
        val ttftMs: Long,
        val totalDurationMs: Long,
        val tokenCount: Int,
        val tokensPerSecond: Float,
        val memoryDeltaBytes: Long,
        val peakMemoryBytes: Long,
        val maxHeapBytes: Long,
        val processPssDeltaBytes: Long,
        val peakProcessPssBytes: Long,
        val nativeHeapDeltaBytes: Long,
        val cpuTimeMs: Long,
        val strategy: String,
        val seqLen: Int,
        val threadCount: Int,
        val requestedThreadCount: Int,
        val batteryLevel: Int,
        val batteryTempCelsius: Float,
        val isCharging: Boolean,
        val memoryPressureRatio: Float,
        val thermalStatus: Int?,
        val kvCacheQuantized: Boolean,
        val kvCacheBytesPerElement: Int,
        val promptTokenEstimate: Int,
        val isWarmup: Boolean,
        val profile: String,
    )

    fun setModelInfo(path: String, format: String, quantization: String, sizeBytes: Long) {
        modelPath = path
        modelFormat = format
        modelQuantization = detectQuantization(path)
            ?: "$quantization (user label; not verified from GGUF metadata)"
        modelSizeBytes = sizeBytes
    }

    fun record(
        result: InferenceBenchmark.BenchmarkResult,
        tuning: InferenceTuning?,
        kvCacheQuantized: Boolean,
        kvCacheBytesPerElement: Int,
        promptTokenEstimate: Int,
        actualThreadCount: Int = tuning?.threadCount ?: 0,
        profile: String = "Interactive",
        isWarmupOverride: Boolean? = null,
    ) {
        val isWarmup = isWarmupOverride ?: entries.isEmpty()
        val entry = Entry(
            index = entries.size + 1,
            timestampMs = System.currentTimeMillis(),
            ttftMs = result.ttftMs,
            totalDurationMs = result.totalDurationMs,
            tokenCount = result.tokenCount,
            tokensPerSecond = result.tokensPerSecond,
            memoryDeltaBytes = result.memoryDeltaBytes,
            peakMemoryBytes = result.peakMemoryBytes,
            maxHeapBytes = result.maxHeapBytes,
            processPssDeltaBytes = result.processPssDeltaBytes,
            peakProcessPssBytes = result.peakProcessPssBytes,
            nativeHeapDeltaBytes = result.nativeHeapDeltaBytes,
            cpuTimeMs = result.cpuTimeMs,
            strategy = tuning?.strategy?.displayName ?: "Unknown",
            seqLen = tuning?.seqLen ?: 0,
            threadCount = actualThreadCount,
            requestedThreadCount = tuning?.threadCount ?: 0,
            batteryLevel = tuning?.batteryLevel ?: -1,
            batteryTempCelsius = tuning?.batteryTempCelsius ?: 25f,
            isCharging = tuning?.isCharging ?: false,
            memoryPressureRatio = tuning?.memoryPressureRatio ?: 0f,
            thermalStatus = tuning?.thermalStatus,
            kvCacheQuantized = kvCacheQuantized,
            kvCacheBytesPerElement = kvCacheBytesPerElement,
            promptTokenEstimate = promptTokenEstimate,
            isWarmup = isWarmup,
            profile = profile,
        )
        entries.add(entry)
        Log.i(TAG, "Session entry #${entry.index}: ${result.summary()}")
    }

    fun entryCount(): Int = entries.size

    fun snapshot(): List<Entry> = entries.toList()

    fun setThreadCalibration(heuristicThreads: Int, calibration: ThreadCalibration) {
        heuristicThreadCount = heuristicThreads
        selectedCalibration = calibration
    }

    fun clear() {
        entries.clear()
        heuristicThreadCount = null
        selectedCalibration = null
        sessionStartTimeMs = System.currentTimeMillis()
    }

    /**
     * Exports a comprehensive text report suitable for pasting into a chat
     * or sharing as a benchmark document. Includes device info, model info,
     * per-generation table, and aggregate statistics.
     */
    fun exportReport(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val now = sdf.format(Date())

        return buildString {
            appendLine("================================================================================")
            appendLine("POCKETIDE — BENCHMARK REPORT")
            appendLine("Generated: $now")
            appendLine("================================================================================")
            appendLine()

            // Device info
            appendLine("--- DEVICE INFO ---")
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("Model: ${Build.MODEL}")
            appendLine("Device: ${Build.DEVICE}")
            appendLine("Product: ${Build.PRODUCT}")
            appendLine("Android API: ${Build.VERSION.SDK_INT}")
            appendLine("Android Release: ${Build.VERSION.RELEASE}")
            appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("CPU Cores: ${Runtime.getRuntime().availableProcessors()}")
            appendLine("Max Heap: ${Runtime.getRuntime().maxMemory() / (1024 * 1024)}MB")
            appendLine()

            // CPU features
            val features = BackendInfo.features
            appendLine("--- CPU FEATURES ---")
            appendLine("Architecture: ${if (features.isArm64) "arm64-v8a" else "non-Arm"}")
            appendLine("NEON: ${features.hasNeon}")
            appendLine("i8mm: ${features.hasI8mm}")
            appendLine("Dotprod: ${features.hasDotprod}")
            appendLine("SVE: ${features.hasSve}")
            appendLine("SVE2: ${features.hasSve2}")
            appendLine("SME2: ${features.hasSme2}")
            appendLine("FP16: ${features.hasFp16}")
            appendLine("KleidiAI INT4 CPU-capable (kernel use not verified): ${features.kleidiAiInt4Capable}")
            appendLine("KleidiAI INT8 CPU-capable (kernel use not verified): ${features.kleidiAiInt8Capable}")
            appendLine("SME2 capable: ${features.sme2Capable}")
            appendLine("Active GGUF native library: ${BackendInfo.llamaCppNativeLibrary}")
            appendLine("Native dispatch: runtime-selected from Arm CPU feature flags")
            appendLine("Heuristic starting thread count (not assumed optimal): ${BackendInfo.optimalThreadCount}")
            appendLine()

            // Model info
            appendLine("--- MODEL INFO ---")
            appendLine("Model: ${modelPath.substringAfterLast("/")}")
            appendLine("Format: $modelFormat")
            appendLine("Quantization: $modelQuantization")
            appendLine("File size: ${"%.1f".format(modelSizeBytes / (1024f * 1024f))}MB ($modelSizeBytes bytes)")
            val arch = runCatching { ModelSpec.detect(modelPath) }.getOrNull()
            if (arch != null) {
                appendLine("Architecture: ${arch.displayName}")
                appendLine("Parameters: ${arch.paramCountBillion}B")
                appendLine("Layers: ${arch.numLayers}")
                appendLine("Hidden dim: ${arch.hiddenDim}")
                appendLine("KV heads: ${arch.numKvHeads}")
                appendLine("Head dim: ${arch.headDim}")
                appendLine("Max context: ${arch.maxContextLength}")
            }
            appendLine()

            // Per-generation table
            if (entries.isNotEmpty()) {
                appendLine("--- PER-GENERATION RESULTS ---")
                appendLine("Run | Profile | TTFT(ms) | Total(ms) | Tokens | tok/s   | JavaDelta | ProcessPSS | NativeDelta | SeqLen | Req/Actual | Battery% | TempC | PromptTok | Warmup")
                appendLine("----|---------|----------|-----------|--------|---------|-----------|------------|-------------|--------|------------|----------|-------|-----------|-------")

                entries.forEach { e ->
                    appendLine(
                        "${e.index.toString().padStart(3)} | " +
                            "${e.profile.take(8).padEnd(8)} | " +
                            "${e.ttftMs.toString().padStart(8)} | " +
                            "${e.totalDurationMs.toString().padStart(9)} | " +
                            "${e.tokenCount.toString().padStart(6)} | " +
                            "${"%.2f".format(e.tokensPerSecond).padStart(7)} | " +
                            "${"%.1fMB".format(e.memoryDeltaBytes / (1024f * 1024f)).padStart(9)} | " +
                            "${"%.1fMB".format(e.peakProcessPssBytes / (1024f * 1024f)).padStart(10)} | " +
                            "${"%.1fMB".format(e.nativeHeapDeltaBytes / (1024f * 1024f)).padStart(11)} | " +
                            "${e.seqLen.toString().padStart(6)} | " +
                            "${"${e.requestedThreadCount}/${e.threadCount}".padStart(10)} | " +
                            "${e.batteryLevel.toString().padStart(8)} | " +
                            "${"%.1f".format(e.batteryTempCelsius).padStart(5)} | " +
                            "${e.promptTokenEstimate.toString().padStart(9)} | " +
                            if (e.isWarmup) "YES" else "no",
                    )
                }
                appendLine()

                // Aggregate stats (excluding warmup)
                val steady = entries.filter { !it.isWarmup }
                if (steady.isNotEmpty()) {
                    appendLine("--- AGGREGATE STATISTICS (excluding warmup) ---")
                    val tpsValues = steady.map { it.tokensPerSecond }
                    val ttftValues = steady.mapNotNull { it.ttftMs.takeIf { t -> t >= 0 } }
                    val memDeltas = steady.map { it.memoryDeltaBytes / (1024f * 1024f) }

                    appendLine("Generations: ${steady.size} (+${entries.count { it.isWarmup }} warmup excluded)")
                    appendLine("Tokens/sec — min: ${"%.2f".format(tpsValues.min())}, " +
                        "max: ${"%.2f".format(tpsValues.max())}, " +
        "avg: ${"%.2f".format(tpsValues.average())}, " +
        "median: ${"%.2f".format(tpsValues.sorted().let { it[it.size / 2] })}")
                    if (ttftValues.isNotEmpty()) {
                        appendLine("TTFT (ms)  — min: ${ttftValues.min()}, " +
            "max: ${ttftValues.max()}, " +
            "avg: ${ttftValues.average().toInt()}, " +
            "p50: ${ttftValues.sorted()[ttftValues.size / 2]}")
                    }
                    appendLine("Mem delta  — min: ${"%.1f".format(memDeltas.min())}MB, " +
        "max: ${"%.1f".format(memDeltas.max())}MB, " +
        "avg: ${"%.1f".format(memDeltas.average())}MB")
                    appendLine("Total tokens generated: ${steady.sumOf { it.tokenCount }}")
                    appendLine("Total generation time: ${steady.sumOf { it.totalDurationMs }}ms")
                    appendLine()

                    val profiles = steady.groupBy { it.profile }
                    if (profiles.size > 1) {
                        appendLine("--- MEASURED THREAD PROFILES ---")
                        profiles.forEach { (profile, runs) ->
                            appendLine("$profile: ${"%.2f".format(runs.map { it.tokensPerSecond }.average())} tok/s avg, " +
                                "${runs.map { it.ttftMs }.filter { it >= 0 }.average().toLong()}ms TTFT avg, " +
                                "actual threads ${runs.map { it.threadCount }.distinct().joinToString()}")
                        }
                        appendLine()
                    }

                    selectedCalibration?.let { calibration ->
                        appendLine("--- CALIBRATION DECISION ---")
                        appendLine("Selected: ${calibration.threadCount} thread(s)")
                        appendLine("Selected median decode: ${"%.2f".format(calibration.medianTokensPerSecond)} tok/s")
                        appendLine("Selected average TTFT: ${calibration.averageTtftMs}ms")
                        appendLine("Previous heuristic: ${heuristicThreadCount ?: "unknown"} thread(s)")
                        appendLine("Rule: fastest measured median; within 1%, prefer lower PSS then fewer threads")
                        appendLine("Saved for normal GGUF chat on this exact device and model file")
                        appendLine()
                    }

                    // Strategy distribution
                    val strategies = steady.groupBy { it.strategy }
                    appendLine("--- STRATEGY DISTRIBUTION ---")
                    strategies.forEach { (strategy, list) ->
                        appendLine("$strategy: ${list.size} runs (${list.sumOf { it.tokenCount }} tokens)")
                    }
                    appendLine()

                    // KV cache info
                    val quantizedCount = steady.count { it.kvCacheQuantized }
                    if (quantizedCount > 0) {
                        appendLine("--- KV CACHE ---")
                        appendLine("INT8 KV cache activated: $quantizedCount/${steady.size} runs")
                        appendLine("KV cache bytes/element: ${steady.first().kvCacheBytesPerElement}")
                    }
                }
            }

            appendLine("================================================================================")
        }
    }

    /**
     * Exports the report as JSON for programmatic processing.
     */
    fun exportJson(): String {
        val f = BackendInfo.features
        return JSONObject()
            .put("schema_version", 4)
            .put("device", JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("api_level", Build.VERSION.SDK_INT)
                .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
                .put("cpu_cores", Runtime.getRuntime().availableProcessors())
                .put("max_heap_bytes", Runtime.getRuntime().maxMemory()))
            .put("cpu_features", JSONObject()
                .put("arm64", f.isArm64)
                .put("neon", f.hasNeon)
                .put("i8mm", f.hasI8mm)
                .put("dotprod", f.hasDotprod)
                .put("sve2", f.hasSve2)
                .put("sme2", f.hasSme2)
                .put("kleidiai_int4", f.kleidiAiInt4Capable)
                .put("kleidiai_int8", f.kleidiAiInt8Capable))
            .put("gguf_runtime", JSONObject()
                .put("engine", "llama.cpp via KotlinLlamaCpp")
                .put("native_library", BackendInfo.llamaCppNativeLibrary)
                .put("dispatch", "runtime CPU-feature selection"))
            .put("model", JSONObject()
                .put("name", modelPath.substringAfterLast('/'))
                .put("format", modelFormat)
                .put("quantization", modelQuantization)
                .put("size_bytes", modelSizeBytes))
            .put("thread_calibration", selectedCalibration?.let { calibration ->
                JSONObject()
                    .put("selected_threads", calibration.threadCount)
                    .put("heuristic_threads", heuristicThreadCount ?: JSONObject.NULL)
                    .put("median_tps", calibration.medianTokensPerSecond.toDouble())
                    .put("average_ttft_ms", calibration.averageTtftMs)
                    .put("average_pss_bytes", calibration.averagePeakProcessPssBytes)
                    .put("measured_at_ms", calibration.measuredAtMs)
            } ?: JSONObject.NULL)
            .put("generations", JSONArray().apply {
                entries.forEach { e ->
                    put(JSONObject()
                        .put("run", e.index)
                        .put("ttft_ms", e.ttftMs)
                        .put("total_ms", e.totalDurationMs)
                        .put("tokens", e.tokenCount)
                        .put("tps", e.tokensPerSecond.toDouble())
                        .put("mem_delta_bytes", e.memoryDeltaBytes)
                        .put("peak_bytes", e.peakMemoryBytes)
                        .put("process_pss_delta_bytes", e.processPssDeltaBytes)
                        .put("process_pss_peak_bytes", e.peakProcessPssBytes)
                        .put("native_heap_delta_bytes", e.nativeHeapDeltaBytes)
                        .put("cpu_time_ms", e.cpuTimeMs)
                        .put("strategy", e.strategy)
                        .put("seq_len", e.seqLen)
                        .put("requested_threads", e.requestedThreadCount)
                        .put("actual_threads", e.threadCount)
                        .put("battery_pct", e.batteryLevel)
                        .put("battery_temp_c", e.batteryTempCelsius.toDouble())
                        .put("thermal_status", e.thermalStatus ?: JSONObject.NULL)
                        .put("mem_pressure", e.memoryPressureRatio.toDouble())
                        .put("kv_estimate_quantized", e.kvCacheQuantized)
                        .put("prompt_tokens_est", e.promptTokenEstimate)
                        .put("warmup", e.isWarmup)
                        .put("profile", e.profile))
                }
            })
            .toString(2)
    }

    companion object {
        private const val TAG = "BenchmarkSession"

        private fun detectQuantization(path: String): String? =
            Regex("(?i)(Q[2-8](?:_[A-Z0-9]+)+|IQ[1-4](?:_[A-Z0-9]+)+)")
                .find(path.substringAfterLast('/'))
                ?.value
                ?.uppercase(Locale.US)
    }
}
