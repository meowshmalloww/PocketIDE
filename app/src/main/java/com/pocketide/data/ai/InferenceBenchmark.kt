package com.pocketide.data.ai

import android.util.Log
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

    fun start() {
        val runtime = Runtime.getRuntime()
        startMemoryBytes.set(runtime.totalMemory() - runtime.freeMemory())
        startTimeMs.set(System.currentTimeMillis())
        firstTokenTimeMs.set(0)
        finishTimeMs.set(0)
        tokenCount.set(0)
    }

    fun onToken() {
        tokenCount.incrementAndGet()
        if (firstTokenTimeMs.get() == 0L) {
            firstTokenTimeMs.set(System.currentTimeMillis())
        }
    }

    fun finish(): BenchmarkResult {
        finishTimeMs.set(System.currentTimeMillis())
        val runtime = Runtime.getRuntime()
        val endMemory = runtime.totalMemory() - runtime.freeMemory()

        val start = startTimeMs.get()
        val firstToken = firstTokenTimeMs.get()
        val end = finishTimeMs.get()
        val tokens = tokenCount.get()

        val ttftMs = if (firstToken > 0) firstToken - start else -1L
        val totalDurationMs = end - start
        val generationDurationMs = if (firstToken > 0) end - firstToken else totalDurationMs
        val tokensPerSecond = if (generationDurationMs > 0) {
            (tokens.toFloat() / generationDurationMs) * 1000f
        } else {
            0f
        }
        val memoryDeltaBytes = endMemory - startMemoryBytes.get()
        val peakMemoryBytes = runtime.totalMemory() - runtime.freeMemory()

        val result = BenchmarkResult(
            ttftMs = ttftMs,
            totalDurationMs = totalDurationMs,
            tokenCount = tokens,
            tokensPerSecond = tokensPerSecond,
            memoryDeltaBytes = memoryDeltaBytes,
            peakMemoryBytes = peakMemoryBytes,
            maxHeapBytes = runtime.maxMemory(),
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
    ) {
        fun summary(): String {
            val ttftStr = if (ttftMs >= 0) "${ttftMs}ms" else "N/A"
            val memDeltaMb = memoryDeltaBytes / (1024f * 1024f)
            val peakMb = peakMemoryBytes / (1024f * 1024f)
            val maxMb = maxHeapBytes / (1024f * 1024f)
            return "InferenceBenchmark: TTFT=$ttftStr, " +
                "${tokensPerSecond.format(2)} tok/s, " +
                "$tokenCount tokens in ${totalDurationMs}ms, " +
                "mem_delta=${memDeltaMb.format(1)}MB, " +
                "peak=${peakMb.format(1)}/${maxMb.format(1)}MB"
        }

        fun toJson(): String {
            return """{"ttft_ms":$ttftMs,"total_ms":$totalDurationMs,"tokens":$tokenCount,"" +
                "" "tps":${tokensPerSecond.format(4)},"mem_delta_bytes":$memoryDeltaBytes,"" +
                "" "peak_bytes":$peakMemoryBytes,"max_heap_bytes":$maxHeapBytes}"""
        }

        private fun Float.format(digits: Int): String =
            "%.${digits}f".format(this)
    }

    companion object {
        private const val TAG = "InferenceBenchmark"
    }
}
