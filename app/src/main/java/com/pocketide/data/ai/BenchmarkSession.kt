package com.pocketide.data.ai

import android.util.Log
import java.util.Locale

/** Accumulates real inference measurements and exports reproducible evidence. */
class BenchmarkSession {

    private val entries = mutableListOf<Entry>()
    private var sessionStartTimeMs = System.currentTimeMillis()
    private var modelPath: String = "Unknown"
    private var modelFormat: String = "Unknown"
    private var modelQuantization: String = "Unknown"
    private var modelSizeBytes: Long = 0
    private var heuristicThreadCount: Int? = null
    private var selectedCalibration: ThreadCalibration? = null
    private var protocol: BenchmarkProtocol? = null
    private var nativeKernelBenchmark: NativeKernelBenchmark? = null
    private var nativeKernelBenchmarkError: String? = null
    private var resourcePlan: InferenceResourcePlan? = null
    private var successfullyLoadedNativeLibrary: String? = null
    private var previousProcessExit: PreviousProcessExit? = null
    private val powerSamples = mutableListOf<BenchmarkPowerSample>()

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
        val promptTokenCount: Int,
        val promptTokensExact: Boolean,
        val isWarmup: Boolean,
        val profile: String,
        val workload: String,
        val phase: String,
    )

    fun setModelInfo(path: String, format: String, quantization: String, sizeBytes: Long) {
        modelPath = path
        modelFormat = format
        modelQuantization = detectQuantization(path) ?: if (format == "PTE") {
            "$quantization (user label; quantization cannot be inferred from the PTE filename)"
        } else {
            "$quantization (user label; not verified from GGUF metadata)"
        }
        modelSizeBytes = sizeBytes
    }

    fun setProtocol(value: BenchmarkProtocol) {
        protocol = value
    }

    fun setConfirmationThreads(threads: List<Int>) {
        protocol = protocol?.copy(confirmationThreads = threads.distinct().sorted())
    }

    fun setNativeKernelBenchmark(result: NativeKernelBenchmark?, error: String? = null) {
        nativeKernelBenchmark = result
        nativeKernelBenchmarkError = error
    }

    fun nativeKernelBenchmark(): NativeKernelBenchmark? = nativeKernelBenchmark

    fun setResourcePlan(value: InferenceResourcePlan) {
        resourcePlan = value
    }

    fun setSuccessfullyLoadedNativeLibrary(value: String?) {
        successfullyLoadedNativeLibrary = value
    }

    fun setPreviousProcessExit(value: PreviousProcessExit?) {
        previousProcessExit = value
    }

    fun recordPowerSample(value: BenchmarkPowerSample) {
        powerSamples += value
    }

    @Suppress("LongParameterList")
    fun record(
        result: InferenceBenchmark.BenchmarkResult,
        tuning: InferenceTuning?,
        kvCacheQuantized: Boolean,
        kvCacheBytesPerElement: Int,
        promptTokenEstimate: Int,
        promptTokensExact: Boolean = false,
        configuredThreadCount: Int = tuning?.threadCount ?: 0,
        profile: String = "Interactive",
        isWarmupOverride: Boolean? = null,
        workload: String = "interactive_chat",
        phase: String = "interactive",
    ) {
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
            threadCount = configuredThreadCount,
            requestedThreadCount = tuning?.threadCount ?: 0,
            batteryLevel = tuning?.batteryLevel ?: -1,
            batteryTempCelsius = tuning?.batteryTempCelsius ?: 25f,
            isCharging = tuning?.isCharging ?: false,
            memoryPressureRatio = tuning?.memoryPressureRatio ?: 0f,
            thermalStatus = tuning?.thermalStatus,
            kvCacheQuantized = kvCacheQuantized,
            kvCacheBytesPerElement = kvCacheBytesPerElement,
            promptTokenCount = promptTokenEstimate,
            promptTokensExact = promptTokensExact,
            isWarmup = isWarmupOverride ?: entries.isEmpty(),
            profile = profile,
            workload = workload,
            phase = phase,
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
        protocol = null
        nativeKernelBenchmark = null
        nativeKernelBenchmarkError = null
        resourcePlan = null
        successfullyLoadedNativeLibrary = null
        powerSamples.clear()
        sessionStartTimeMs = System.currentTimeMillis()
    }

    fun exportReport(): String = BenchmarkTextReport.export(exportSnapshot())

    fun exportJson(): String = BenchmarkJsonReport.export(exportSnapshot())

    fun dashboard(): BenchmarkDashboard = BenchmarkDashboardFactory.create(exportSnapshot())

    private fun exportSnapshot() = BenchmarkExportSnapshot(
        entries = entries.toList(),
        sessionStartTimeMs = sessionStartTimeMs,
        modelPath = modelPath,
        modelFormat = modelFormat,
        modelQuantization = modelQuantization,
        modelSizeBytes = modelSizeBytes,
        heuristicThreadCount = heuristicThreadCount,
        selectedCalibration = selectedCalibration,
        protocol = protocol,
        nativeKernelBenchmark = nativeKernelBenchmark,
        nativeKernelBenchmarkError = nativeKernelBenchmarkError,
        resourcePlan = resourcePlan,
        successfullyLoadedNativeLibrary = successfullyLoadedNativeLibrary,
        previousProcessExit = previousProcessExit,
        powerSamples = powerSamples.toList(),
    )

    companion object {
        private const val TAG = "BenchmarkSession"

        private fun detectQuantization(path: String): String? =
            Regex("(?i)(Q[2-8](?:_[A-Z0-9]+)+|IQ[1-4](?:_[A-Z0-9]+)+)")
                .find(path.substringAfterLast('/').substringAfterLast('\\'))
                ?.value
                ?.uppercase(Locale.US)
    }
}

internal data class BenchmarkExportSnapshot(
    val entries: List<BenchmarkSession.Entry>,
    val sessionStartTimeMs: Long,
    val modelPath: String,
    val modelFormat: String,
    val modelQuantization: String,
    val modelSizeBytes: Long,
    val heuristicThreadCount: Int?,
    val selectedCalibration: ThreadCalibration?,
    val protocol: BenchmarkProtocol?,
    val nativeKernelBenchmark: NativeKernelBenchmark?,
    val nativeKernelBenchmarkError: String?,
    val resourcePlan: InferenceResourcePlan?,
    val successfullyLoadedNativeLibrary: String?,
    val previousProcessExit: PreviousProcessExit?,
    val powerSamples: List<BenchmarkPowerSample>,
)
