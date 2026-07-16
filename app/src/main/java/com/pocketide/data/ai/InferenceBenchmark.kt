package com.pocketide.data.ai

import android.os.SystemClock
import android.os.Debug
import android.os.Process
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks real-time inference metrics for on-device LLM generation.
 *
 * Measures:
 * - **TTFT** (Time To First Token): latency from generation start to first token.
 * - **Tokens/sec**: sustained generation rate after first token.
 * - **Total tokens**: count of generated tokens.
 * - **Memory delta**: heap usage before and after generation.
 * - **Generation duration**: wall-clock time for the full generation.
 *
 * Usage:
 * ```
 * val benchmark = InferenceBenchmark()
 * benchmark.start()
 * // ... on each token:
 * benchmark.onToken()
 * // ... after generation:
 * val result = benchmark.finish()
 * ```
 */
class InferenceBenchmark {

    private val startTimeMs = AtomicLong(0)
    private val firstTokenTimeMs = AtomicLong(0)
    private val finishTimeMs = AtomicLong(0)
    private val tokenCount = AtomicInteger(0)
    private val startMemoryBytes = AtomicLong(0)
    private val peakMemoryBytes = AtomicLong(0)
    private val startPssBytes = AtomicLong(0)
    private val peakPssBytes = AtomicLong(0)
    private val startNativeHeapBytes = AtomicLong(0)
    private val startCpuTimeMs = AtomicLong(0)

    fun start() {
        val runtime = Runtime.getRuntime()
        val startMemory = usedHeapBytes(runtime)
        startMemoryBytes.set(startMemory)
        peakMemoryBytes.set(startMemory)
        val pss = processPssBytes()
        startPssBytes.set(pss)
        peakPssBytes.set(pss)
        startNativeHeapBytes.set(nativeHeapBytes())
        startCpuTimeMs.set(Process.getElapsedCpuTime())
        startTimeMs.set(SystemClock.elapsedRealtime())
        firstTokenTimeMs.set(0)
        finishTimeMs.set(0)
        tokenCount.set(0)
    }

    fun onToken() {
        tokenCount.incrementAndGet()
        observePeakMemory()
        if (firstTokenTimeMs.get() == 0L) {
            firstTokenTimeMs.set(SystemClock.elapsedRealtime())
            observeProcessMemory()
        }
    }

    fun finish(exactTokenCount: Int? = null): BenchmarkResult {
        finishTimeMs.set(SystemClock.elapsedRealtime())
        val runtime = Runtime.getRuntime()
        val endMemory = usedHeapBytes(runtime)
        observePeakMemory(runtime)

        val start = startTimeMs.get()
        val firstToken = firstTokenTimeMs.get()
        val end = finishTimeMs.get()
        val tokens = exactTokenCount?.takeIf { it >= 0 } ?: tokenCount.get()

        val ttftMs = if (firstToken > 0) firstToken - start else -1L
        val totalDurationMs = end - start
        val generationDurationMs = if (firstToken > 0) end - firstToken else totalDurationMs
        val timedTokens = (tokens - 1).coerceAtLeast(0)
        val tokensPerSecond = if (generationDurationMs > 0 && timedTokens > 0) {
            (timedTokens.toFloat() / generationDurationMs) * 1000f
        } else {
            0f
        }
        val memoryDeltaBytes = endMemory - startMemoryBytes.get()
        val observedPeakMemoryBytes = peakMemoryBytes.get()
        observeProcessMemory()
        val endPss = processPssBytes()
        val endNativeHeap = nativeHeapBytes()

        val result = BenchmarkResult(
            ttftMs = ttftMs,
            totalDurationMs = totalDurationMs,
            tokenCount = tokens,
            tokensPerSecond = tokensPerSecond,
            memoryDeltaBytes = memoryDeltaBytes,
            peakMemoryBytes = observedPeakMemoryBytes,
            maxHeapBytes = runtime.maxMemory(),
            processPssDeltaBytes = endPss - startPssBytes.get(),
            peakProcessPssBytes = peakPssBytes.get().coerceAtLeast(endPss),
            nativeHeapDeltaBytes = endNativeHeap - startNativeHeapBytes.get(),
            cpuTimeMs = (Process.getElapsedCpuTime() - startCpuTimeMs.get()).coerceAtLeast(0),
        )

        Log.i(TAG, result.summary())
        return result
    }

    data class BenchmarkResult(
        val ttftMs: Long,
        val totalDurationMs: Long,
        val tokenCount: Int,
        val tokensPerSecond: Float,
        val memoryDeltaBytes: Long,
        val peakMemoryBytes: Long,
        val maxHeapBytes: Long,
        val processPssDeltaBytes: Long = 0,
        val peakProcessPssBytes: Long = 0,
        val nativeHeapDeltaBytes: Long = 0,
        val cpuTimeMs: Long = 0,
    ) {
        fun summary(): String {
            val ttftStr = if (ttftMs >= 0) "${ttftMs}ms" else "N/A"
            val memDeltaMb = memoryDeltaBytes / (1024f * 1024f)
            val peakMb = peakMemoryBytes / (1024f * 1024f)
            val maxMb = maxHeapBytes / (1024f * 1024f)
            val pssMb = peakProcessPssBytes / (1024f * 1024f)
            return "InferenceBenchmark: TTFT=$ttftStr, " +
                "${tokensPerSecond.format(2)} tok/s, " +
                "$tokenCount tokens in ${totalDurationMs}ms, " +
                "mem_delta=${memDeltaMb.format(1)}MB, " +
                "java_heap_peak=${peakMb.format(1)}/${maxMb.format(1)}MB, " +
                "process_pss=${pssMb.format(1)}MB, cpu=${cpuTimeMs}ms"
        }

        fun toJson(): String =
            """{"ttft_ms":$ttftMs,"total_ms":$totalDurationMs,"tokens":$tokenCount,"tps":${tokensPerSecond.format(4)},"java_heap_delta_bytes":$memoryDeltaBytes,"java_heap_peak_bytes":$peakMemoryBytes,"max_heap_bytes":$maxHeapBytes,"process_pss_delta_bytes":$processPssDeltaBytes,"process_pss_peak_bytes":$peakProcessPssBytes,"native_heap_delta_bytes":$nativeHeapDeltaBytes,"cpu_time_ms":$cpuTimeMs}"""

        private fun Float.format(digits: Int): String =
            String.format(Locale.US, "%.${digits}f", this)
    }

    companion object {
        private const val TAG = "InferenceBenchmark"
    }

    private fun observePeakMemory(runtime: Runtime = Runtime.getRuntime()) {
        val observed = usedHeapBytes(runtime)
        while (true) {
            val previousPeak = peakMemoryBytes.get()
            if (observed <= previousPeak || peakMemoryBytes.compareAndSet(previousPeak, observed)) return
        }
    }

    private fun usedHeapBytes(runtime: Runtime): Long = runtime.totalMemory() - runtime.freeMemory()

    private fun observeProcessMemory() {
        val observed = processPssBytes()
        while (true) {
            val previous = peakPssBytes.get()
            if (observed <= previous || peakPssBytes.compareAndSet(previous, observed)) return
        }
    }

    private fun processPssBytes(): Long = runCatching { Debug.getPss() * 1024L }.getOrDefault(0L)

    private fun nativeHeapBytes(): Long = runCatching { Debug.getNativeHeapAllocatedSize() }.getOrDefault(0L)
}
